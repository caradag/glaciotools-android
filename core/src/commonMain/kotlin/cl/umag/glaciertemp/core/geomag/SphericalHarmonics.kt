package cl.umag.glaciertemp.core.geomag

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coeficientes de Gauss de un campo interno, semi-normalizados de Schmidt, en nT.
 *
 * Indice plano n*(n+1)/2 + m, que guarda el triangulo 0 <= m <= n sin huecos.
 */
class ShCoefficients(val nmax: Int, val g: DoubleArray, val h: DoubleArray) {
    init {
        require(g.size == size(nmax) && h.size == size(nmax))
    }

    companion object {
        fun size(nmax: Int) = (nmax + 1) * (nmax + 2) / 2
        fun index(n: Int, m: Int) = n * (n + 1) / 2 + m
    }
}

/** Componentes del campo en el sistema geodesico local: norte, este, abajo (nT). */
data class FieldVector(val x: Double, val y: Double, val z: Double)

/**
 * Sintesis del campo magnetico interno por armonicos esfericos.
 *
 * Es el mismo calculo que hacen WMM y el programa oficial de IGRF: se pasa de coordenadas
 * geodesicas (las del GPS) a geocentricas, se suman los terminos con los polinomios de
 * Legendre asociados semi-normalizados de Schmidt y se vuelve a girar el vector a norte,
 * este y abajo LOCALES. El giro final no es un detalle: sin el, en latitudes medias la
 * componente vertical se contamina con la horizontal en una fraccion de grado, justo el
 * orden de magnitud que interesa en una declinacion.
 *
 * Todo en Double. AOSP hace esta cuenta en Float, y con siete cifras significativas el
 * termino de grado 12 ya se pierde en el redondeo de los primeros.
 */
object SphericalHarmonics {
    private const val A = 6378.137            // semieje mayor WGS84, km
    private const val B = 6356.7523142        // semieje menor WGS84, km
    private const val RE = 6371.2             // radio de referencia geomagnetico, km

    fun field(c: ShCoefficients, latitudeDeg: Double, longitudeDeg: Double,
              altitudeKm: Double): FieldVector {
        val phi = latitudeDeg * PI / 180
        val lam = longitudeDeg * PI / 180
        val a2 = A * A
        val b2 = B * B
        val sp = sin(phi)
        val cp = cos(phi)
        // geodesico -> geocentrico
        val rho = sqrt(a2 * cp * cp + b2 * sp * sp)
        val r = sqrt(altitudeKm * altitudeKm + 2 * altitudeKm * rho +
                     (a2 * a2 * cp * cp + b2 * b2 * sp * sp) / (rho * rho))
        val cd = (altitudeKm + rho) / r
        val sd = (a2 - b2) / rho * cp * sp / r
        val cosTheta = sp * cd - cp * sd          // colatitud geocentrica
        val sinTheta = cp * cd + sp * sd
        // Junto al polo geografico el termino en phi se divide por sin(theta); el limite
        // existe pero la division no. Con este minimo los valores de prueba oficiales de
        // WMM a 89 grados salen al 0,001 nT.
        val sinT = if (abs(sinTheta) > 1e-10) sinTheta else 1e-10

        val n = c.nmax
        val p = DoubleArray(ShCoefficients.size(n))
        val dp = DoubleArray(ShCoefficients.size(n))
        p[0] = 1.0
        for (nn in 1..n) {
            for (m in 0..nn) {
                val i = ShCoefficients.index(nn, m)
                if (nn == m) {
                    val k = if (m > 1) sqrt(1 - 1.0 / (2 * m)) else 1.0
                    val j = ShCoefficients.index(nn - 1, m - 1)
                    p[i] = k * sinTheta * p[j]
                    dp[i] = k * (sinTheta * dp[j] + cosTheta * p[j])
                } else {
                    val k1 = (2 * nn - 1) / sqrt((nn * nn - m * m).toDouble())
                    val k2 = sqrt(((nn - 1) * (nn - 1) - m * m).toDouble() / (nn * nn - m * m))
                    val j1 = ShCoefficients.index(nn - 1, m)
                    val tieneJ2 = nn - 2 >= m
                    val j2 = if (tieneJ2) ShCoefficients.index(nn - 2, m) else 0
                    p[i] = k1 * cosTheta * p[j1] - (if (tieneJ2) k2 * p[j2] else 0.0)
                    dp[i] = k1 * (cosTheta * dp[j1] - sinTheta * p[j1]) -
                            (if (tieneJ2) k2 * dp[j2] else 0.0)
                }
            }
        }

        var br = 0.0
        var bt = 0.0
        var bp = 0.0
        val ratio = RE / r
        var f = ratio * ratio * ratio            // (RE/r)^(n+2) para n = 1
        val cosM = DoubleArray(n + 1) { cos(it * lam) }
        val sinM = DoubleArray(n + 1) { sin(it * lam) }
        for (nn in 1..n) {
            for (m in 0..nn) {
                val i = ShCoefficients.index(nn, m)
                val g = c.g[i]
                val h = c.h[i]
                val t = g * cosM[m] + h * sinM[m]
                br += f * (nn + 1) * t * p[i]
                bt -= f * t * dp[i]
                bp += f * m * (g * sinM[m] - h * cosM[m]) * p[i] / sinT
            }
            f *= ratio
        }
        // geocentrico (bt hacia el sur) -> norte/este/abajo, y giro al sistema geodesico
        val xc = -bt
        val zc = -br
        return FieldVector(x = xc * cd + zc * sd, y = bp, z = -xc * sd + zc * cd)
    }
}
