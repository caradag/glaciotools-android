package cl.umag.glaciertemp.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.umag.glaciertemp.core.gnss.AlmanacFreshness
import cl.umag.glaciertemp.core.gnss.Constellation
import cl.umag.glaciertemp.core.gnss.Freshness
import cl.umag.glaciertemp.core.gnss.Tle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AlmanacUiState(
    val tles: List<Tle> = emptyList(),
    val downloadedAtMillis: Long? = null,
    val freshness: Freshness = Freshness.MISSING,
    val busy: Boolean = false,
    /** Que paso en el ultimo intento. Null mientras no se haya intentado nada. */
    val note: String? = null,
    val counts: Map<Constellation, Int> = emptyMap(),
)

/**
 * El almanaque: cargarlo al arrancar y mantenerlo al dia sin que nadie tenga que acordarse.
 *
 * LA REGLA DE ARRANQUE. Al abrir la app se mira lo guardado; si esta caducado Y hay red, se
 * baja solo. Nada mas. En particular NO se descarga si lo guardado sigue fresco, aunque haya
 * wifi: serian datos y bateria para no cambiar ni un pixel de la grafica, porque con 2 grados
 * de tolerancia un juego de elementos vale meses.
 *
 * Y NO SE PIDE PERMISO NI SE PREGUNTA. La descarga son 24 KB y ocurre una vez cada varias
 * semanas. Un cuadro de dialogo preguntando por eso, en una app que se usa con guantes,
 * cuesta mas que los datos que ahorra.
 */
class AlmanacViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AlmanacStore(File(app.filesDir, "almanac"))
    private val _state = MutableStateFlow(AlmanacUiState())
    val state: StateFlow<AlmanacUiState> = _state

    init { cargarYPonerAlDia() }

    /** Lo que se hace al arrancar: cargar, y bajar solo si hace falta y se puede. */
    fun cargarYPonerAlDia() {
        viewModelScope.launch {
            val cargado = withContext(Dispatchers.IO) { store.load() }
            publicar(cargado.tles, cargado.downloadedAtMillis, null)

            val ahora = System.currentTimeMillis()
            if (!AlmanacFreshness.shouldDownload(cargado.downloadedAtMillis, ahora)) return@launch
            if (!store.online(getApplication())) {
                // Se dice, y no se calla: quien esta a punto de salir a terreno con un
                // almanaque viejo tiene derecho a enterarse mientras todavia hay wifi.
                if (cargado.downloadedAtMillis == null)
                    publicar(cargado.tles, null, "No almanac yet, and no network to fetch one.")
                return@launch
            }
            descargar(automatica = true)
        }
    }

    /** El boton de actualizar a mano. Intenta siempre, aunque este fresco. */
    fun refreshNow() = descargar(automatica = false)

    private fun descargar(automatica: Boolean) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, note = null)
        viewModelScope.launch {
            val cuando = withContext(Dispatchers.IO) { store.download() }
            if (cuando == null) {
                // Lo guardado NO se toca. Un almanaque de dos meses sirve; ninguno, no.
                _state.value = _state.value.copy(
                    busy = false,
                    note = if (automatica) "Could not update the almanac. Keeping the stored one."
                           else "Download failed. The stored almanac is unchanged.")
                return@launch
            }
            val cargado = withContext(Dispatchers.IO) { store.load() }
            publicar(cargado.tles, cargado.downloadedAtMillis,
                     "Almanac updated: ${cargado.tles.size} satellites.")
        }
    }

    private fun publicar(tles: List<Tle>, cuando: Long?, nota: String?) {
        _state.value = AlmanacUiState(
            tles = tles,
            downloadedAtMillis = cuando,
            freshness = AlmanacFreshness.of(cuando, System.currentTimeMillis()),
            busy = false,
            note = nota,
            counts = Constellation.entries.associateWith { c -> tles.count { it.constellation == c } },
        )
    }

    fun clearNote() { _state.value = _state.value.copy(note = null) }
}
