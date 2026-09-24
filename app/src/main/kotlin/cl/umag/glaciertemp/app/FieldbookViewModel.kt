package cl.umag.glaciertemp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.umag.glaciertemp.core.fieldbook.*
import cl.umag.glaciertemp.core.geo.GpsPointStore
import cl.umag.glaciertemp.core.geo.SavedPoint
import cl.umag.glaciertemp.core.geo.SavedPoint.toFieldPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Cual de las dos listas de nombres reutilizables se esta tocando. */
enum class NameList { PEOPLE, RECEIVERS, SPECIES }

/** Una peticion de posicion en curso. */
data class PositionRequest(val waiting: Boolean = false, val note: String? = null)

data class FieldbookUiState(
    /** Ya filtradas por campana y por tipo: es lo que la lista pinta tal cual. */
    val entries: List<FieldEntry> = emptyList(),
    /** Cuantas hay de cada tipo en la campana que se esta viendo, para los botones de filtro. */
    val counts: Map<EntryType, Int> = emptyMap(),
    val total: Int = 0,
    /** null = todas. */
    val filter: EntryType? = null,
    /** La entrada abierta. Es una COPIA de trabajo; el disco se actualiza al guardar. */
    val open: FieldEntry? = null,
    val people: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val species: List<String> = emptyList(),
    val savedPoints: List<SavedPoint.Solution> = emptyList(),
    val positionRequest: PositionRequest? = null,
    /** La campana abierta, o null si todavia no se ha empezado ninguna. */
    val activeCampaign: Campaign? = null,
    val archivedCampaigns: List<Campaign> = emptyList(),
    /** La campana archivada que se esta mirando, o null para la vista normal. */
    val viewingCampaign: Campaign? = null,
    val sky: SkyView? = null,
    val exporting: Boolean = false,
    val error: String? = null,
    val note: String? = null,
)

/**
 * La libreta de terreno.
 *
 * ViewModel propio, como el de GPS y por el mismo motivo: no comparte nada con la placa ni
 * con el promediado, y meterlo en otro habria creado un estado donde desconectar un aparato
 * puede afectar a una anotacion en curso.
 *
 * TODO CAMBIO SE ESCRIBE EN EL ACTO. No hay boton de guardar en las entradas: cada campo que
 * se edita persiste inmediatamente. Es deliberado y es lo contrario de lo que se suele hacer:
 * en terreno la app se cierra sola --frio, bateria, el sistema matando procesos en segundo
 * plano durante una medicion de tres horas-- y un borrador en memoria es trabajo que no se
 * puede repetir. El coste es reescribir unos kilobytes por pulsacion, que no se nota.
 */
class FieldbookViewModel : ViewModel() {

    var store: FieldbookStore? = null
    var people: NameStore? = null
    var receivers: NameStore? = null
    var species: NameStore? = null
    var campaigns: CampaignStore? = null
    var gpsPoints: GpsPointStore? = null
    var location: LocationSource? = null
    var sky: SkySource? = null

    /** Lo rellena la actividad. Se pide al ir a usar el GPS, no al abrir la libreta. */
    var requestLocationPermission: (() -> Unit)? = null

    private val _state = MutableStateFlow(FieldbookUiState())
    val state: StateFlow<FieldbookUiState> = _state.asStateFlow()

    private var posJob: Job? = null

    /** Lo ultimo que hay que escribir y aun no esta en disco. */
    private var pendiente: FieldEntry? = null
    private var guardadoJob: Job? = null

    // ------------------------------------- listas -------------------------------------

