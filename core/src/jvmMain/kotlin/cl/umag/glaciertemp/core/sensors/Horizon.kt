package cl.umag.glaciertemp.core.sensors

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A donde mira la camara, sacado de la MATRIZ DE ROTACION y no de yaw/pitch/roll.
 *
 * POR QUE NO SE USAN LOS ANGULOS DE EULER AQUI. El mapeo de horizonte pide sostener el
 * telefono VERTICAL, y esa es exactamente la postura en la que la descomposicion en yaw,
 * pitch y roll se vuelve singular: el yaw y el roll describen el mismo giro y el sensor
 * entrega una pareja cualquiera de las infinitas posibles (ver [Tilt]). La matriz de
 * rotacion, en cambio, no tiene ninguna singularidad: la direccion de la camara sale de ella
 * directamente y es estable en cualquier actitud.
 *
 * La matriz viene de `SensorManager.getRotationMatrixFromVector` y lleva vectores del sistema
 * del aparato al del mundo, con los ejes Este, Norte y Arriba.
 */
object ViewDirection {

    /** Azimut (desde el norte, hacia el este) y elevacion de la CAMARA TRASERA, en grados. */
    fun of(R: FloatArray): Pair<Double, Double> {
        // La camara trasera mira segun -Z del aparato. En el mundo, eso es menos la tercera
        // columna de R.
        val e = -R[2].toDouble()
        val n = -R[5].toDouble()
        val u = -R[8].toDouble()
        val az = Compass.normalize(Math.toDegrees(atan2(e, n)))
        val el = Math.toDegrees(asin(u.coerceIn(-1.0, 1.0)))
        return az to el
    }

    /**
     * Cuanto esta ladeado el telefono sobre el eje de la vista, en grados.
     *
     * NO AFECTA A LA MEDIDA --el eje optico apunta donde apunta, girado o no-- pero si a
     * poder apuntar: con la imagen torcida, la cruz no se alinea con el horizonte y quien
     * mira no sabe si esta apuntando alto o bajo. Por eso se avisa y no se corrige.
     */
    fun roll(R: FloatArray): Double {
        // El borde derecho del aparato es +X; si el telefono esta derecho, es horizontal y su
        // componente vertical vale cero.
        val arribaDelDerecho = R[6].toDouble()
        val horizontal = sqrt((R[0] * R[0] + R[3] * R[3]).toDouble())
        return Math.toDegrees(atan2(arribaDelDerecho, horizontal))
    }
}

/**
 * El horizonte medido: elevacion media por sector de azimut.
 *
 * POR SECTORES Y NO POR MUESTRA. Girando sobre uno mismo con el telefono en la mano, la
 * mano tiembla y el paso no es uniforme: hay cien muestras en un azimut y tres en el de al
 * lado. Promediar por sector de 5 grados da el mismo peso a cada trozo de cielo, que es lo
 * que se quiere, y ademas permite volver a pasar por donde ya se paso para corregir.
 */
class HorizonBins(val binDeg: Int = BIN_DEG) {

    companion object {
        const val BIN_DEG = 5
        fun binsFor(binDeg: Int) = 360 / binDeg
    }

    private val n = binsFor(binDeg)
    private val suma = DoubleArray(n)
    private val cuenta = IntArray(n)

    fun binOf(azimut: Double): Int =
        ((Compass.normalize(azimut) / binDeg).toInt()).coerceIn(0, n - 1)

    /** El centro del sector, que es el azimut al que corresponde su media. */
    fun centerOf(bin: Int): Double = (bin + 0.5) * binDeg

    fun add(azimut: Double, elevacion: Double) {
        val b = binOf(azimut)
        suma[b] += elevacion
        cuenta[b]++
    }

    fun samples(bin: Int): Int = cuenta[bin]

    /** La media del sector, o null si nunca se paso por ahi. */
    fun mean(bin: Int): Double? = if (cuenta[bin] == 0) null else suma[bin] / cuenta[bin]

    fun meanAt(azimut: Double): Double? = mean(binOf(azimut))

    fun covered(): Int = cuenta.count { it > 0 }

    fun total(): Int = n

    /**
     * El perfil completo, con los huecos rellenados interpolando entre vecinos.
     *
     * SE INTERPOLA Y SE DICE CUANTO. Un hueco sin rellenar obliga a decidir en cada cuenta
     * --el cielo visible, el panel solar-- que hacer con el, y cada cuenta acabaria
     * decidiendo distinto. Interpolando, todas parten del mismo perfil; cuantos sectores se
     * inventaron se informa aparte, que es lo que permite no fiarse.
     *
     * Devuelve null si no se midio NADA: no hay de donde interpolar.
     */
    fun profile(): HorizonProfile? {
        if (cuenta.all { it == 0 }) return null
        val v = DoubleArray(n)
        for (b in 0 until n) {
            v[b] = mean(b) ?: interpolar(b)
        }
        return HorizonProfile(v, binDeg)
    }

    /** Entre el sector medido mas cercano por un lado y por el otro, dando la vuelta. */
    private fun interpolar(bin: Int): Double {
        var antes = 1
        while (antes < n && cuenta[(bin - antes + n) % n] == 0) antes++
        var despues = 1
        while (despues < n && cuenta[(bin + despues) % n] == 0) despues++
        val a = mean((bin - antes + n) % n) ?: return 0.0
        val d = mean((bin + despues) % n) ?: return a
        return a + (d - a) * antes / (antes + despues)
    }
}

/** Un horizonte ya cerrado: una elevacion para cada azimut. */
data class HorizonProfile(val elevations: DoubleArray, val binDeg: Int) {

    fun elevationAt(azimut: Double): Double {
        val a = Compass.normalize(azimut)
        val i = ((a / binDeg).toInt()).coerceIn(0, elevations.size - 1)
        return elevations[i]
    }

    fun centerOf(bin: Int): Double = (bin + 0.5) * binDeg

    /**
     * Que fraccion del hemisferio celeste queda a la vista, entre 0 y 1.
     *
     * El angulo solido por encima de un horizonte h(A) es la integral de (1 - sin h) sobre el
     * azimut, y el hemisferio entero vale 2*pi. La cuenta se reduce a **1 menos la media de
     * sin h**, que es de las pocas cosas de este fichero que se pueden comprobar a mano.
     *
     * Los horizontes NEGATIVOS se recortan a cero. Desde una cumbre se ve algo por debajo de
     * la horizontal, pero eso no es mas cielo: es suelo lejano. Contarlo daria mas del 100 %.
     */
    fun skyFraction(): Double {
        val m = elevations.map { sin(Math.toRadians(it.coerceAtLeast(0.0))) }.average()
        return (1.0 - m).coerceIn(0.0, 1.0)
    }

    fun maxElevation(): Double = elevations.max()
    fun minElevation(): Double = elevations.min()
    fun meanElevation(): Double = elevations.average()

    /** El azimut del punto mas alto del horizonte: de donde viene la sombra que mas estorba. */
    fun highestAzimuth(): Double = centerOf(elevations.indices.maxBy { elevations[it] })

    override fun equals(other: Any?): Boolean =
        other is HorizonProfile && binDeg == other.binDeg &&
        elevations.contentEquals(other.elevations)

    override fun hashCode(): Int = 31 * elevations.contentHashCode() + binDeg
}
