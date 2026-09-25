package cl.umag.glaciertemp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.umag.glaciertemp.core.fieldbook.CampaignStore
import cl.umag.glaciertemp.core.fieldbook.JournalAudio
import cl.umag.glaciertemp.core.fieldbook.JournalDay
import cl.umag.glaciertemp.core.fieldbook.JournalDays
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
        val entradas = st.list(activa.id)
        val dias = JournalDays.group(entradas, st.dayTitles(activa.id))

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
                claves, System.currentTimeMillis(), descartado),
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
    fun create(atMillis: Long = System.currentTimeMillis()) {
        val st = store ?: return
        val id = _state.value.campaignId ?: return
        val e = JournalEntry(st.newId(), id, atMillis)
        st.save(e)
        _state.value = _state.value.copy(open = e)
        refresh()
        _state.value = _state.value.copy(open = e)
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
    fun close() {
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
}