    /**
     * Recarga TODO lo que la lista muestra: entradas, campanas y las listas de nombres.
     *
     * Las listas de nombres se recargan aqui y en [create] y [open], y no solo al entrar en la
     * libreta. Ese era el bug: un receptor escrito dentro de una baliza quedaba guardado en
     * disco, pero el estado con el que se pinta el desplegable se habia cargado al abrir la
     * pantalla y no volvia a mirarse, asi que al crear la siguiente medicion el nombre no
     * estaba en la lista -- hasta que cualquier otra cosa provocaba un refresco.
     */
    fun refresh() {
        val s = store ?: return
        // Barrido de medios sueltos: una foto tomada y luego descartada, o una captura que se
        // quedo a medias porque el sistema mato la app con la camara delante. Solo alcanza a
        // los que llevan mas de diez minutos parados, para no tocar una captura en curso.
        viewModelScope.launch { withContext(Dispatchers.IO) { s.purgeOrphanMedia() } }

        val activa = campaigns?.active()
        val archivadas = campaigns?.archivedCampaigns() ?: emptyList()
        val mirando = _state.value.viewingCampaign?.id?.let { id -> archivadas.firstOrNull { it.id == id } }
        val todas = s.list()
        val deLaVista = enVista(todas, mirando, activa)

        _state.value = _state.value.copy(
            entries = aplicarFiltro(deLaVista, _state.value.filter),
            counts = EntryType.entries.associateWith { t -> deLaVista.count { it.type == t } },
            total = deLaVista.size,
            activeCampaign = activa,
            archivedCampaigns = archivadas,
            viewingCampaign = mirando,
            people = people?.list() ?: emptyList(),
            receivers = receivers?.list() ?: emptyList(),
            species = species?.list() ?: emptyList(),
        )
    }

    /**
     * Que entradas pertenecen a la vista actual.
     *
     * En la vista normal salen las de la campana abierta Y las que no tienen ninguna. Las
     * segundas son las anotadas antes de que existieran las campanas: dejarlas fuera las
     * haria desaparecer sin que nadie las hubiera archivado, que es perder datos a los ojos
     * del usuario aunque el fichero siga en disco.
     */
    private fun enVista(todas: List<FieldEntry>, mirando: Campaign?, activa: Campaign?):
        List<FieldEntry> = CampaignView.inView(todas, mirando, activa)

    /**
     * Ya existe otra entrada del MISMO tipo con este nombre?
     *
     * Se consulta el almacen entero y no `state.entries`, que viene filtrado por campana y
     * por tipo: el choque que importa es con cualquier baliza, este o no a la vista. Una
     * baliza "B1" en la campana del ano pasado y otra "B1" ahora son dos filas distintas en
     * el CSV exportado y nadie sabra cual es cual.
     *
     * Por TIPO y no global: una baliza "B1" y una muestra dendro "B1" no se estorban, y
     * avisar de eso seria ruido que ensena a ignorar el aviso.
     *
     * Se avisa, no se impide. Un nombre repetido casi siempre es un descuido, pero decidir
     * por el usuario que no puede escribirlo es peor: en terreno puede haber una razon que
     * el programa no conoce, y bloquear deja sin salida a quien esta con las manos frias.
     */
    fun nameClash(type: EntryType, name: String, selfId: String): Boolean {
        val n = name.trim()
        if (n.isBlank()) return false
        val s = store ?: return false
        return s.list().any { otra ->
            otra.id != selfId && otra.type == type && nombreDe(otra).trim().equals(n, ignoreCase = true)
        }
    }

    private fun nombreDe(e: FieldEntry): String = when (e.type) {
        EntryType.STAKE -> e.stakeName
        EntryType.GNSS -> e.pointName
        EntryType.DENDRO -> e.sampleLabel
        EntryType.NOTE -> ""
    }

    private fun aplicarFiltro(l: List<FieldEntry>, f: EntryType?) =
        if (f == null) l else l.filter { it.type == f }

    fun setFilter(f: EntryType?) {
        _state.value = _state.value.copy(filter = f)
        refresh()
    }

    // ------------------------------------ campanas ------------------------------------

    /** Mira una campana archivada. La lista pasa a mostrar solo sus entradas. */
    fun viewCampaign(c: Campaign?) {
        _state.value = _state.value.copy(viewingCampaign = c, filter = null)
        refresh()
    }

