package cl.umag.glaciertemp.app

import android.content.Context
import android.content.SharedPreferences
import cl.umag.glaciertemp.core.geo.HeightVerdict
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
