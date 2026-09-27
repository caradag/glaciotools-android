package cl.umag.glaciertemp.core.sensors

/**
 * Medida en dos posiciones: el truco clasico de topografia para cancelar el sesgo del
 * instrumento sin tener que calibrarlo.
 *
 * COMO FUNCIONA. Se mide, se gira el aparato 180 grados SOBRE LA NORMAL DE LA SUPERFICIE en
 * la que se apoya --o sea se hace girar en su sitio, sin levantarlo-- y se vuelve a medir.
 * El sesgo del sensor va pegado al aparato y gira con el; la magnitud que se quiere medir no.
 * Combinando las dos lecturas, una de las dos cosas se cancela.
 *
 * CUAL SE CANCELA DEPENDE DE QUE SE ESTE MIDIENDO, y aqui es donde es facil equivocarse:
 *
 *  - Una magnitud de la SUPERFICIE --el buzamiento-- no cambia al girar el telefono sobre
 *    ella: el plano sigue siendo el mismo. El sesgo entra con signo contrario en las dos
 *    lecturas, asi que el valor sale de la MEDIA y el sesgo de la SEMIDIFERENCIA.
 *
 *  - Una magnitud del APARATO --su pitch, su roll-- si cambia de signo al girarlo. Entonces
 *    es al reves: el valor sale de la SEMIDIFERENCIA y el sesgo de la MEDIA.
 *
 * Promediar las dos lecturas en los dos casos --que es lo que sale solo si uno no se para a
 * pensarlo-- da el sesgo en vez del valor justo en el segundo caso, o sea casi cero.
 */
object Reversal {

    /** El resultado: la magnitud ya sin sesgo, y el sesgo que se cancelo. */
    data class Corrected(val value: Double, val bias: Double)

    /**
     * Para lo que NO cambia al girar el telefono sobre la superficie: el buzamiento.
     *
     * El plano es el mismo en las dos posiciones, asi que lo que difiere entre las lecturas
     * es el sesgo, dos veces.
     */
    fun surface(first: Double, second: Double): Corrected =
        Corrected(value = (first + second) / 2.0, bias = (first - second) / 2.0)

    /**
     * Para lo que SI cambia de signo: el pitch y el roll del propio aparato.
     *
     * Al girarlo media vuelta sobre la superficie, su eje largo apunta al lado contrario y la
     * inclinacion que mide cambia de signo. Lo que NO cambia de signo es el sesgo.
     */
    fun device(first: Double, second: Double): Corrected =
        Corrected(value = (first - second) / 2.0, bias = (first + second) / 2.0)

    /**
     * Para el rumbo: la segunda lectura viene girada 180 grados.
     *
     * Es la reversion clasica de brujula y cancela el hierro duro en el plano horizontal. Se
     * promedia CIRCULARMENTE, porque promediar 359 y 1 a lo bruto da el rumbo contrario.
     */
    fun heading(first: Double, second: Double): Corrected {
        val alineado = Compass.normalize(second - 180.0)
        val media = Angles.mean(listOf(first, alineado))
            ?: return Corrected(Compass.normalize(first), 0.0)
        // El sesgo es la mitad de lo que se separan las dos lecturas alineadas.
        val separacion = Angles.wrap(first - alineado)
        return Corrected(Compass.normalize(media), separacion / 2.0)
    }

    /** Cuanto hay que fiarse: un sesgo grande dice que algo mas pasa, no solo el sensor. */
    fun warning(biases: List<Double>): String? {
        val peor = biases.maxOfOrNull { kotlin.math.abs(it) } ?: return null
        return when {
            // Un acelerometro de telefono anda por decimas de grado. Grados enteros ya no es
            // sesgo del sensor: es que el telefono no se apoyo igual en las dos posiciones.
            peor > 3.0 -> "The two positions disagree by %.1f°. That is more than sensor bias: "
                              .format(peor * 2) +
                          "most likely the phone did not sit the same way both times. Repeat."
            peor > 1.0 -> "Bias of %.1f° removed — on the high side, but plausible."
                              .format(peor)
            else -> null
        }
    }
}
