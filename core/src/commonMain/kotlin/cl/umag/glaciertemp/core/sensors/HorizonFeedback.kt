package cl.umag.glaciertemp.core.sensors

import cl.umag.glaciertemp.core.Decimals
import kotlin.math.abs

/**
 * Lo que la pantalla del mapeador tiene que decirle a quien esta girando.
 *
 * Vive en core porque son reglas --que diferencia es tolerable, que sectores faltan, a que
 * velocidad se escapan-- y no dibujo. Ademas es lo unico de esa pantalla que se puede probar
 * sin telefono, porque lo demas es camara.
 */
object HorizonFeedback {

    /** Cuanto se aparta la pasada actual de lo ya medido en ese sector. */
    enum class Band { FINE, OFF, WAY_OFF }

    /** Por debajo de esto la diferencia es ruido de pulso y no merece corregirse. */
    const val FINE_DEG = 2.0

    /** Por encima de esto ya no es la mano: es que se esta apuntando a otra cosa. */
    const val OFF_DEG = 5.0

    /**
     * En que banda cae la diferencia.
     *
     * TRES BANDAS Y NO UN DEGRADADO. Con el telefono a un brazo y el sol de frente, lo que se
     * distingue es el COLOR, no el tono: verde es "sigue", ambar es "corrige", rojo es "esto
     * no es el mismo horizonte". Un degradado continuo obligaria a leer el numero, que es lo
     * que no se puede hacer mientras se gira.
     */
    fun band(differenceDeg: Double): Band {
        val d = abs(differenceDeg)
        return when {
            d < FINE_DEG -> Band.FINE
            d < OFF_DEG -> Band.OFF
            else -> Band.WAY_OFF
        }
    }

    /**
     * Largo de la flecha, en multiplos del brazo de la cruz.
     *
     * PROPORCIONAL A LA DIFERENCIA, con tope. El color dice la banda y el largo dice cuanto
     * dentro de ella: asi una desviacion de 6 grados y una de 25 no se ven iguales aunque
     * las dos sean rojas. Sin tope, una diferencia grande dibujaria una flecha que se sale de
     * la pantalla y deja de poder compararse con nada.
     */
    fun lengthFactor(differenceDeg: Double): Double =
        (abs(differenceDeg) / 10.0).coerceIn(0.35, 1.8)

    /**
     * Los tramos de azimut que nunca se midieron.
     *
     * SE DEVUELVEN COMO TRAMOS y no como una lista de sectores sueltos: "faltan 145 a 170
     * grados" se puede ir a rellenar, y "faltan los sectores 29, 30, 31, 32, 33" no.
     */
    fun gaps(covered: BooleanArray, binDeg: Int): List<ClosedRange<Double>> {
        val n = covered.size
        if (n == 0 || covered.all { it }) return emptyList()
        if (covered.none { it }) return listOf(0.0..360.0)

        val tramos = ArrayList<ClosedRange<Double>>()
        // Se empieza en un sector medido para que un hueco a caballo del norte salga entero
        // y no partido en dos.
        val inicio = covered.indexOfFirst { it }
        var i = 0
        while (i < n) {
            val b = (inicio + i) % n
            if (covered[b]) { i++; continue }
            var largo = 0
            while (largo < n && !covered[(inicio + i + largo) % n]) largo++
            tramos += (b * binDeg).toDouble()..((b + largo) * binDeg).toDouble()
            i += largo
        }
        return tramos
    }

    /** Los tramos en palabras, para el mensaje. */
    fun describeGaps(gaps: List<ClosedRange<Double>>): String =
        gaps.joinToString(", ") {
            Decimals.fixed(it.start, 0) + "°–" + Decimals.fixed(it.endInclusive % 360.0, 0) + "°"
        }

    /**
     * Si se esta girando tan deprisa que los sectores se escapan.
     *
     * La cuenta es directa: a [degPerSecond] grados por segundo y [sampleHz] muestras por
     * segundo, cada sector de [binDeg] grados recibe binDeg*sampleHz/degPerSecond muestras.
     * Por debajo de tres, un sector entero puede quedarse sin ninguna.
     *
     * SE AVISA EN VEZ DE INTERPOLAR. Rellenar el hueco con la media de los vecinos daria un
     * perfil de aspecto impecable con un trozo inventado dentro, y nadie sabria cual.
     */
    const val MIN_SAMPLES_PER_BIN = 3.0

    fun tooFast(degPerSecond: Double, binDeg: Int, sampleHz: Double): Boolean {
        if (degPerSecond <= 0.0 || sampleHz <= 0.0) return false
        return binDeg * sampleHz / degPerSecond < MIN_SAMPLES_PER_BIN
    }

    /** La velocidad a la que conviene no pasar, para poder decirla. */
    fun maxComfortableRate(binDeg: Int, sampleHz: Double): Double =
        binDeg * sampleHz / MIN_SAMPLES_PER_BIN
}
