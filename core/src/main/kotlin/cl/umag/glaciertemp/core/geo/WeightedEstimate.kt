package cl.umag.glaciertemp.core.geo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * La estimacion de un eje a partir de muestras con peso, repartidas en sesiones.
 *
 * Tres numeros que responden a tres preguntas distintas:
 *
 *  - [estimate] es la posicion: media PONDERADA por la calidad declarada de cada arreglo.
 *  - [sd] es cuanto se dispersan las muestras que se usaron. Baja si el receptor mejora
 *    --y suele mejorar: los primeros arreglos de una sesion son los peores-- pero no baja
 *    por el mero hecho de acumular mas.
 *  - [standardError] es la incertidumbre de la estimacion, y tiene en cuenta que las
 *    muestras seguidas NO son independientes. Es la que dice si vale la pena seguir.
 */
data class Axis(
    val n: Int,
    val nEffective: Double,
    val sessions: Int,
    val estimate: Double,
    val median: Double,
    val sd: Double,
    val standardError: Double,
    val rejected: Int,
)

/**
 * Media ponderada por la calidad, jerarquica por sesiones y con cribado de disparates.
 *
 * ================================ POR QUE ASI ================================
 *
 * **El peso es 1/sigma² y no 1/sigma.** Ponderar por la inversa de la VARIANZA es lo que
 * hace que la media resultante sea la de minima varianza entre todas las combinaciones
 * lineales insesgadas; con 1/sigma la estimacion sigue siendo valida pero es peor, y la
 * formula de su incertidumbre deja de ser la sencilla. El receptor declara su precision
 * como un radio de confianza del 68 %, que es aproximadamente una sigma, asi que sigma es
 * ese numero tal cual.
 *
 * **Se criba antes de ponderar.** La media ponderada perdio lo unico bueno que tenia la
 * mediana: aguantar un arreglo disparatado. Un GNSS suelta de vez en cuando una posicion a
 * cientos de metros y, para colmo, a veces la declara con buena precision -- con lo que la
 * ponderacion le da MAS peso, no menos. Antes de nada se calcula la mediana y la MAD, y se
 * descartan las muestras que se apartan mas de [MAD_LIMIT] MADs. Las descartadas se
 * CUENTAN: descartar en silencio es lo mismo que no medir.
 *
 * **Las muestras seguidas no son independientes, y las sesiones casi si.** El error de un
 * GNSS viene de la geometria de los satelites, de la ionosfera y del rebote en lo que haya
 * alrededor, y ninguna de esas tres cosas cambia de un segundo al siguiente: cambian en
 * minutos o en decenas de minutos. Mil arreglos en veinte minutos no son mil datos, son
 * unos pocos repetidos mil veces. Dos visitas en dias distintos, en cambio, ven
 * constelaciones y atmosferas distintas, y esas si aportan informacion nueva.
 *
 * De ahi que el calculo vaya en dos pisos:
 *
 *  1. Dentro de cada sesion se estima cuanta informacion independiente hay de verdad, con
 *     el tamano de muestra EFECTIVO de un proceso AR(1): n_ef = n·(1-rho)/(1+rho), donde rho
 *     es la autocorrelacion de retardo uno de los residuos. Se mide de los propios datos y
 *     no se supone una constante, que es lo que lo hace honesto: una sesion con el
 *     telefono quieto bajo un arbol da rho alto y n_ef bajo, y una al aire libre con buena
 *     geometria da rho bajo y n_ef alto.
 *  2. Las sesiones se combinan ponderando por la inversa de la varianza de SU media. Una
 *     sesion corta de tres minutos pesa poco frente a una de una hora, pero pesa mucho mas
 *     de lo que le tocaria por su numero de muestras -- que es exactamente lo que se busca.
 *
 * El efecto practico: dejar el telefono una hora en el mismo sitio mejora mucho menos que
 * volver tres dias a medir veinte minutos, y los numeros de la pantalla lo reflejan en vez
 * de prometer una precision que no existe.
 */
object WeightedEstimate {

    /** A cuantas MAD de la mediana deja de considerarse una muestra y pasa a ser un fallo. */
    const val MAD_LIMIT = 5.0

    /** Para convertir la MAD en algo comparable con una sigma gaussiana. */
    private const val MAD_TO_SIGMA = 1.4826

    /** Sigma que se supone cuando el receptor no declara ninguna precision. */
    const val DEFAULT_SIGMA = 10.0

    /**
     * El rho se acota por arriba. Con muestras muy correlacionadas la formula tiende a
     * infinito de informacion perdida, y un rho estimado de 0,999 sobre ruido daria n_ef
     * menor que uno, es decir, menos informacion que una sola muestra, que es absurdo.
     */
    private const val MAX_RHO = 0.98

