package cl.umag.glaciertemp.core.geomag

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Un modelo del campo magnetico principal con su intervalo de validez.
 *
 * FUERA DE VALIDEZ NO DEVUELVE NADA. Es exactamente el defecto que viene a corregir: el
 * GeomagneticField de Android sigue contestando con WMM-2015 en 2026 o en 1950, y una
 * declinacion inventada con buena cara es peor que un "no se sabe".
 */
interface MagneticModel {
    val name: String
    /** Anos decimales, ambos incluidos. */
    val validFrom: Double
    val validTo: Double
    fun coefficientsAt(year: Double): ShCoefficients?
    /** Variacion secular (nT/ano) en esa fecha: la derivada de los coeficientes. */
    fun secularVariationAt(year: Double): ShCoefficients?

    fun isValid(year: Double) = year >= validFrom && year <= validTo
}

/** Cuanto fiarse de la declinacion segun la componente horizontal (informe tecnico de WMM). */
enum class Reliability {
    NORMAL,
    /** H < 6000 nT: la brujula se vuelve perezosa y la declinacion cambia deprisa. */
    CAUTION,
    /** H < 2000 nT: zona de exclusion de WMM; la declinacion no es util. */
    UNRELIABLE,
}

data class MagneticResult(
    val model: String,
    val year: Double,
    val x: Double, val y: Double, val z: Double,
    val horizontal: Double,
    val total: Double,
    /** Grados, positiva al este: lo que hay que SUMAR a un rumbo magnetico. */
    val declination: Double,
    /** Grados, positiva hacia abajo. */
    val inclination: Double,
    /** Grados por ano. */
    val declinationRate: Double,
    val inclinationRate: Double,
    val reliability: Reliability,
)

/** Modelo con coeficientes a una epoca y variacion secular lineal (WMM). */
private class LinearModel(
    override val name: String,
    private val epoch: Double,
    private val nmax: Int,
    private val g: DoubleArray, private val h: DoubleArray,
    private val dg: DoubleArray, private val dh: DoubleArray,
    override val validFrom: Double,
    override val validTo: Double,
) : MagneticModel {
    override fun coefficientsAt(year: Double): ShCoefficients? {
        if (!isValid(year)) return null
        val dt = year - epoch
        return ShCoefficients(nmax, DoubleArray(g.size) { g[it] + dg[it] * dt },
                              DoubleArray(h.size) { h[it] + dh[it] * dt })
    }

    override fun secularVariationAt(year: Double): ShCoefficients? =
        if (isValid(year)) ShCoefficients(nmax, dg, dh) else null
}

/**
 * IGRF: coeficientes cada cinco anos, interpolados linealmente entre ellos, y la variacion
 * secular predicha para el ultimo tramo. Es lo que hace el programa oficial (igrf14.f).
 */
private class EpochModel(
    override val name: String,
    private val epochs: DoubleArray,
    private val nmax: Int,
    /** [epoca][indice] */
    private val g: Array<DoubleArray>, private val h: Array<DoubleArray>,
    private val sg: DoubleArray, private val sh: DoubleArray,
    override val validTo: Double,
) : MagneticModel {
    override val validFrom: Double = epochs.first()

    override fun coefficientsAt(year: Double): ShCoefficients? {
        if (!isValid(year)) return null
        val ultima = epochs.size - 1
        if (year >= epochs[ultima]) {
            val dt = year - epochs[ultima]
            return ShCoefficients(nmax, DoubleArray(sg.size) { g[ultima][it] + sg[it] * dt },
                                  DoubleArray(sh.size) { h[ultima][it] + sh[it] * dt })
        }
        var k = 0
        while (epochs[k + 1] <= year) k++
        val w = (year - epochs[k]) / (epochs[k + 1] - epochs[k])
        return ShCoefficients(nmax,
            DoubleArray(sg.size) { g[k][it] + (g[k + 1][it] - g[k][it]) * w },
            DoubleArray(sh.size) { h[k][it] + (h[k + 1][it] - h[k][it]) * w })
    }

    /** La pendiente del tramo quinquenal que contiene la fecha, como igrf14.f. */
    override fun secularVariationAt(year: Double): ShCoefficients? {
        if (!isValid(year)) return null
        val ultima = epochs.size - 1
        if (year >= epochs[ultima]) return ShCoefficients(nmax, sg, sh)
        var k = 0
        while (epochs[k + 1] <= year) k++
        val dt = epochs[k + 1] - epochs[k]
        return ShCoefficients(nmax, DoubleArray(sg.size) { (g[k + 1][it] - g[k][it]) / dt },
                              DoubleArray(sh.size) { (h[k + 1][it] - h[k][it]) / dt })
    }
}

object MagneticModels {

    /** WMM2025 (NOAA/BGS): el modelo oficial para navegacion, 2025.0-2030.0. */
    val WMM2025: MagneticModel by lazy { parseWmm("WMM2025", WMM2025_COEFFICIENTS, 12) }

