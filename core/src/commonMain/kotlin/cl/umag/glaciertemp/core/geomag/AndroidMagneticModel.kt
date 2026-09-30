package cl.umag.glaciertemp.core.geomag

import kotlin.math.abs

/**
 * Que modelo magnetico trae el Android de ESTE telefono.
 *
 * SE MIDE, NO SE DEDUCE DE LA VERSION. AOSP trae WMM-2015 hasta Android 11 y WMM-2020 desde
 * Android 12 (sin actualizar en 2026), pero un fabricante puede haberlo cambiado. Se le pide
 * a GeomagneticField la declinacion en unos puntos y una fecha fijos, elegidos donde los dos
 * modelos se separan mas de dos grados, y se compara con lo que da cada uno segun el
 * calculo de AOSP reproducido en tools/geomag/gen_geomag.py. Si no coincide con ninguno, se
 * dice "desconocido" en vez de adivinar.
 */
object AndroidMagneticModel {
    /** Tolerancia: AOSP calcula en Float; entre sus redondeos y los nuestros hay milesimas. */
    private const val TOLERANCIA = 0.02

    data class Probe(val latitude: Double, val longitude: Double,
                     val wmm2015: Double, val wmm2020: Double)

    val probeEpochMillis: Long = ANDROID_PROBE_EPOCH_MILLIS

    val probes: List<Probe> by lazy {
        ANDROID_PROBES.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map {
            val p = it.split(' ').map(String::toDouble)
            Probe(p[0], p[1], p[2], p[3])
        }.toList()
    }

    /**
     * @param declination la declinacion que da la plataforma para (lat, lon, altura 0 m, instante)
     * @return "WMM2015", "WMM2020" o null si no es ninguno de los dos
     */
    fun identify(declination: (lat: Double, lon: Double, epochMillis: Long) -> Double): String? {
        val medidas = probes.map { declination(it.latitude, it.longitude, probeEpochMillis) }
        fun encaja(esperado: (Probe) -> Double) =
            probes.indices.all { abs(medidas[it] - esperado(probes[it])) <= TOLERANCIA }
        return when {
            encaja { it.wmm2015 } -> "WMM2015"
            encaja { it.wmm2020 } -> "WMM2020"
            else -> null
        }
    }

    /**
     * Intervalo de validez del modelo reconocido. Fuera de el, Android sigue contestando
     * con la extrapolacion lineal, y hay que decirlo al lado del numero.
     */
    fun validity(model: String?): ClosedFloatingPointRange<Double>? = when (model) {
        "WMM2015" -> 2015.0..2020.0
        "WMM2020" -> 2020.0..2025.0
        else -> null
    }
}
