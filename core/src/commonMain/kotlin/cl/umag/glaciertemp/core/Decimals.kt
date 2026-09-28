package cl.umag.glaciertemp.core

/**
 * Numeros con un numero fijo de decimales y SIEMPRE con punto.
 *
 * POR QUE EXISTE. En codigo comun no hay `String.format`: es una extension de la JVM. Pero la
 * razon de fondo es mejor que la obligacion. `"%.2f".format(v)` usa el idioma DEL APARATO, y
 * con el telefono en espanol escribia "1013,25" dentro de ficheros que separan sus columnas
 * por comas -- una linea de cinco campos pasaba a tener nueve y el registro entero quedaba
 * ilegible al recargarlo. Aqui no hay idioma que consultar, asi que ese fallo no se puede
 * volver a escribir.
 *
 * COMO REDONDEA, que es donde estas funciones se pueden diferenciar en silencio. Se redondea
 * sobre la representacion decimal MAS CORTA que reproduce el double (lo que da `toString`),
 * medio hacia arriba. `%.2f` de la JVM no hace eso: inspecciona el valor binario exacto, que
 * casi nunca termina donde el decimal escrito parece terminar. Los dos coinciden salvo en los
 * empates exactos -- 5,25 a un decimal da aqui "5.3" y en la JVM "5.2", porque el binario de
 * 5,25 es exacto y el de la mayoria de los empates no. `DecimalsTest` mide la discrepancia
 * contra `%.Nf` y fija que nunca pasa de una unidad del ultimo decimal.
 */
object Decimals {

    /** `v` con exactamente `decimals` decimales. Siempre punto, nunca coma. */
    fun fixed(v: Double, decimals: Int): String {
        require(decimals >= 0) { "decimales negativos: $decimals" }
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"

        val negativo = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)
        val (entera, fraccion) = plano(kotlin.math.abs(v))
        val redondeado = redondea(entera, fraccion, decimals)
        // El signo se conserva AUNQUE todo se redondee a cero: "-0.00" es lo que escribe
        // `%.2f`, y medido sobre un millon de valores era la UNICA diferencia entre las dos
        // funciones. Quitarlo por parecer ruido habria sido cambiar el comportamiento de la
        // app a cambio de nada, justo en el cambio que existe para no cambiar nada.
        return (if (negativo) "-" else "") + redondeado
    }

    /**
     * Como `fixed`, pero sin los ceros finales que no dicen nada: 1,50 -> "1.5", 2,0 -> "2".
     *
     * Es lo que quiere una columna de CSV, donde `%.8f` en todas las celdas engorda el fichero
     * sin anadir informacion.
     */
    fun trimmed(v: Double, decimals: Int): String {
        val s = fixed(v, decimals)
        if ('.' !in s) return s
        // "-0" si vuelve a "0": en una celda de CSV el signo de un cero no es un dato.
        return s.trimEnd('0').trimEnd('.').let { if (it == "" || it == "-" || it == "-0") "0" else it }
    }

    /**
     * La parte entera y la decimal de un double no negativo, SIN notacion cientifica.
     *
     * `toString` cambia a la forma con exponente fuera de [1e-3, 1e7), y un "1.0E20" metido en
     * un fichero de columnas no lo lee nadie. Aqui se despliega el exponente a mano.
     */
    private fun plano(x: Double): Pair<String, String> {
        val s = x.toString()
        val e = s.indexOfFirst { it == 'e' || it == 'E' }
        val mantisa = if (e < 0) s else s.substring(0, e)
        val exp = if (e < 0) 0 else mantisa.let { s.substring(e + 1).toInt() }

        val punto = mantisa.indexOf('.')
        var digitos = mantisa.replace(".", "")
        // Donde cae el punto contando desde la izquierda, ya movido por el exponente.
        var coma = (if (punto < 0) mantisa.length else punto) + exp

        if (coma <= 0) { digitos = "0".repeat(1 - coma) + digitos; coma = 1 }
        if (coma >= digitos.length) digitos += "0".repeat(coma - digitos.length + 1)

        return digitos.substring(0, coma).trimStart('0').ifEmpty { "0" } to
               digitos.substring(coma).trimEnd('0')
    }

    /** Redondeo medio-arriba sobre los digitos ya escritos, con acarreo hasta la parte entera. */
    private fun redondea(entera: String, fraccion: String, decimals: Int): String {
        if (fraccion.length <= decimals)
            return if (decimals == 0) entera
                   else entera + "." + fraccion.padEnd(decimals, '0')

        val sube = fraccion[decimals] >= '5'
        val cifras = (entera + fraccion.substring(0, decimals)).toCharArray()
        if (sube) {
            var i = cifras.size - 1
            while (i >= 0) {
                if (cifras[i] == '9') { cifras[i] = '0'; i-- } else { cifras[i]++; break }
            }
            // Se desbordo entero (999 -> 000): hace falta un digito mas por delante.
            if (i < 0) {
                val s = "1" + cifras.concatToString()
                return corta(s, s.length - decimals, decimals)
            }
        }
        val s = cifras.concatToString()
        return corta(s, entera.length, decimals)
    }

    private fun corta(s: String, hastaEntera: Int, decimals: Int): String =
        if (decimals == 0) s else s.substring(0, hastaEntera) + "." + s.substring(hastaEntera)
}
