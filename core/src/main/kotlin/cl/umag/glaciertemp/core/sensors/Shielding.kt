package cl.umag.glaciertemp.core.sensors

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Factor de apantallamiento topografico para nucleidos cosmogenicos.
 *
 * QUE ES. Los rayos cosmicos llegan de todo el cielo, pero no por igual: la intensidad cae
 * con el angulo respecto a la vertical siguiendo aproximadamente cos^m con m = 2,3. Una
 * montana delante tapa una parte del cielo y con ella una parte de la produccion de
 * nucleidos; el factor dice que fraccion de la produccion de un sitio despejado queda. Sin
 * el, una edad de exposicion calculada al pie de una pared sale sistematicamente joven.
 *
 * DE DONDE SALE ESTE CODIGO. Es el algoritmo de `skyline.m` de Greg Balco, el que usan las
 * calculadoras CRONUS-Earth e ICE-D. Se reproduce paso por paso a proposito: el valor tiene
 * que ser comparable con el que publican los demas, y una variante "mejorada" que diera otro
 * numero seria peor aunque fuera mas exacta.
 *
 * LA INTEGRAL, en sectores de un grado:
 *
 *     S = (1/360) * suma de sin(h)^3.3        factor = 1 - S
 *
 * El exponente es m + 1 = 3,3 y sale de integrar cos^2,3 sobre el angulo solido tapado.
 * Que la cuenta se reduzca a "uno menos la media de sin(h)^3,3" es lo que permite
 * comprobarla a mano: con un horizonte plano a 90 grados el factor es cero, y con uno a
 * cero grados es uno.
 */
object Shielding {

    /** m + 1, con m = 2,3 para la distribucion angular de los muones y nucleones. */
    const val EXPONENT = 3.3

    /** La rejilla del calculo: 360 sectores de un grado, como en skyline.m. */
    const val SECTORS = 360

    /**
     * @param factor fraccion de la produccion de un sitio despejado que queda
     * @param horizonDeg el horizonte compuesto, un valor por grado de azimut
     * @param fromTerrain lo que apantalla el relieve por si solo
     * @param fromDip lo que apantalla la propia superficie inclinada por si sola
     */
    data class Result(
        val factor: Double,
        val horizonDeg: DoubleArray,
        val fromTerrain: Double,
        val fromDip: Double,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Result && factor == other.factor && horizonDeg.contentEquals(other.horizonDeg)
        override fun hashCode(): Int = horizonDeg.contentHashCode() * 31 + factor.hashCode()
    }

    /**
     * El horizonte que se hace una superficie inclinada a si misma.
     *
     * Una roca que buza no recibe nada del hemisferio que le queda por debajo del plano. En
     * la direccion de buzamiento el "horizonte" propio vale cero --ahi el cielo esta
     * abierto-- y en la contraria vale el angulo de buzamiento.
     *
     * CONVENIO: el buzamiento cae a la DERECHA de la direccion de rumbo. Rumbo 0 y
     * buzamiento 45 es una superficie que cae 45 grados hacia el este.
     */
    fun dippingHorizon(strikeDeg: Double, dipDeg: Double): DoubleArray {
        val h = DoubleArray(SECTORS)
        if (dipDeg <= 0.0) return h
        val strike = Math.toRadians(strikeDeg)
        val dip = Math.toRadians(dipDeg)
        for (i in 0 until SECTORS) {
            val a = Math.toRadians((i + 1).toDouble()) - (strike - Math.PI / 2)
            // Negativo es por debajo de la horizontal: ahi no tapa nada.
            h[i] = atan(tan(dip) * cos(a)).coerceAtLeast(0.0)
        }
        return h
    }

