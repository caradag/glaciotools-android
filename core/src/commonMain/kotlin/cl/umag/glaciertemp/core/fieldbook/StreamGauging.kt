package cl.umag.glaciertemp.core.fieldbook

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Un aforo de caudal por el metodo de area-velocidad.
 *
 * COMO SE MIDE UN RIO. Se tiende una cinta de orilla a orilla, se divide el ancho en tramos
 * iguales y en el centro de cada tramo se mide la profundidad y la velocidad del agua. El
 * caudal de cada tramo es su area por su velocidad, y el del rio es la suma. Lo que lo hace
 * util es que los tramos sean estrechos: la velocidad cambia mucho de una orilla a otra y un
 * tramo ancho promedia justo lo que interesa distinguir.
 *
 * POR QUE AL 60 % DE LA PROFUNDIDAD. La velocidad no es uniforme en la vertical: es casi cero
 * en el fondo y maxima cerca de la superficie. El perfil se parece bastante a una curva
 * logaritmica, y para esa curva la velocidad MEDIA de la vertical se da a seis decimos de la
 * profundidad contados desde arriba. Medir ahi una sola vez da, en un solo molinetazo, lo que
 * de otro modo exigiria promediar varias profundidades. Es el metodo de un punto, y es la
 * practica estandar en corrientes poco profundas.
 *
 * SE GUARDAN LAS HORAS DE CADA CASILLA. Un aforo no es instantaneo --un perfil de siete
 * metros a veinte centimetros son treinta y cinco verticales y puede llevar una hora-- y
 * durante esa hora el rio puede estar subiendo o bajando. Saber cuando se midio cada dato es
 * lo que despues permite decir si el caudal calculado corresponde a un instante o a una
 * media de la crecida. No se puede reconstruir despues, asi que se anota sola.
 */
data class GaugingBin(
    /** Profundidad del agua en el centro del tramo, en metros. */
    val depthM: Double? = null,
    /** Velocidad medida, en metros por segundo. Puede ser negativa en un remolino. */
    val velocityMps: Double? = null,
    /**
     * Cuando se escribio por PRIMERA y por ULTIMA vez cada casilla.
     *
     * Las dos y no una sola. La primera dice cuando se tomo la medida, que es el dato; la
     * ultima dice si despues se corrigio, y una correccion hecha media hora mas tarde tiene
     * un significado muy distinto del valor original. Guardando solo una, la diferencia
     * entre "medido a las 10:05" y "medido a las 10:05 y arreglado a las 11:40" desaparece
     * sin dejar rastro.
     */
    val depthFirstEditMillis: Long? = null,
    val depthLastEditMillis: Long? = null,
    val velocityFirstEditMillis: Long? = null,
    val velocityLastEditMillis: Long? = null,
) {
    val isEmpty: Boolean get() = depthM == null && velocityMps == null
}

/** Los datos del aforo que cuelgan de la entrada de libreta. */
data class StreamGauging(
    /** Ancho total del perfil, en metros. */
    val widthM: Double? = null,
    /** Ancho de cada tramo, en metros. Se teclea en centimetros pero se guarda en metros. */
    val intervalM: Double? = null,
    /**
     * Si la tercera columna cuenta desde el FONDO en vez de desde la superficie.
     *
     * Es solo como se presenta el mismo numero: 0,6*d desde arriba es 0,4*d desde abajo. Se
     * ofrecen las dos porque el molinete se baja desde la superficie con una barra graduada
     * --y ahi se quiere la primera-- pero algunos equipos se apoyan en el fondo, y entonces
     * la util es la segunda. Convertirlo de cabeza junto al agua es justo donde se cuela un
     * error que despues no se ve.
     */
    val depthFromBed: Boolean = false,
    val bins: List<GaugingBin> = emptyList(),
    val comments: String = "",
) {
    fun isEmpty(): Boolean =
        widthM == null && intervalM == null && comments.isBlank() && bins.all { it.isEmpty }
}

