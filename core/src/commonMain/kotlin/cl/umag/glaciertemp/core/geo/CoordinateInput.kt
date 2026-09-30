package cl.umag.glaciertemp.core.geo

/**
 * Coordenadas escritas a mano.
 *
 * SE ACEPTA COMO LO ESCRIBE LA GENTE, no como le conviene al programa: "-53.16", "53.16 S",
 * "53°9.6'S", "53 9 36 S", "S 53 09.600" y con coma decimal. Una casilla que solo acepta
 * grados decimales con signo obliga a convertir de cabeza un numero sacado de un mapa o de
 * otro GPS, y ese es el paso donde se cuela el signo equivocado.
 *
 * EL HEMISFERIO MANDA SOBRE EL SIGNO, y si vienen los dos y se contradicen es un error, no
 * se elige uno: "-53 N" no significa nada.
 */
object CoordinateInput {

    fun latitude(text: String): Double? = parse(text, 'N', 'S', 90.0)
    fun longitude(text: String): Double? = parse(text, 'E', 'W', 180.0)

    private val NUMERO = Regex("""\d+(?:[.,]\d*)?""")

    private fun parse(text: String, positivo: Char, negativo: Char, limite: Double): Double? {
        var t = text.trim().uppercase()
        if (t.isEmpty()) return null
        // W se admite tambien como O (oeste) cuando la letra negativa es W
        var hemisferio: Int? = null
        val letras = t.filter { it.isLetter() }
        for (c in letras) {
            val h = when {
                c == positivo -> 1
                c == negativo -> -1
                negativo == 'W' && c == 'O' -> -1
                else -> return null
            }
            if (hemisferio != null && hemisferio != h) return null
            hemisferio = h
        }
        val signo = t.trimStart().startsWith("-")
        t = t.replace(Regex("[A-Z]"), " ")
        val numeros = NUMERO.findAll(t).map { it.value.replace(',', '.').toDouble() }.toList()
        if (numeros.isEmpty() || numeros.size > 3) return null
        // solo el ultimo componente puede llevar decimales
        if (numeros.dropLast(1).any { it != kotlin.math.floor(it) }) return null
        val (g, m, s) = Triple(numeros[0], numeros.getOrElse(1) { 0.0 }, numeros.getOrElse(2) { 0.0 })
        if (numeros.size >= 2 && m >= 60) return null
        if (numeros.size == 3 && s >= 60) return null
        var v = g + m / 60 + s / 3600
        if (signo && hemisferio != null) return null
        if (signo || hemisferio == -1) v = -v
        return if (kotlin.math.abs(v) <= limite) v else null
    }
}
