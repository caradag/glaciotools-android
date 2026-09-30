package cl.umag.glaciertemp.core.geo

import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import cl.umag.glaciertemp.core.geo.geoid.GeoidProvider

/** Que altura se informa: la elipsoidal o la referida a un geoide. */
sealed interface HeightReference {
    data object Ellipsoidal : HeightReference
    data class Orthometric(val model: GeoidModel) : HeightReference
}

/**
 * Una altura lista para mostrar o exportar, con todo lo que hace falta para no malinterpretarla.
 *
 * [value] es la altura en la referencia pedida, o la elipsoidal si hubo que caer a ella (y
 * entonces [fallbackReason] dice por que). [ellipsoidal] va siempre: es lo que se guarda.
 */
data class ReportedHeight(
    val value: Double?,
    val ellipsoidal: Double?,
    /** El geoide aplicado, o null si [value] es elipsoidal. */
    val model: GeoidModel?,
    val undulation: Double?,
    /** "m above the WGS84 ellipsoid" o "m above the EGM2008 geoid". */
    val label: String,
    val fallbackReason: String?,
)

/**
 * EL UNICO SITIO donde una altura elipsoidal pasa a geoidal.
 *
 * Toda pantalla y toda exportacion pasa por aqui y ninguna calcula h - N por su cuenta: con
 * varios sitios haciendolo, basta que uno se olvide del signo o de la etiqueta para que una
 * misma altura se lea distinta en dos pantallas.
 *
 * SIN SUSTITUCIONES EN SILENCIO. Si el geoide elegido no tiene datos para ese punto, se
 * informa la elipsoidal CON SU ETIQUETA y se dice por que; nunca otro geoide ni una altura
 * rotulada como geoidal que no lo es.
 */
object HeightReport {
    const val ELLIPSOID_LABEL = "m above the WGS84 ellipsoid"

    fun geoidLabel(m: GeoidModel) = "m above the ${m.title} geoid"

    fun report(ellipsoidal: Double?, latitude: Double, longitude: Double,
               reference: HeightReference, geoids: GeoidProvider): ReportedHeight {
        if (ellipsoidal == null)
            return ReportedHeight(null, null, null, null, ELLIPSOID_LABEL, "no altitude in this fix")
        return when (reference) {
            HeightReference.Ellipsoidal ->
                ReportedHeight(ellipsoidal, ellipsoidal, null, null, ELLIPSOID_LABEL, null)
            is HeightReference.Orthometric -> {
                val n = geoids.undulation(reference.model, latitude, longitude)
                if (n == null) ReportedHeight(ellipsoidal, ellipsoidal, null, null, ELLIPSOID_LABEL,
                                              "${reference.model.title} not available here")
                else ReportedHeight(ellipsoidal - n, ellipsoidal, reference.model, n,
                                    geoidLabel(reference.model), null)
            }
        }
    }
}