    /**
     * Renombra una campana, este abierta o archivada.
     *
     * Para la ABIERTA es el punto de poder bautizarla sin cerrarla: el nombre se sabe el
     * primer dia --"Bernal, febrero"-- y obligar a terminar la campana para poder escribirlo
     * convierte un rotulo en una decision.
     *
     * Para una ARCHIVADA es la salida de la trampa contraria: hasta ahora la unica forma de
     * cambiarle el nombre era reabrirla y volver a cerrarla, y ese viaje de ida y vuelta es
     * justo el que dejaba una campana nueva vacia por el camino.
     */
    fun renameCampaign(id: String, name: String) {
        campaigns?.rename(id, name)
        refresh()
    }

    /**
     * Cierra la campana abierta. Las entradas NO se mueven ni se tocan.
     *
     * Solo dejan de salir en la lista principal, que es lo que hace sitio para empezar la
     * siguiente. La proxima entrada que se cree abrira una campana nueva sola.
     */
    fun archiveActiveCampaign(name: String) {
        val cs = campaigns ?: return
        val activa = cs.active() ?: return
        if (name.isNotBlank()) cs.rename(activa.id, name)

        // SELLAR LO QUE NO TENIA CAMPANA ANTES DE ARCHIVAR.
        //
        // La vista normal muestra las entradas de la campana abierta Y las que no tienen
        // ninguna --las anotadas antes de que las campanas existieran--. Las primeras se van
        // solas al archivar, porque dejan de coincidir con la campana activa; las segundas se
        // quedaban en pantalla PARA SIEMPRE, y a los ojos de quien acaba de cerrar la
        // campana eso es que archivar no ha hecho nada.
        //
        // Se sellan con la campana que se esta cerrando, que es lo que significan: estaban a
        // la vista durante toda ella y son parte de ese trabajo de terreno.
        store?.let { st ->
            CampaignView.unfiled(st.list()).forEach { st.save(it.copy(campaignId = activa.id)) }
        }

        cs.archive(activa.id)
        _state.value = _state.value.copy(
            viewingCampaign = null, filter = null,
            note = "Campaign “${name.ifBlank { activa.displayName() }}” archived. " +
                   "Its entries are in Archived campaigns.")
        refresh()
    }

    /** Vuelve a abrirla, si no hay otra abierta. */
    fun unarchiveCampaign(id: String) {
        val cs = campaigns ?: return
        if (!cs.unarchive(id)) {
            _state.value = _state.value.copy(
                error = "Finish the campaign that is still open before reopening another one.")
            return
        }
        _state.value = _state.value.copy(viewingCampaign = null)
        refresh()
    }

    /** Los puntos de GPS tools, resueltos. Solo se piden al ir a elegir uno. */
    fun refreshSavedPoints() {
        val g = gpsPoints ?: return
        viewModelScope.launch {
            val puntos = withContext(Dispatchers.IO) { SavedPoint.solutions(g) }
            _state.value = _state.value.copy(savedPoints = puntos)
        }
    }

    fun dismissNote() { _state.value = _state.value.copy(note = null, error = null) }

    /** Un fallo que la pantalla necesita contar y el ViewModel no genero. */
    fun report(message: String) { _state.value = _state.value.copy(error = message) }

    // ------------------------------------ entradas ------------------------------------

    /**
     * Crea una entrada y la abre.
     *
     * La persona se propone con la ultima que se uso, que es lo que pide la especificacion.
     * Sale de la cabeza de la lista y no de un campo "ultimo usado" aparte: un segundo dato
     * que decir lo mismo es un segundo dato que puede discrepar.
     */
    fun create(type: EntryType) {
        val s = store ?: return
        // Empezar a anotar no exige haber creado una campana a mano: se abre sola con la
        // primera entrada y se le pone nombre al terminarla, que es cuando uno sabe como se
        // llamo aquello.
        val campana = campaigns?.openOrCurrent()
        val quien = people?.mostRecent() ?: ""
        val e = s.create(type, System.currentTimeMillis(), quien).copy(campaignId = campana?.id)
        val inicial = when (type) {
            // Una nota general nace con su primera anotacion de texto ya puesta: abrirla en
            // una pantalla vacia con un boton "anadir texto" es un paso de mas para lo que se
            // hace el 100 % de las veces.
            EntryType.NOTE -> e.copy(items = listOf(
                NoteItem(NoteItemKind.TEXT, e.createdEpochMillis)))
            EntryType.STAKE -> e
            EntryType.GNSS -> e.copy(gnss = GnssSession(receiver = receivers?.mostRecent() ?: ""))
            EntryType.DENDRO -> e.copy(species = species?.mostRecent() ?: "")
        }
        s.save(inicial)
        _state.value = _state.value.copy(open = inicial, error = null, filter = null)
        // Recarga tambien las listas de nombres: un receptor escrito dentro de otra entrada
        // tiene que estar en el desplegable de esta.
        refresh()
    }