    /**
     * El horizonte medido, llevado a la rejilla de un grado interpolando linealmente.
     *
     * Se interpola ENTRE PUNTOS, que es lo que hace skyline.m, y da la vuelta: el punto
     * anterior al primero es el ultimo menos 360 grados.
     */
    fun interpolate(azimuths: DoubleArray, elevationsDeg: DoubleArray): DoubleArray {
        require(azimuths.size == elevationsDeg.size) { "azimuts y elevaciones de distinta longitud" }
        val h = DoubleArray(SECTORS)
        if (azimuths.isEmpty()) return h

        val orden = azimuths.indices.sortedBy { Compass.normalize(azimuths[it]) }
        val az = DoubleArray(orden.size + 2)
        val el = DoubleArray(orden.size + 2)
        orden.forEachIndexed { k, i ->
            az[k + 1] = Compass.normalize(azimuths[i])
            el[k + 1] = elevationsDeg[i]
        }
        // Relleno circular por los dos extremos.
        az[0] = az[orden.size] - 360.0; el[0] = el[orden.size]
        az[orden.size + 1] = az[1] + 360.0; el[orden.size + 1] = el[1]

        for (i in 0 until SECTORS) {
            val x = (i + 1).toDouble()
            var k = 0
            while (k < az.size - 2 && az[k + 1] < x) k++
            val x0 = az[k]; val x1 = az[k + 1]
            h[i] = if (x1 == x0) el[k]
                   else Math.toRadians(el[k] + (el[k + 1] - el[k]) * (x - x0) / (x1 - x0))
        }
        return h
    }

    /** La integral de skyline.m: uno menos la media de sin(h) elevado a 3,3. */
    fun factorOf(horizonRad: DoubleArray): Double {
        val s = horizonRad.sumOf { sin(it.coerceIn(0.0, Math.PI / 2)).pow(EXPONENT) } / SECTORS
        return (1.0 - s).coerceIn(0.0, 1.0)
    }

    /**
     * El calculo completo: relieve, superficie inclinada, y los dos juntos.
     *
     * EL HORIZONTE COMPUESTO ES EL MAXIMO de los dos, no su suma. Es la unica parte del
     * algoritmo que no es obvia y la que evita contar dos veces: donde la pared de roca ya
     * tapa mas que el propio buzamiento, el buzamiento no quita nada adicional.
     */
    fun compute(profile: HorizonProfile?, strikeDeg: Double = 0.0, dipDeg: Double = 0.0): Result {
        val porBuzamiento = dippingHorizon(strikeDeg, dipDeg)
        val porRelieve = if (profile == null) DoubleArray(SECTORS) else {
            val az = DoubleArray(profile.elevations.size) { profile.centerOf(it) }
            // Las elevaciones negativas no aportan: por debajo de la horizontal no hay
            // apantallamiento que contar, igual que en skyline.m.
            val el = DoubleArray(profile.elevations.size) { profile.elevations[it].coerceAtLeast(0.0) }
            interpolate(az, el)
        }
        val compuesto = DoubleArray(SECTORS) { maxOf(porBuzamiento[it], porRelieve[it]) }
        return Result(
            factor = factorOf(compuesto),
            horizonDeg = DoubleArray(SECTORS) { Math.toDegrees(compuesto[it]) },
            fromTerrain = factorOf(porRelieve),
            fromDip = factorOf(porBuzamiento),
        )
    }

    /**
     * Rumbo y buzamiento de la superficie sobre la que se apoya el telefono.
     *
     * La normal de la superficie es el eje +Z del aparato, o sea la tercera columna de la
     * matriz de rotacion. El buzamiento es lo que esa normal se aparta de la vertical, y la
     * direccion de maxima pendiente es la de la normal proyectada, al reves: la normal se
     * inclina hacia ARRIBA de la pendiente.
     */
    fun strikeDipFrom(R: FloatArray): Pair<Double, Double> {
        val e = R[2].toDouble()
        val n = R[5].toDouble()
        val u = R[8].toDouble()
        val dip = Math.toDegrees(kotlin.math.acos(u.coerceIn(-1.0, 1.0)))
        // Sin pendiente apreciable la direccion no significa nada; se devuelve 0 y no ruido.
        if (dip < 0.5) return 0.0 to 0.0
        val direccionDeBuzamiento = Compass.normalize(Math.toDegrees(kotlin.math.atan2(-e, -n)))
        // El buzamiento cae a la derecha del rumbo: rumbo = direccion de buzamiento - 90.
        return Compass.normalize(direccionDeBuzamiento - 90.0) to dip.coerceIn(0.0, 90.0)
    }
}