    /**
     * @param values el valor de cada muestra, en el orden en que se tomaron
     * @param sigmas la precision declarada de cada una; null donde no la haya
     * @param sessions a que sesion pertenece cada muestra
     */
    fun of(values: List<Double>, sigmas: List<Double?>, sessions: List<Int>): Axis? {
        if (values.isEmpty()) return null
        require(values.size == sigmas.size && values.size == sessions.size) {
            "las tres listas describen las mismas muestras y tienen que medir lo mismo"
        }

        // --- 1. Cribado robusto, sobre TODAS las muestras -------------------------------
        val mediana = mediana(values)
        val mad = mediana(values.map { abs(it - mediana) }) * MAD_TO_SIGMA
        val guardar = BooleanArray(values.size) { true }
        var descartadas = 0
        if (mad > 0) {
            for (i in values.indices) {
                if (abs(values[i] - mediana) > MAD_LIMIT * mad) {
                    guardar[i] = false
                    descartadas++
                }
            }
        }
        // Si el cribado se lleva casi todo, el que esta mal es el cribado: pasa cuando la
        // nube tiene dos grupos legitimos y la MAD sale minuscula. Mejor no cribar nada.
        if (descartadas > values.size / 2) {
            java.util.Arrays.fill(guardar, true)
            descartadas = 0
        }

        val idx = values.indices.filter { guardar[it] }
        if (idx.isEmpty()) return null

        // Sin ninguna precision declarada, todos los pesos iguales: lo que importa es la
        // proporcion entre ellos, no su valor.
        val haySigmas = idx.any { sigmas[it] != null && sigmas[it]!! > 0 }
        fun sigmaDe(i: Int): Double =
            if (!haySigmas) 1.0
            else (sigmas[i]?.takeIf { it > 0 } ?: DEFAULT_SIGMA)

        // --- 2. Cada sesion por su cuenta ---------------------------------------------
        data class Tramo(val media: Double, val varianzaDeLaMedia: Double, val nEf: Double,
                         val n: Int)

        val tramos = idx.groupBy { sessions[it] }.values.mapNotNull { miembros ->
            val orden = miembros.sortedBy { it }          // ya vienen en orden temporal
            val w = orden.map { 1.0 / (sigmaDe(it) * sigmaDe(it)) }
            val sw = w.sum()
            if (sw <= 0) return@mapNotNull null
            val media = orden.indices.sumOf { w[it] * values[orden[it]] } / sw

            val n = orden.size
            if (n == 1) {
                val s = sigmaDe(orden[0])
                return@mapNotNull Tramo(media, s * s, 1.0, 1)
            }

            // Varianza de la media SI las muestras fueran independientes.
            val varIndependiente = 1.0 / sw
            // Y el castigo por no serlo.
            val residuos = orden.map { values[it] - media }
            val rho = autocorrelacionUno(residuos).coerceIn(0.0, MAX_RHO)
            val nEf = (n * (1 - rho) / (1 + rho)).coerceIn(1.0, n.toDouble())
            Tramo(media, varIndependiente * (n / nEf), nEf, n)
        }
        if (tramos.isEmpty()) return null

        // --- 3. Las sesiones se combinan por la inversa de la varianza de su media ------
        val pesos = tramos.map { 1.0 / max(it.varianzaDeLaMedia, 1e-12) }
        val sumaPesos = pesos.sum()
        val estimacion = tramos.indices.sumOf { pesos[it] * tramos[it].media } / sumaPesos
        val errorEstandar = sqrt(1.0 / sumaPesos)

        // --- 4. La dispersion, para ensenarla ------------------------------------------
        val w = idx.map { 1.0 / (sigmaDe(it) * sigmaDe(it)) }
        val sw = w.sum()
        val sw2 = w.sumOf { it * it }
        val mediaGlobal = idx.indices.sumOf { w[it] * values[idx[it]] } / sw
        val denom = sw - sw2 / sw
        val sd = if (idx.size < 2 || denom <= 0) 0.0
                 else sqrt(idx.indices.sumOf {
                     w[it] * (values[idx[it]] - mediaGlobal).let { d -> d * d }
                 } / denom)

        return Axis(
            n = idx.size,
            nEffective = tramos.sumOf { it.nEf },
            sessions = tramos.size,
            estimate = estimacion,
            median = mediana(idx.map { values[it] }),
            sd = sd,
            standardError = errorEstandar,
            rejected = descartadas,
        )
    }

    internal fun mediana(v: List<Double>): Double {
        if (v.isEmpty()) return Double.NaN
        val o = v.sorted()
        val n = o.size
        return if (n % 2 == 1) o[n / 2] else (o[n / 2 - 1] + o[n / 2]) / 2.0
    }

    /**
     * Autocorrelacion de retardo uno de una serie ya centrada.
     *
     * Es la medida mas barata de "cuanto se parece cada muestra a la anterior", y para un
     * proceso AR(1) --que es una descripcion razonable del error de un GNSS a corto plazo--
     * basta con ella para saber cuanta informacion independiente hay.
     */
    internal fun autocorrelacionUno(residuos: List<Double>): Double {
        val n = residuos.size
        if (n < 3) return 0.0
        val denominador = residuos.sumOf { it * it }
        if (denominador <= 0) return 0.0
        var numerador = 0.0
        for (i in 0 until n - 1) numerador += residuos[i] * residuos[i + 1]
        return numerador / denominador
    }
}