    fun open(id: String) {
        val s = store ?: return
        val e = s.load(id)
        if (e == null) {
            _state.value = _state.value.copy(error = "That entry could not be read")
            return
        }
        _state.value = _state.value.copy(open = e, error = null)
        refreshNames()
    }

    /**
     * Cierra la entrada abierta. Es lo que hace el boton Done y tambien el atras del sistema.
     *
     * Antes de soltarla se COSECHAN sus nombres: persona, receptor y especie pasan a las
     * listas de sugerencias. Hasta ahora eso dependia de que el campo perdiera el foco, y un
     * campo del que se sale pulsando atras puede no perderlo nunca -- con lo que el nombre
     * quedaba en el registro pero no se ofrecia la vez siguiente. Un nombre que llego a un
     * dato de terreno se ofrece siempre.
     */
    fun close() {
        _state.value.open?.let { harvestNames(it) }
        flush()
        _state.value = _state.value.copy(open = null)
        refresh()
    }

    /** Mete en las listas de sugerencias todos los nombres que la entrada haya usado. */
    private fun harvestNames(e: FieldEntry) {
        e.person.takeIf { it.isNotBlank() }?.let { people?.remember(it) }
        e.species.takeIf { it.isNotBlank() }?.let { species?.remember(it) }
        e.gnss?.receiver?.takeIf { it.isNotBlank() }?.let { receivers?.remember(it) }
        e.measurements.forEach { m ->
            m.person.takeIf { it.isNotBlank() }?.let { people?.remember(it) }
            m.gnss?.receiver?.takeIf { it.isNotBlank() }?.let { receivers?.remember(it) }
        }
    }

    /**
     * Cambia la entrada abierta. El estado se actualiza siempre en el acto; el disco, segun
     * [immediate].
     *
     * `updated` se toca en cada cambio, y de el depende el orden de la lista: lo ultimo que se
     * toco, arriba. Lo que se busca al volver a abrir la libreta es casi siempre lo que se
     * estaba haciendo, no lo que se creo antes.
     *
     * POR QUE HAY DOS MODOS. Todo lo estructural --anadir una medicion, empezar o terminar un
     * cronometro, poner una coordenada-- se escribe en el acto, porque es justo lo que no se
     * puede repetir si Android mata la app. Lo que se TECLEA no: un `save` por pulsacion es un
     * fichero reescrito treinta veces por segundo en el hilo principal, y eso en un telefono
     * frio con la memoria llena es como se provoca un ANR escribiendo una nota. Se agrupa con
     * un retardo corto, y se fuerza el volcado al cerrar la entrada, al irse la app a segundo
     * plano y al destruirse el ViewModel -- los tres sitios por los que se puede salir.
     */
    fun update(immediate: Boolean = true, transform: (FieldEntry) -> FieldEntry) {
        val actual = _state.value.open ?: return
        val nueva = transform(actual).copy(updatedEpochMillis = System.currentTimeMillis())
        _state.value = _state.value.copy(open = nueva)
        pendiente = nueva
        guardadoJob?.cancel()
        if (immediate) {
            flush()
        } else {
            guardadoJob = viewModelScope.launch {
                kotlinx.coroutines.delay(SAVE_DEBOUNCE_MS)
                flush()
            }
        }
    }

    /** Escribe ya lo que estuviera pendiente. Es idempotente y barato si no hay nada. */
    fun flush() {
        guardadoJob?.cancel(); guardadoJob = null
        val s = store ?: return
        val e = pendiente ?: return
        pendiente = null
        s.save(e)
    }

