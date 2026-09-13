package cl.umag.glaciertemp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.umag.glaciertemp.core.geo.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Una sesion de promediado en marcha, o recien terminada. */
data class AveragingState(
    /** null mientras el punto no se ha guardado nunca. */
    val pointId: String? = null,
    val name: String = "",
    val running: Boolean = false,
    val samples: List<GpsSample> = emptyList(),
    /** Proyectadas en la zona fijada por la primera muestra. */
    val projected: List<Pair<Double, Double>> = emptyList(),
    val stats: GpsPointStats? = null,
    val outOfZone: Int = 0,
    /** Cuantas se anadieron en ESTA sesion y aun no estan en disco. */
    val unsaved: Int = 0,
    val waiting: Boolean = true,
)

data class GpsUiState(
    val points: List<GpsPointSummary> = emptyList(),
    val averaging: AveragingState? = null,
    val openPoint: OpenPoint? = null,
    val error: String? = null,
    val note: String? = null,
    val gpsUnavailable: Boolean = false,
)

/** Un punto guardado, ya cargado con sus muestras. */
data class OpenPoint(
    val summary: GpsPointSummary,
    val samples: List<GpsSample>,
    val projected: List<Pair<Double, Double>>,
    val stats: GpsPointStats?,
)

/**
 * Promediado de posiciones GPS.
 *
 * Va en su propio ViewModel y no en el de la placa porque no tienen nada que ver: esta
 * herramienta no habla con ningun aparato, y meterla en el otro habria significado un estado
 * compartido donde desconectar una placa afecta a una medida de terreno en marcha.
 */
class GpsViewModel : ViewModel() {

    var store: GpsPointStore? = null
    var location: LocationSource? = null

    /**
     * Lo llama la herramienta antes de medir, y lo rellena la actividad.
     *
     * El permiso se pide AQUI y no al arrancar la app: para la placa la posicion es un
     * extra que se puede denegar sin perder nada, pero sin ella esta herramienta no existe,
     * asi que el dialogo aparece cuando se entiende para que es.
     */
    var requestLocationPermission: (() -> Unit)? = null

    private val _state = MutableStateFlow(GpsUiState())
    val state: StateFlow<GpsUiState> = _state.asStateFlow()

    private var averager = GpsAverager()
    private var recogida: Job? = null
    /** Lo que aun no se ha escrito. Se vacia al guardar, no al pintar. */
    private val pendientes = ArrayList<GpsSample>()

    fun refresh() {
        val s = store ?: return
        _state.value = _state.value.copy(points = s.list())
    }

    fun dismissNote() { _state.value = _state.value.copy(note = null, error = null) }

    // ---------------------------------- promediar ----------------------------------

    /**
     * Empieza un punto nuevo, o sigue uno que ya existe.
     *
     * Al seguir, las muestras viejas se cargan ANTES de escuchar el receptor: la zona se
     * fija con la primera muestra de todas, que es la de la visita original, y asi las dos
     * visitas quedan en el mismo marco. Empezar de cero con las nuevas y anadir las viejas
     * despues podria elegir otra zona y partir la nube.
     */
    fun startAveraging(pointId: String? = null, name: String = "") {
        val loc = location
        if (loc == null) {
            _state.value = _state.value.copy(error = "No location source available")
            return
        }
        requestLocationPermission?.invoke()
        averager = GpsAverager()
        pendientes.clear()

        val previas = pointId?.let { store?.load(it)?.samples } ?: emptyList()
        averager.addAll(previas)

        _state.value = _state.value.copy(
            error = null, note = null, gpsUnavailable = false,
            averaging = AveragingState(
                pointId = pointId,
                name = name.ifBlank { pointId?.let { store?.load(it)?.header?.name } ?: "" },
                running = true,
                samples = averager.samples(),
                projected = averager.projected(),
                stats = averager.stats(),
                outOfZone = averager.outOfZone,
                // Solo se espera si no hay nada: al seguir un punto ya medido, las muestras
                // viejas ya estan en pantalla y decir "esperando la primera" seria mentir.
                waiting = previas.isEmpty(),
            ))

        recogida?.cancel()
        recogida = viewModelScope.launch {
            var llego = false
            runCatching {
                loc.samples(1000L).collect { m ->
                    llego = true
                    averager.add(m)
                    pendientes.add(m)
                    publicar()
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    error = e.message ?: "the receiver stopped",
                    averaging = _state.value.averaging?.copy(running = false))
            }
            // Un flujo que termina sin haber entregado nada significa que no hay GPS: sin
            // permiso, sin proveedor, o apagado. Decirlo es mejor que una pantalla vacia
            // que no se sabe si esta esperando o rota.
            if (!llego) {
                _state.value = _state.value.copy(
                    gpsUnavailable = true,
                    averaging = _state.value.averaging?.copy(running = false))
            }
        }
    }