/** La geometria y la aritmetica del aforo. Sin nada de plataforma: se prueba sin telefono. */
object Gauging {

    /** A seis decimos de la profundidad, contados desde la superficie. */
    const val VELOCITY_DEPTH_FRACTION = 0.6

    /**
     * Tope de tramos.
     *
     * Existe por un dedo, no por una limitacion: tecleando el intervalo en centimetros es
     * facil poner 2 donde iban 20, y sin tope un perfil de siete metros generaria tres mil
     * quinientas filas y la pantalla se quedaria pensando. Con tope, se avisa.
     */
    const val MAX_BINS = 500

    /**
     * Cuanto puede durar UNA escritura antes de contar como una correccion aparte.
     *
     * Existe porque la casilla se guarda en cada pulsacion: tecleando "0.85" hay cuatro
     * ediciones, la primera y la ultima separadas por un segundo. Sin este umbral, toda
     * casilla escrita con mas de un digito pareceria haber sido corregida despues, y el
     * aviso que sirve para detectar una correccion de verdad --la que se hizo media hora mas
     * tarde, con el rio ya cambiado-- no distinguiria nada porque saltaria siempre.
     *
     * Treinta segundos: mas de lo que cuesta teclear un numero aun con guantes, y mucho
     * menos que el rato que pasa hasta que alguien vuelve sobre una vertical.
     */
    const val SAME_WRITING_MS = 30_000L

    /**
     * Si el valor se cambio DESPUES, y no es solo el final de la misma tecleada.
     *
     * Devuelve false cuando no hay dato: una casilla vacia no se corrigio, no se escribio.
     */
    fun wasCorrected(first: Long?, last: Long?): Boolean {
        if (first == null || last == null) return false
        return last - first > SAME_WRITING_MS
    }

    /** Un tramo del perfil: donde empieza, donde acaba y cual es su centro. */
    data class Bin(val index: Int, val startM: Double, val endM: Double) {
        val widthM: Double get() = endM - startM
        val centreM: Double get() = (startM + endM) / 2.0
    }

    /**
     * Los tramos en que se divide el perfil.
     *
     * EL ULTIMO PUEDE SER MAS CORTO, y su centro es el centro DE EL, no el que le tocaria si
     * fuera entero. Un perfil de 7 m a 0,20 m son 35 tramos justos, pero uno de 7,1 m deja un
     * ultimo tramo de 10 cm cuyo centro esta a 7,05 y no a 7,10. Darle el centro nominal
     * pondria la medida fuera del agua, y darle el ancho nominal contaria 10 cm de rio que no
     * existen: dos errores pequenos que se suman al caudal total sin avisar.
     */
    fun bins(widthM: Double?, intervalM: Double?): List<Bin> {
        if (widthM == null || intervalM == null) return emptyList()
        if (!widthM.isFinite() || !intervalM.isFinite()) return emptyList()
        if (widthM <= 0.0 || intervalM <= 0.0) return emptyList()

        // Un pelin de holgura para que 7,0 / 0,2 no de 36 tramos por culpa del binario.
        val n = ceil(widthM / intervalM - 1e-9).toInt().coerceAtLeast(1)
        if (n > MAX_BINS) return emptyList()
        return (0 until n).map { i ->
            Bin(i, i * intervalM, min((i + 1) * intervalM, widthM))
        }
    }

    /** Cuantos tramos saldrian, aunque pasen del tope. Sirve para poder explicar el aviso. */
    fun binCount(widthM: Double?, intervalM: Double?): Int {
        if (widthM == null || intervalM == null) return 0
        if (!widthM.isFinite() || !intervalM.isFinite()) return 0
        if (widthM <= 0.0 || intervalM <= 0.0) return 0
        return ceil(widthM / intervalM - 1e-9).toInt().coerceAtLeast(1)
    }