    fun delete(id: String) {
        val s = store ?: return
        // Se descarta lo pendiente ANTES de borrar: un volcado posterior recrearia el fichero
        // que se acaba de eliminar, y la entrada volveria de entre los muertos en la lista.
        guardadoJob?.cancel(); guardadoJob = null; pendiente = null
        s.delete(id)
        _state.value = _state.value.copy(open = null, entries = s.list(),
                                         note = "Entry deleted")
    }

    // ------------------------------- personas y receptores -------------------------------

    private fun storeFor(which: NameList): NameStore? = when (which) {
        NameList.PEOPLE -> people
        NameList.RECEIVERS -> receivers
        NameList.SPECIES -> species
    }

    private fun labelFor(which: NameList): String = when (which) {
        NameList.PEOPLE -> "people"
        NameList.RECEIVERS -> "receivers"
        NameList.SPECIES -> "species"
    }

    /** Guarda el nombre en la lista que toque y lo deja como el mas reciente. */
    fun rememberName(which: NameList, name: String) {
        storeFor(which)?.remember(name)
        refreshNames()
    }

    fun removeName(which: NameList, name: String) {
        storeFor(which)?.remove(name)
        refreshNames()
        _state.value = _state.value.copy(
            note = "Removed “$name” from the list. Existing records keep it.")
    }

    fun clearNames(which: NameList) {
        storeFor(which)?.clear()
        refreshNames()
        _state.value = _state.value.copy(
            note = "Cleared the list of ${labelFor(which)}. Existing records keep their names.")
    }

    private fun refreshNames() {
        _state.value = _state.value.copy(
            people = people?.list() ?: emptyList(),
            receivers = receivers?.list() ?: emptyList(),
            species = species?.list() ?: emptyList())
    }

    // ------------------------------------ posicion ------------------------------------

    /**
     * Pide una posicion al receptor del telefono y la pone en la entrada abierta.
     *
     * Con plazo y con un boton de cancelar en la pantalla: bajo dosel o en un valle encajonado
     * el arreglo puede no llegar nunca, y una espera de la que no se puede salir obliga a
     * matar la app -- que aqui significa perder lo que se estuviera anotando.
     */
    fun requestPhonePosition() {
        val loc = location
        if (loc == null) {
            _state.value = _state.value.copy(error = "No location source available")
            return
        }
        requestLocationPermission?.invoke()
        _state.value = _state.value.copy(positionRequest = PositionRequest(waiting = true))
        posJob?.cancel()
        posJob = viewModelScope.launch {
            val fix = runCatching {
                withContext(Dispatchers.IO) { loc.freshFix(PHONE_FIX_TIMEOUT_MS) }
            }.getOrNull()
            if (fix == null) {
                _state.value = _state.value.copy(
                    positionRequest = null,
                    error = "No position arrived. Try again in the open, or pick a saved point.")
                return@launch
            }
            update { it.copy(position = FieldPosition(
                latitude = fix.latitude,
                longitude = fix.longitude,
                altitudeMetres = fix.altitudeMetres,
                accuracyMetres = fix.accuracyMetres,
                source = PositionSource.PHONE,
                // El instante del ARREGLO, no el de ahora: es cuando se midio.
                atEpochMillis = System.currentTimeMillis() - fix.ageSeconds * 1000,
            )) }
            _state.value = _state.value.copy(positionRequest = null)
        }
    }

    fun cancelPositionRequest() {
        posJob?.cancel(); posJob = null
        _state.value = _state.value.copy(positionRequest = null)
    }

    fun usePoint(solution: SavedPoint.Solution) {
        update { it.copy(position = solution.toFieldPosition()) }
    }

    fun clearPosition() { update { it.copy(position = null) } }

    // ---------------------------------- nota general ----------------------------------

    fun addNoteItem(kind: NoteItemKind, text: String = "", file: String? = null,
                    durationMillis: Long? = null) {
        update { e ->
            e.copy(items = e.items + NoteItem(
                kind = kind, atEpochMillis = System.currentTimeMillis(),
                text = text, file = file, durationMillis = durationMillis))
        }
    }

