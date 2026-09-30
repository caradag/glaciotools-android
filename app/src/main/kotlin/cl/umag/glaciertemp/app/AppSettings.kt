package cl.umag.glaciertemp.app

import android.content.Context
import android.content.SharedPreferences
import cl.umag.glaciertemp.core.fieldbook.LengthUnit
import cl.umag.glaciertemp.core.geo.HeightReference
import cl.umag.glaciertemp.core.geo.HeightVerdict
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Las opciones generales de la app (menu Settings).
 *
 * UN SOLO SITIO y como StateFlow: las pantallas abiertas se enteran al instante de un cambio,
 * sin tener que releer las preferencias ni acordarse de refrescar. Se guarda en el mismo
 * fichero de preferencias "glaciotools" que el resto.
 */
object AppSettings {
    private const val PREFS = "glaciotools"
    private var prefs: SharedPreferences? = null

    /** Lo que se sabe de la altura que entrega este telefono (ver PhoneAltitude). */
    data class HeightCheckState(
        val verdict: HeightVerdict = HeightVerdict.UNVERIFIED,
        val deviceModel: String? = null,
        val checkedAtMillis: Long? = null,
        /** La separacion geoidal que usaba el chip al verificar, en m. */
        val chipSeparation: Double? = null,
    )

    private val _heightReference = MutableStateFlow<HeightReference>(HeightReference.Ellipsoidal)
    /**
     * En que referencia se MUESTRAN las alturas (y que columnas se anaden al exportar). Lo
     * guardado es siempre la altura elipsoidal: cambiar esto no reescribe ningun dato.
     */
    val heightReference: StateFlow<HeightReference> = _heightReference.asStateFlow()

    /** El geoide elegido, recordado aunque la referencia sea elipsoidal. */
    private val _geoidModel = MutableStateFlow(GeoidModel.EGM2008)
    val geoidModel: StateFlow<GeoidModel> = _geoidModel.asStateFlow()

    fun setHeightReference(geoidal: Boolean, model: GeoidModel = _geoidModel.value) {
        _geoidModel.value = model
        _heightReference.value = if (geoidal) HeightReference.Orthometric(model) else HeightReference.Ellipsoidal
        prefs?.edit()?.putString("height_reference", if (geoidal) "GEOID" else "ELLIPSOIDAL")
            ?.putString("geoid_model", model.id)?.apply()
    }

    private val _gaugingUnit = MutableStateFlow(LengthUnit.METRE)
    /** Unidad de entrada de las longitudes del aforo; lo guardado sigue en metros. */
    val gaugingLengthUnit: StateFlow<LengthUnit> = _gaugingUnit.asStateFlow()

    fun setGaugingLengthUnit(u: LengthUnit) {
        _gaugingUnit.value = u
        prefs?.edit()?.putString("gauging_length_unit", u.name)?.apply()
    }

    private val _heightCheck = MutableStateFlow(HeightCheckState())
    val heightCheck: StateFlow<HeightCheckState> = _heightCheck.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // El veredicto es de UN telefono: si las preferencias llegan a otro (copia de
        // seguridad restaurada), no vale y se vuelve a verificar.
        val modelo = p.getString("height_verdict_model", null)
        val esteTelefono = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
        if (modelo != null && modelo != esteTelefono) {
            p.edit().remove("height_verdict").remove("height_verdict_model")
                .remove("height_verdict_at").remove("height_verdict_sep").apply()
        }
        _gaugingUnit.value = LengthUnit.fromName(p.getString("gauging_length_unit", null))
        _geoidModel.value = GeoidModel.fromId(p.getString("geoid_model", null)) ?: GeoidModel.EGM2008
        _heightReference.value = if (p.getString("height_reference", null) == "GEOID")
            HeightReference.Orthometric(_geoidModel.value) else HeightReference.Ellipsoidal
        _heightCheck.value = HeightCheckState(
            verdict = runCatching { HeightVerdict.valueOf(p.getString("height_verdict", "")!!) }
                .getOrDefault(HeightVerdict.UNVERIFIED),
            deviceModel = p.getString("height_verdict_model", null),
            checkedAtMillis = p.getLong("height_verdict_at", 0L).takeIf { it > 0 },
            chipSeparation = p.getString("height_verdict_sep", null)?.toDoubleOrNull(),
        )
    }

    fun setHeightCheck(s: HeightCheckState) {
        _heightCheck.value = s
        prefs?.edit()?.apply {
            putString("height_verdict", s.verdict.name)
            putString("height_verdict_model", s.deviceModel)
            putLong("height_verdict_at", s.checkedAtMillis ?: 0L)
            putString("height_verdict_sep", s.chipSeparation?.toString())
            apply()
        }
    }
}