    /**
     * A que profundidad hay que poner el molinete, en metros.
     *
     * Devuelve la MISMA posicion fisica de las dos maneras: fromBed = false la cuenta desde
     * la superficie (0,6*d) y fromBed = true desde el fondo (0,4*d). No es una opcion de
     * calculo, es como se lee la barra que se tenga en la mano.
     */
    fun measurementDepthM(depthM: Double?, fromBed: Boolean): Double? {
        if (depthM == null || !depthM.isFinite() || depthM <= 0.0) return null
        val desdeArriba = depthM * VELOCITY_DEPTH_FRACTION
        return if (fromBed) depthM - desdeArriba else desdeArriba
    }

    /**
     * El caudal de un tramo, en metros cubicos por segundo: ancho x profundidad x velocidad.
     *
     * Null --y no cero-- mientras falte cualquiera de los dos datos. Cero significaria que
     * por ahi no pasa agua, que es una afirmacion sobre el rio; lo que ocurre es que todavia
     * no se ha medido, que es una afirmacion sobre la libreta. Sumar los ceros daria un
     * caudal total que parece completo estando a medias.
     */
    fun binDischarge(bin: Bin, b: GaugingBin): Double? {
        val d = b.depthM ?: return null
        val v = b.velocityMps ?: return null
        if (!d.isFinite() || !v.isFinite() || d < 0.0) return null
        return bin.widthM * d * v
    }

    /** El area mojada de un tramo, en metros cuadrados. */
    fun binArea(bin: Bin, b: GaugingBin): Double? {
        val d = b.depthM ?: return null
        if (!d.isFinite() || d < 0.0) return null
        return bin.widthM * d
    }

    /**
     * Lo que se ensena arriba mientras se mide, y lo que se copia al terminar.
     *
     * El caudal es PARCIAL hasta que todos los tramos tienen los dos datos, y eso se dice
     * explicitamente en vez de dejar un numero suelto: un aforo a medias y uno terminado se
     * parecen demasiado en pantalla, y el segundo es el unico que se puede apuntar.
     */
    data class Summary(
        val binCount: Int,
        /** Tramos con profundidad Y velocidad, que son los que aportan caudal. */
        val completeBins: Int,
        val binsWithDepth: Int,
        val binsWithVelocity: Int,
        /** Suma de los caudales de los tramos completos, en m3/s. */
        val dischargeM3s: Double?,
        /** Area mojada total de los tramos con profundidad, en m2. */
        val areaM2: Double?,
        val maxVelocityMps: Double?,
        val meanVelocityMps: Double?,
        val maxDepthM: Double?,
        val meanDepthM: Double?,
        val depthTimes: TimeSpan,
        val velocityTimes: TimeSpan,
    ) {
        val isComplete: Boolean get() = binCount > 0 && completeBins == binCount
    }

    /**
     * Cuando empezo, cuando acabo y la hora del medio de una tanda de medidas.
     *
     * LA MEDIANA Y NO LA MEDIA. Un aforo se interrumpe: se cambia una pila, se saca una foto,
     * se rehace una vertical una hora despues. La media se va detras de esa cola y deja de
     * representar cuando se midio el rio; la mediana se queda donde estuvo el grueso del
     * trabajo, que es lo que se quiere para fechar el caudal.
     */
    data class TimeSpan(val firstMillis: Long?, val lastMillis: Long?, val medianMillis: Long?) {
        val isEmpty: Boolean get() = firstMillis == null
    }

    private fun span(times: List<Long>): TimeSpan {
        if (times.isEmpty()) return TimeSpan(null, null, null)
        val o = times.sorted()
        val n = o.size
        // Con un numero par de medidas se promedian las dos centrales, como manda la
        // definicion. En milisegundos la diferencia es invisible, pero una mediana que no
        // es la mediana es de las cosas que muerden en otro sitio anos despues.
        val mediana = if (n % 2 == 1) o[n / 2] else (o[n / 2 - 1] + o[n / 2]) / 2
        return TimeSpan(o.first(), o.last(), mediana)
    }