    fun setNoteItemText(index: Int, text: String) {
        update(immediate = false) { e ->
            if (index !in e.items.indices) e
            else e.copy(items = e.items.toMutableList().also { it[index] = it[index].copy(text = text) })
        }
    }

    fun setNoteItemTime(index: Int, atMillis: Long) {
        update { e ->
            if (index !in e.items.indices) e
            else e.copy(items = e.items.toMutableList()
                .also { it[index] = it[index].copy(atEpochMillis = atMillis) })
        }
    }

    fun removeNoteItem(index: Int) {
        update { e ->
            if (index !in e.items.indices) e
            else {
                val fuera = e.items[index]
                borrarMedioSiSobra(fuera.file, e.id)
                e.copy(items = e.items.filterIndexed { i, _ -> i != index })
            }
        }
    }

    // ------------------------------------- balizas -------------------------------------

    fun addStakeMeasurement(person: String) {
        update { e ->
            e.copy(measurements = e.measurements + StakeMeasurement(
                atEpochMillis = System.currentTimeMillis(), person = person))
        }
    }

    /**
     * Las mediciones se identifican por su instante y no por su indice.
     *
     * La lista se REORDENA al mostrarla --cronologicamente-- asi que el indice que ve la
     * pantalla no es el del modelo. Corregir una hora a mano mueve la fila de sitio, y un
     * indice capturado antes editaria otra medicion sin que nada lo delate.
     */
    fun updateStakeMeasurement(atEpochMillis: Long, immediate: Boolean = true,
                               transform: (StakeMeasurement) -> StakeMeasurement) {
        update(immediate) { e ->
            e.copy(measurements = e.measurements.map {
                if (it.atEpochMillis == atEpochMillis) transform(it) else it
            })
        }
    }

    fun removeStakeMeasurement(atEpochMillis: Long) {
        update { e ->
            val fuera = e.measurements.firstOrNull { it.atEpochMillis == atEpochMillis }
            fuera?.photos?.forEach { borrarMedioSiSobra(it, e.id) }
            e.copy(measurements = e.measurements.filterNot { it.atEpochMillis == atEpochMillis })
        }
    }

    // ------------------------------------- medidas -------------------------------------

    fun addPhotos(files: List<String>) { update { it.copy(photos = it.photos + files) } }

    fun removePhoto(file: String) {
        update { e ->
            borrarMedioSiSobra(file, e.id)
            e.copy(photos = e.photos - file)
        }
    }

    /**
     * Borra un fichero de medios si ninguna OTRA entrada lo nombra.
     *
     * Se comprueba contra el disco y no contra la entrada abierta: la copia en memoria no
     * sabe nada de las demas entradas, y una foto compartida que se borrase dejaria a la otra
     * entrada apuntando a un hueco.
     */
    private fun borrarMedioSiSobra(file: String?, exceptEntryId: String) {
        val s = store ?: return
        val f = file ?: return
        val enUso = s.list().filter { it.id != exceptEntryId }.flatMap { it.mediaFiles() }.toSet()
        if (f !in enUso) runCatching { s.media(f).delete() }
    }

    /** Un fichero nuevo donde la camara o el microfono puedan escribir. */
    fun newMediaFile(extension: String): File? = store?.newMediaFile(extension)

    fun mediaFile(name: String): File? = store?.media(name)

    // ---------------------------------- medicion GNSS ----------------------------------

    /** Las duraciones que ofrece el desplegable, en minutos. */
    val plannedDurations: List<Int> =
        listOf(5, 10, 15, 20, 30, 45, 60, 75, 90, 120, 150, 180)