    private fun publicar() {
        val a = _state.value.averaging ?: return
        _state.value = _state.value.copy(averaging = a.copy(
            samples = averager.samples(),
            projected = averager.projected(),
            stats = averager.stats(),
            outOfZone = averager.outOfZone,
            unsaved = pendientes.size,
            waiting = false,
        ))
    }

    /** Deja de escuchar el receptor, sin tirar lo acumulado. */
    fun pauseAveraging() {
        recogida?.cancel(); recogida = null
        _state.value = _state.value.copy(
            averaging = _state.value.averaging?.copy(running = false))
    }

    /**
     * Guarda lo acumulado en esta sesion. Se puede llamar varias veces: escribe solo lo
     * pendiente, asi que guardar a mitad y seguir midiendo no duplica nada.
     */
    fun saveAveraging(name: String) {
        val s = store ?: return
        val a = _state.value.averaging ?: return
        if (a.samples.isEmpty()) {
            _state.value = _state.value.copy(error = "No fixes yet: nothing to save")
            return
        }
        val id = a.pointId ?: s.create(name.ifBlank { "Point" })
        if (a.pointId != null && name.isNotBlank() && name != a.name) s.rename(id, name)
        s.append(id, pendientes.toList())
        pendientes.clear()
        _state.value = _state.value.copy(
            points = s.list(),
            note = "Saved: ${a.samples.size} fixes",
            averaging = a.copy(pointId = id, name = name.ifBlank { a.name }, unsaved = 0))
    }

    /** Cierra la sesion. Lo no guardado se pierde, y quien llama ya lo ha advertido. */
    fun closeAveraging() {
        recogida?.cancel(); recogida = null
        averager = GpsAverager()
        pendientes.clear()
        _state.value = _state.value.copy(averaging = null)
        refresh()
    }

    // ------------------------------------ un punto ------------------------------------

    fun open(id: String) {
        val s = store ?: return
        val p = s.load(id)
        if (p == null) {
            _state.value = _state.value.copy(error = "That point could not be read")
            return
        }
        val a = GpsAverager().apply { addAll(p.samples) }
        _state.value = _state.value.copy(openPoint = OpenPoint(
            summary = GpsPointSummary(
                id = p.header.id, name = p.header.name,
                createdEpochMillis = p.header.createdEpochMillis,
                samples = p.samples.size,
                lastEpochMillis = p.samples.maxOfOrNull { it.epochMillis }
                    ?: p.header.createdEpochMillis),
            samples = p.samples,
            projected = a.projected(),
            stats = a.stats(),
        ))
    }

    fun closePoint() { _state.value = _state.value.copy(openPoint = null) }

    fun rename(id: String, name: String) {
        store?.rename(id, name)
        refresh()
        if (_state.value.openPoint?.summary?.id == id) open(id)
    }

    fun delete(id: String) {
        store?.delete(id)
        _state.value = _state.value.copy(openPoint = null)
        refresh()
    }

    // ------------------------------------ exportar ------------------------------------

    enum class Scope { AVERAGE, ALL_FIXES }
    enum class Format { CSV, GPX }

    fun exportName(p: OpenPoint, scope: Scope, format: Format): String {
        val limpio = p.summary.name.ifBlank { "point" }
            .replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "point" }
        val t = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        val que = if (scope == Scope.AVERAGE) "avg" else "fixes"
        return "glaciotools_${limpio}_${que}_$t." + if (format == Format.CSV) "csv" else "gpx"
    }

    fun exportBytes(p: OpenPoint, scope: Scope, format: Format): ByteArray {
        val st = p.stats ?: return ByteArray(0)
        val name = p.summary.name.ifBlank { "Point" }
        val texto = when {
            scope == Scope.AVERAGE && format == Format.CSV -> GpsExport.csvAverage(name, st)
            scope == Scope.AVERAGE -> GpsExport.gpxAverage(name, st)
            format == Format.CSV -> GpsExport.csvSamples(name, st, p.samples)
            else -> GpsExport.gpxSamples(name, p.samples)
        }
        return texto.toByteArray()
    }

    fun onExported(ok: Boolean, name: String) {
        _state.value = if (ok) _state.value.copy(note = "Exported $name")
                       else _state.value.copy(error = "Could not write $name")
    }

    override fun onCleared() {
        recogida?.cancel()
        super.onCleared()
    }
}
