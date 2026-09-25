package cl.umag.glaciertemp.core.gnss

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Un satelite tal como lo ve el receptor AHORA. */
data class ObservedSat(
    val constellation: Constellation,
    val svid: Int,
    val azimuthDeg: Double,
    val elevationDeg: Double,
)

/** Un satelite emparejado: donde lo pone el modelo y donde lo ve el receptor. */
data class SatComparison(
    val constellation: Constellation,
    val svid: Int,
    val predictedAz: Double, val predictedEl: Double,
    val observedAz: Double, val observedEl: Double,
    val separationDeg: Double,
)

/** El resultado de contrastar el modelo con el cielo. */
data class CheckResult(
    val matched: Int,
    val maxErrorDeg: Double?,
    /** Uno por satelite emparejado, el peor primero. Es lo que permite ver si el desacuerdo
     *  es de TODOS --marco de coordenadas o reloj-- o de uno solo --identidad--. */
    val details: List<SatComparison> = emptyList(),
)

/**
 * Cuanto se equivoca el modelo, medido contra el cielo de verdad.
 *
 * POR QUE ESTO EXISTE. "Cuanto dura un almanaque" no se puede saber por calendario: depende
 * de cada satelite, de cuando se subieron sus elementos y de si alguno se ha maniobrado. En
 * vez de estimarlo, se MIDE: el receptor entrega el azimut y la elevacion reales de lo que
 * esta rastreando, el modelo dice donde deberian estar, y la diferencia es el error actual
 * en grados. Convierte una incognita en un numero en pantalla.
 *
 * Se emparejan por IDENTIDAD y no por cercania. Buscar el predicho mas proximo a cada
 * observado daria siempre un numero pequeno --con diez satelites repartidos por el cielo casi
 * siempre hay alguno cerca-- y eso es justo lo contrario de lo que se quiere: una
 * comprobacion que no puede fallar no comprueba nada.
 */
object ModelCheck {

    /**
     * Separacion angular real entre dos direcciones del cielo.
     *
     * No la diferencia de azimut y elevacion por separado: cerca del cenit dos satelites con
     * 90 grados de azimut entre ellos pueden estar a un grado de distancia. La ley de los
     * cosenos esferica es la unica que da el angulo que uno vería.
     */
    fun separationDeg(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = az1 * PI / 180.0; val e1 = el1 * PI / 180.0
        val a2 = az2 * PI / 180.0; val e2 = el2 * PI / 180.0
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /**
     * El peor desacuerdo entre lo calculado y lo observado, ahora mismo.
     *
     * Solo cuentan los satelites que se pueden emparejar por identidad (GPS y BeiDou, ver
     * [Tle.svid]) y que ambos ven sobre el horizonte. Si no hay ninguno, no se inventa un
     * numero: se devuelve null y la pantalla lo dice.
     */
    fun compare(tles: List<Tle>, observed: List<ObservedSat>, atMillis: Long,
                latDeg: Double, lonDeg: Double): CheckResult {
        val porId = tles.mapNotNull { t -> t.svid?.let { (t.constellation to it) to t } }.toMap()
        val filas = mutableListOf<SatComparison>()
        for (o in observed) {
            if (o.elevationDeg <= 0.0) continue
            val t = porId[o.constellation to o.svid] ?: continue
            val p = SkyModel.skyPos(t, atMillis, latDeg, lonDeg)
            // Si el modelo lo situa bajo el horizonte y el receptor lo ve, eso TAMBIEN es
            // error, y del grande: se mide igual.
            filas += SatComparison(
                o.constellation, o.svid,
                p.azimuthDeg, p.elevationDeg, o.azimuthDeg, o.elevationDeg,
                separationDeg(p.azimuthDeg, p.elevationDeg, o.azimuthDeg, o.elevationDeg))
        }
        filas.sortByDescending { it.separationDeg }
        return CheckResult(filas.size, filas.firstOrNull()?.separationDeg, filas)
    }
}