    fun formatPlanned(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    // ------------------------------------- el cielo -------------------------------------

    private var cieloJob: Job? = null

    /**
     * Empieza a mirar el cielo. Lo llama el panel al aparecer, y lo corta al desaparecer.
     *
     * Atado a la PANTALLA y no a la medicion a proposito: mantener el GPS del telefono
     * encendido durante una ocupacion de tres horas vaciaria la bateria del unico aparato que
     * ademas lleva el cronometro. La cuenta de satelites se mira al empezar y cuando uno se
     * pregunta si alargar, que es cuando el panel esta delante.
     */
    fun watchSky() {
        val fuente = sky ?: return
        if (cieloJob?.isActive == true) return
        requestLocationPermission?.invoke()
        cieloJob = viewModelScope.launch {
            var llego = false
            runCatching {
                fuente.sky().collect { v -> llego = true; _state.value = _state.value.copy(sky = v) }
            }
            // Un flujo que termina sin entregar nada significa que no hay GPS: sin permiso,
            // sin proveedor o apagado. Se deja en un cielo vacio, que la pantalla distingue
            // de "todavia no ha llegado nada".
            if (!llego) _state.value = _state.value.copy(sky = SkyView.EMPTY)
        }
    }

    fun stopWatchingSky() {
        cieloJob?.cancel(); cieloJob = null
        _state.value = _state.value.copy(sky = null)
    }

    // ------------------------- lo que falta en una medicion GNSS -------------------------

    /**
     * Que le falta a la medicion GNSS de la entrada abierta para servir de algo.
     *
     * Se comprueba al TERMINAR y no al empezar: al empezar, no tener todavia la altura de
     * antena es lo normal --se mide con el tripode ya puesto-- mientras que al terminar es una
     * perdida. Y nunca IMPIDE terminar: la hora de termino es el dato que no se puede
     * recuperar, asi que se registra primero y el aviso viene despues.
     */
    fun missingInGnss(atEpochMillis: Long? = null): List<String> {
        val e = _state.value.open ?: return emptyList()
        val g = if (atEpochMillis == null) e.gnss
                else e.measurements.firstOrNull { it.atEpochMillis == atEpochMillis }?.gnss
        g ?: return emptyList()
        if (g.startEpochMillis == null) return emptyList()
        return g.missingForUse(hasPosition = e.position != null)
    }

    // ------------------------------------ exportacion ------------------------------------

    /**
     * Escribe el zip en el destino que el usuario eligio.
     *
     * Se exporta lo que se esta VIENDO --la campana archivada abierta, o la campana en curso
     * mas lo que no tenga campana-- y no siempre todo. Es lo mismo que la lista muestra, asi
     * que no hay forma de creer que se exporto una cosa y haber exportado otra.
     */
    fun export(out: java.io.OutputStream, media: FieldbookExport.Media, todo: Boolean) {
        val s = store ?: return
        _state.value = _state.value.copy(exporting = true, error = null, note = null)
        viewModelScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val todas = s.list()
                    val cs = campaigns?.list() ?: emptyList()
                    val seleccion = if (todo) todas
                                    else enVista(todas, _state.value.viewingCampaign,
                                                 _state.value.activeCampaign)
                    out.use { FieldbookExport.writeZip(it, seleccion, media, cs) }
                }
            }
            _state.value = r.fold(
                onSuccess = { _state.value.copy(exporting = false, note = "Exported ${it.describe()}") },
                onFailure = { _state.value.copy(exporting = false,
                                                error = "Export failed: ${it.message ?: "unknown"}") })
        }
    }

    fun exportName(todo: Boolean): String =
        FieldbookExport.suggestedName(
            if (todo) null else _state.value.viewingCampaign ?: _state.value.activeCampaign)

    companion object {
        /** Plazo del arreglo del telefono. Al aire libre llega en 2-15 s; bajo dosel, nunca. */
        const val PHONE_FIX_TIMEOUT_MS = 60_000L

        /**
         * Cuanto se agrupan las pulsaciones antes de escribir a disco.
         *
         * Corto a proposito: es el tiempo que se perderia si el sistema matara el proceso
         * justo despues de teclear. Medio segundo de escritura es una palabra a medias, no
         * una nota.
         */
        const val SAVE_DEBOUNCE_MS = 500L
    }

    override fun onCleared() {
        posJob?.cancel()
        cieloJob?.cancel()
        // El ultimo sitio por el que se puede salir sin pasar por close(). Sincrono a
        // proposito: una corrutina lanzada aqui no tiene por que llegar a correr.
        flush()
        super.onCleared()
    }
}
