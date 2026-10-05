package cl.umag.glaciertemp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import cl.umag.glaciertemp.core.fieldbook.CampaignStore
import cl.umag.glaciertemp.core.fieldbook.JournalAudio
import cl.umag.glaciertemp.core.fieldbook.JournalDay
import cl.umag.glaciertemp.core.fieldbook.JournalDays
import cl.umag.glaciertemp.core.fieldbook.FieldPosition
import cl.umag.glaciertemp.core.fieldbook.PositionSource
import cl.umag.glaciertemp.core.fieldbook.JournalEntry
import cl.umag.glaciertemp.core.fieldbook.JournalReminder
import cl.umag.glaciertemp.core.fieldbook.JournalStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class JournalUiState(
    val campaignId: String? = null,
    val campaignName: String = "",
    val days: List<JournalDay> = emptyList(),
    /** Los dias plegados. Se guarda lo PLEGADO y no lo desplegado: un dia nuevo nace abierto. */
    val collapsed: Set<String> = emptySet(),
    /** La entrada que se esta editando, o null en la lista. */
    val open: JournalEntry? = null,
    /** El dia cuyo titulo se esta escribiendo. */
    val editingTitleFor: String? = null,
    /** La fecha que el recordatorio senala, o null si no hay nada que recordar. */
    val reminderDay: String? = null,
    val note: String? = null,
    /** Cuando se esta esperando un arreglo del GPS para la entrada abierta. */
    val waitingForFix: Boolean = false,
)

/**
 * El diario de campana.
 *
 * VIVE APARTE DE LA LIBRETA, con su propio almacen y su propio ViewModel, y no como un tipo
 * mas de anotacion. Una anotacion de libreta documenta UNA COSA y acaba en una tabla del
 * CSV; una entrada de diario documenta UN RATO y no tiene campos que rellenar. Meterla entre
 * los tipos la habria colado en los filtros rapidos, en el dialogo de New Entry y en las
 * tablas exportadas, tres sitios donde no significa nada.
 *
 * SABE SOLO CUAL ES LA CAMPANA ABIERTA, leyendo el almacen de campanas. Es lo que permite
 * que el recordatorio funcione al arrancar la app sin que nadie haya abierto la libreta.
 */
class JournalViewModel : ViewModel() {

    var store: JournalStore? = null
    var campaigns: CampaignStore? = null

    private val _state = MutableStateFlow(JournalUiState())
    val state: StateFlow<JournalUiState> = _state

    /** El dia cuyo aviso se descarto. En memoria: vuelve a verse al reabrir la app, que es
     *  lo que se quiere -- descartar no es "no me lo recuerdes nunca mas", es "ahora no". */
    private var descartado: String? = null

    fun refresh() {
        val st = store ?: return
        val activa = campaigns?.active()
        if (activa == null) {
            _state.value = JournalUiState()
            return
        }
        // BARRIDO DE ENTRADAS VACIAS. close() borra la que se abrio y no se escribio, pero
        // un cierre forzado --o Android matando la app por memoria, que en terreno pasa-- se
        // salta ese paso y deja una entrada en blanco que aparece como un dia con contenido.
        // La que esta ABIERTA ahora mismo se respeta: acaba de nacer y todavia no se ha
        // escrito en ella.
        val abierta = _state.value.open?.id
        st.list(activa.id).forEach { e ->
            if (e.isEmpty() && e.id != abierta) st.delete(e.id)
        }

        val entradas = st.list(activa.id)
        // Con los HUECOS: los dias saltados entre dos dias escritos aparecen vacios y en
        // rojo, para que se vea que falta algo y se pueda escribir ahi mismo.
        val dias = JournalDays.withGaps(JournalDays.group(entradas, st.dayTitles(activa.id)))

        // Al abrir, solo el dia mas reciente queda desplegado. Con una campana de seis
        // semanas, desplegarlo todo obliga a recorrer un muro de texto para llegar a lo de
        // hoy, que es justo lo que se viene a escribir.
        val plegadosPrevios = _state.value.collapsed
        val conocidos = _state.value.days.map { it.key }.toSet()
        val claves = dias.map { it.key }.toSet()
        var plegados = if (conocidos.isEmpty())
            dias.drop(1).map { it.key }.toSet()
        else
            plegadosPrevios.intersect(claves)
        // NUNCA TODO PLEGADO. Al cambiarle la fecha a la ultima entrada de un dia, ese dia
        // --el que estaba abierto-- desaparece, y si los demas venian plegados el diario se
        // queda a la vista como una lista de titulos cerrados: parece que la entrada se
        // perdio. Se abre el mas reciente, que es donde acaba de caer.
        if (dias.isNotEmpty() && plegados.size == dias.size)
            plegados = plegados - dias.first().key

        _state.value = _state.value.copy(
            campaignId = activa.id,
            campaignName = activa.displayName(),
            days = dias,
            collapsed = plegados,
            reminderDay = JournalReminder.missingDay(
                dias.filter { !it.missing }.map { it.key }.toSet(),
                System.currentTimeMillis(), descartado),
        )
    }