    /** IGRF-14 (IAGA): 1900.0-2030.0. Hasta 1945 no es definitivo (IGRF, no DGRF). */
    val IGRF14: MagneticModel by lazy { parseIgrf("IGRF-14", IGRF14_COEFFICIENTS, 13) }

    val all: List<MagneticModel> get() = listOf(IGRF14, WMM2025)

    /**
     * El que se usa para "ahora" en la brujula: WMM2025 mientras sea valido, porque es el
     * de referencia para navegacion; IGRF-14 si la fecha cae fuera de 2025-2030 (un
     * telefono con el reloj mal). Despues de 2030 no vale ninguno y devuelve null.
     */
    fun forDate(year: Double): MagneticModel? = listOf(WMM2025, IGRF14).firstOrNull { it.isValid(year) }

    /**
     * El campo en un sitio y una fecha, o null fuera de la validez del modelo.
     *
     * Las tasas son ANALITICAS: el campo es lineal en los coeficientes, asi que sintetizar
     * los de variacion secular da dX/dt, dY/dt, dZ/dt, y de ahi dD/dt e dI/dt. Se probo
     * antes una diferencia finita de un ano y se apartaba 0,01 grados/ano cerca del polo,
     * donde la declinacion gira deprisa y no es lineal ni en medio ano.
     */
    fun evaluate(model: MagneticModel, latitudeDeg: Double, longitudeDeg: Double,
                 altitudeMetres: Double, year: Double): MagneticResult? {
        val c = model.coefficientsAt(year) ?: return null
        val km = altitudeMetres / 1000.0
        val v = SphericalHarmonics.field(c, latitudeDeg, longitudeDeg, km)
        val hz = sqrt(v.x * v.x + v.y * v.y)
        val s = SphericalHarmonics.field(model.secularVariationAt(year)!!, latitudeDeg, longitudeDeg, km)
        val f2 = hz * hz + v.z * v.z
        val dH = (v.x * s.x + v.y * s.y) / hz
        val rad = 180 / PI
        return MagneticResult(
            model = model.name, year = year,
            x = v.x, y = v.y, z = v.z, horizontal = hz, total = sqrt(hz * hz + v.z * v.z),
            declination = declinacion(v), inclination = inclinacion(v),
            declinationRate = (v.x * s.y - v.y * s.x) / (hz * hz) * rad,
            inclinationRate = (hz * s.z - v.z * dH) / f2 * rad,
            reliability = when {
                hz < 2000 -> Reliability.UNRELIABLE
                hz < 6000 -> Reliability.CAUTION
                else -> Reliability.NORMAL
            },
        )
    }

    private fun declinacion(v: FieldVector) = atan2(v.y, v.x) * 180 / PI
    private fun inclinacion(v: FieldVector) = atan2(v.z, sqrt(v.x * v.x + v.y * v.y)) * 180 / PI

    // ------------------------------------ lectura ------------------------------------

    private fun parseWmm(name: String, texto: String, nmax: Int): MagneticModel {
        val tam = ShCoefficients.size(nmax)
        val g = DoubleArray(tam); val h = DoubleArray(tam)
        val dg = DoubleArray(tam); val dh = DoubleArray(tam)
        var epoch = Double.NaN
        for (l in texto.lineSequence()) {
            val p = l.trim().split(' ')
            if (p.size < 2) continue
            if (p[0] == "epoch") { epoch = p[1].toDouble(); continue }
            val i = ShCoefficients.index(p[0].toInt(), p[1].toInt())
            g[i] = p[2].toDouble(); h[i] = p[3].toDouble()
            dg[i] = p[4].toDouble(); dh[i] = p[5].toDouble()
        }
        require(!epoch.isNaN())
        return LinearModel(name, epoch, nmax, g, h, dg, dh, epoch, epoch + 5.0)
    }

    private fun parseIgrf(name: String, texto: String, nmax: Int): MagneticModel {
        val lineas = texto.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val epochs = lineas.first().split(' ').drop(1).map { it.toDouble() }.toDoubleArray()
        val tam = ShCoefficients.size(nmax)
        val g = Array(epochs.size) { DoubleArray(tam) }
        val h = Array(epochs.size) { DoubleArray(tam) }
        val sg = DoubleArray(tam); val sh = DoubleArray(tam)
        for (l in lineas.drop(1)) {
            val p = l.split(' ')
            val i = ShCoefficients.index(p[1].toInt(), p[2].toInt())
            val valores = p.drop(3).map { it.toDouble() }
            require(valores.size == epochs.size + 1) { "IGRF: fila con ${valores.size} valores" }
            val destino = if (p[0] == "g") g else h
            for (k in epochs.indices) destino[k][i] = valores[k]
            (if (p[0] == "g") sg else sh)[i] = valores.last()
        }
        return EpochModel(name, epochs, nmax, g, h, sg, sh, epochs.last() + 5.0)
    }
}