    fun summarize(g: StreamGauging): Summary {
        val tramos = bins(g.widthM, g.intervalM)
        val datos = tramos.map { it to g.bins.getOrElse(it.index) { GaugingBin() } }

        val caudales = datos.mapNotNull { (t, b) -> binDischarge(t, b) }
        val areas = datos.mapNotNull { (t, b) -> binArea(t, b) }
        val velocidades = datos.mapNotNull { (_, b) -> b.velocityMps?.takeIf { it.isFinite() } }
        val profundidades = datos.mapNotNull { (_, b) -> b.depthM?.takeIf { it.isFinite() } }

        return Summary(
            binCount = tramos.size,
            completeBins = caudales.size,
            binsWithDepth = profundidades.size,
            binsWithVelocity = velocidades.size,
            dischargeM3s = caudales.takeIf { it.isNotEmpty() }?.sum(),
            areaM2 = areas.takeIf { it.isNotEmpty() }?.sum(),
            maxVelocityMps = velocidades.maxOrNull(),
            meanVelocityMps = velocidades.takeIf { it.isNotEmpty() }?.average(),
            maxDepthM = profundidades.maxOrNull(),
            meanDepthM = profundidades.takeIf { it.isNotEmpty() }?.average(),
            // La hora de la PRIMERA edicion: es cuando se tomo la medida. La ultima es la
            // correccion, y meterla aqui correria el inicio del aforo hasta la correccion.
            depthTimes = span(g.bins.mapNotNull { it.depthFirstEditMillis }),
            velocityTimes = span(g.bins.mapNotNull { it.velocityFirstEditMillis }),
        )
    }

    /**
     * Donde cae una velocidad dentro del rango medido, de 0 (la mas lenta) a 1 (la mas rapida).
     *
     * Sirve para colorear el perfil. Se normaliza contra lo medido EN ESTE perfil y no contra
     * una escala fija: un arroyo de montana y un rio de llanura no comparten rango, y una
     * escala absoluta pintaria uno entero de azul y el otro entero de rojo, que es no decir
     * nada. Cuando todas las velocidades son iguales devuelve 0,5 --el medio-- en vez de
     * dividir por cero.
     */
    fun velocityFraction(v: Double?, min: Double?, max: Double?): Double? {
        if (v == null || min == null || max == null) return null
        if (!v.isFinite()) return null
        val rango = max - min
        if (rango <= 1e-12) return 0.5
        return ((v - min) / rango).coerceIn(0.0, 1.0)
    }

    /** El minimo y el maximo de las velocidades medidas, para normalizar el color. */
    fun velocityRange(g: StreamGauging): Pair<Double, Double>? {
        val v = g.bins.mapNotNull { it.velocityMps?.takeIf { x -> x.isFinite() } }
        if (v.isEmpty()) return null
        return v.min() to v.max()
    }

    /**
     * Ajusta la lista de tramos a un ancho o intervalo nuevos SIN perder lo ya medido.
     *
     * Cambiar el ancho a mitad de un aforo es normal --se mide la orilla de verdad despues de
     * empezar-- y lo ultimo que puede hacer la app es tirar treinta verticales por eso. Los
     * tramos que siguen existiendo conservan su dato; si sobran, se recortan por el final; si
     * faltan, se anaden vacios. Se avisa aparte de cuantos se pierden: recortar en silencio
     * seria peor que no poder recortar.
     */
    fun resize(bins: List<GaugingBin>, n: Int): List<GaugingBin> = when {
        n <= 0 -> emptyList()
        bins.size == n -> bins
        bins.size > n -> bins.take(n)
        else -> bins + List(n - bins.size) { GaugingBin() }
    }

    /** Cuantos tramos con datos se perderian al reducir la tabla a n. */
    fun wouldLose(bins: List<GaugingBin>, n: Int): Int =
        if (n >= bins.size) 0 else bins.drop(max(n, 0)).count { !it.isEmpty }
}