    fun toggleDay(key: String) {
        val c = _state.value.collapsed
        _state.value = _state.value.copy(
            collapsed = if (key in c) c - key else c + key)
    }

    fun setDayTitle(key: String, title: String) {
        val st = store ?: return
        val id = _state.value.campaignId ?: return
        st.setDayTitle(id, key, title.trim())
        refresh()
    }

    /**
     * El titulo del dia escrito desde el editor de su primera entrada. Se guarda en cada
     * pulsacion pero SIN refrescar todo el diario: refresh() relee todas las entradas y
     * barre las vacias, y hacerlo por cada letra seria trabajo inutil mientras se teclea.
     */
    fun setDayTitleFromEditor(key: String, title: String) {
        val st = store ?: return
        val id = _state.value.campaignId ?: return
        st.setDayTitle(id, key, title.trim())
        _state.value = _state.value.copy(
            days = _state.value.days.map { if (it.key == key) it.copy(title = title.trim()) else it })
    }

    /** Si la entrada es la unica de su dia: entonces el editor ofrece titular el dia. */
    fun isFirstOfItsDay(e: JournalEntry): Boolean {
        val clave = JournalDays.dayKey(e.epochMillis)
        return _state.value.days.firstOrNull { it.key == clave }
            ?.entries?.none { it.id != e.id } ?: true
    }

    fun dayTitle(key: String): String =
        _state.value.days.firstOrNull { it.key == key }?.title
            ?: store?.let { st -> _state.value.campaignId?.let { st.dayTitles(it)[key] } }.orEmpty()

    fun editTitleFor(key: String?) {
        _state.value = _state.value.copy(editingTitleFor = key)
    }

    // ------------------------------------ entradas ------------------------------------

    /**
     * Abre una entrada nueva.
     *
     * @param atMillis para que dia. Por defecto ahora; el recordatorio la crea con la fecha
     *        del dia que falta, para que aparezca donde el aviso dijo que faltaba.
     */
    /** De donde sale la posicion. Lo pone MainActivity, el mismo que usa la libreta. */
    var location: LocationSource? = null
    var requestLocationPermission: (() -> Unit)? = null
    private var posJob: kotlinx.coroutines.Job? = null

    /**
     * Pide una posicion y la pone en la entrada abierta.
     *
     * AUTOMATICA AL CREAR. Donde se escribio no se reconstruye despues, y el arreglo tarda
     * decenas de segundos: pedirlo al abrir aprovecha el rato que se pasa escribiendo. Si no
     * llega, no protesta: nadie lo pidio, y la pantalla ya dice "Not recorded".
     */
    fun requestPosition(automatica: Boolean = false) {
        val loc = location ?: return
        val abierta = _state.value.open?.id ?: return
        requestLocationPermission?.invoke()
        posJob?.cancel()
        _state.value = _state.value.copy(waitingForFix = true)
        posJob = viewModelScope.launch {
            val fix = runCatching {
                withContext(Dispatchers.IO) { loc.freshFix(FIX_TIMEOUT_MS) }
            }.getOrNull()
            _state.value = _state.value.copy(
                waitingForFix = false,
                note = if (fix == null && !automatica)
                           "No position arrived. Try again in the open." else _state.value.note)
            if (fix == null) return@launch
            // La entrada pudo cerrarse mientras se esperaba: no se escribe sobre otra.
            if (_state.value.open?.id != abierta) return@launch
            update(immediate = true) {
                it.copy(position = FieldPosition(
                    latitude = fix.latitude, longitude = fix.longitude,
                    altitudeMetres = fix.altitudeMetres,
                    accuracyMetres = fix.accuracyMetres,
                    source = PositionSource.PHONE,
                    atEpochMillis = System.currentTimeMillis() - fix.ageSeconds * 1000))
            }
        }
    }

    fun cancelPositionRequest() {
        posJob?.cancel(); posJob = null
        _state.value = _state.value.copy(waitingForFix = false)
    }

    fun create(atMillis: Long = System.currentTimeMillis()) {
        val st = store ?: return
        val id = _state.value.campaignId ?: return
        val e = JournalEntry(st.newId(), id, atMillis)
        st.save(e)
        _state.value = _state.value.copy(open = e)
        refresh()
        _state.value = _state.value.copy(open = e)
        requestPosition(automatica = true)
    }

    fun open(id: String) {
        val e = store?.load(id) ?: return
        _state.value = _state.value.copy(open = e)
    }

    /**
     * Cierra la entrada abierta, y la BORRA si quedo vacia del todo.
     *
     * Abrir el editor y salir sin escribir nada es lo mas facil de hacer sin querer, y una
     * entrada en blanco en medio del diario no dice nada salvo que alguien se equivoco de
     * boton. Se borra sola en vez de dejar basura que haya que limpiar a mano.
     */
    companion object {
        /** El mismo plazo que la libreta: al aire libre llega en segundos, bajo dosel nunca. */
        const val FIX_TIMEOUT_MS = 60_000L
    }

    fun close() {
        cancelPositionRequest()
        flush()
        _state.value.open?.let { if (it.isEmpty()) store?.delete(it.id) }
        _state.value = _state.value.copy(open = null)
        refresh()
    }

    fun delete(id: String) {
        guardado?.cancel(); guardado = null; pendiente = null
        store?.delete(id)
        _state.value = _state.value.copy(open = null, note = "Journal entry deleted")
        refresh()
    }

    // ----------------------------- guardado con retardo -----------------------------

    private var guardado: Job? = null
    private var pendiente: JournalEntry? = null

    /**
     * Cada cambio se persiste, agrupando medio segundo lo que se teclea.
     *
     * Sin boton de guardar, como el resto de la libreta y por lo mismo: en terreno la app se
     * cierra sola --frio, bateria, el sistema matando procesos-- y un borrador en memoria es
     * trabajo que no se recupera.
     */
    fun update(immediate: Boolean = false, f: (JournalEntry) -> JournalEntry) {
        val actual = _state.value.open ?: return
        val nueva = f(actual)
        _state.value = _state.value.copy(open = nueva)
        pendiente = nueva
        guardado?.cancel()
        if (immediate) { flush(); return }
        guardado = viewModelScope.launch {
            delay(500)
            flush()
        }
    }

    fun flush() {
        pendiente?.let { store?.save(it) }
        pendiente = null
    }

    // ------------------------------------- medios -------------------------------------

    fun newMediaFile(extension: String): File? = store?.newMediaFile(extension)
    fun media(name: String): File? = store?.media(name)

    fun addPhotos(files: List<String>) = update(immediate = true) { it.copy(photos = it.photos + files) }
    fun removePhoto(file: String) = update(immediate = true) { it.copy(photos = it.photos - file) }
    fun addAudio(file: String, durationMillis: Long?) =
        update(immediate = true) { it.copy(audio = it.audio + JournalAudio(file, durationMillis)) }
    fun removeAudio(file: String) =
        update(immediate = true) { it.copy(audio = it.audio.filterNot { a -> a.file == file }) }

    // ---------------------------------- recordatorio ----------------------------------

    fun dismissReminder() {
        descartado = _state.value.reminderDay
        _state.value = _state.value.copy(reminderDay = null)
    }

    /** Medianoche local de una clave de dia, para crear la entrada en el dia que falta. */
    fun middayOf(dayKey: String): Long {
        val p = dayKey.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return System.currentTimeMillis()
        return java.util.Calendar.getInstance().apply {
            set(p[0], p[1] - 1, p[2], 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun clearNote() { _state.value = _state.value.copy(note = null) }

    /** Un dia que hay que abrir porque se toco la notificacion del diario. */
    private val _openDay = MutableStateFlow<String?>(null)
    val openDay: StateFlow<String?> = _openDay

    fun requestOpenDay(dayKey: String) { _openDay.value = dayKey }
    fun consumeOpenDay() { _openDay.value = null }
}
