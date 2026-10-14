package cl.umag.glaciertemp.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Calibracion de la bateria y de los canales analogicos de la placa con MUCHOS puntos.
 *
 * La placa guarda una recta por dos puntos (valor real, cuenta del ADC) por canal: V1/V2 para
 * la bateria y A01/A02, A11/A12... para los pines del header. Con los comandos de siempre
 * cada punto es una sola lectura, asi que la recta hereda entero el error de esas dos. Aqui
 * se juntan todas las lecturas que haga falta, se ajusta una recta por minimos cuadrados, se
 * ve cuanto se separan los puntos de ella (RMSE) y se mandan a la placa dos puntos DE LA
 * RECTA, con su cuenta: `A01=2.000>258`. Esa forma con '>' la entiende el firmware 3.16
 * (protocolo [PAIR_PROTOCOL]); uno anterior la acepta sin quejarse y guarda la cuenta que
 * lee en ese momento, por eso no se le manda.
 */
object BoardCalibration {

    /** Protocolo desde el que la placa guarda el par dado con `=valor>cuenta`. */
    const val PAIR_PROTOCOL = 8

    /**
     * Lo que guarda la placa: milesimas en un int de 16 bits. Un valor fuera de esto daria
     * la vuelta en la EEPROM y la recta guardada no se pareceria a la ajustada.
     */
    const val MAX_ABS_REAL = 32.767

    /** El ADC del ATmega328P es de 10 bits. */
    const val MAX_RAW = 1023

    /**
     * Un canal calibrable: el comando que lo lee y el prefijo de sus dos puntos.
     *
     * [unit] solo se conoce para la bateria. En los pines del header la placa guarda
     * "milivoltios" pero son milesimas de lo que uno quiera --metros de agua, por ejemplo--,
     * asi que la unidad la pone quien calibra.
     */
    data class Channel(val key: String, val label: String, val readCommand: String,
                       val setPrefix: String, val unit: String?)

    val VOLTAGE = Channel("V", "Voltage", "V", "V", "V")

    fun analog(pin: Int): Channel = Channel("A$pin", "A$pin", "A$pin", "A$pin", null)

    /**
     * Bateria siempre; un pin solo si esta compilado. Se lee de la firma del log --bit
     * 0x0020 para A0, uno mas por pin, como en el descriptor GTFW-- porque un pin que no
     * esta contesta "Pin disabled at compile", y ofrecerlo para eso no ayuda a nadie.
     */
    fun channels(signature: Int?): List<Channel> =
        listOf(VOLTAGE) + (0..3).filter { signature != null && signature and (0x0020 shl it) != 0 }
            .map(::analog)

    data class Point(val real: Double, val raw: Int)

    /**
     * La recta `raw = intercept + slope * real`.
     *
     * EN ESTE SENTIDO: la cuenta en funcion del valor real. El valor real lo fija quien
     * calibra (una marca en el cable, un voltimetro) y lo que lleva el ruido es la lectura
     * del ADC; los minimos cuadrados suponen el error en la variable de la izquierda. Es la
     * misma eleccion que la calibracion de la dilucion de sal.
     *
     * [rmse] va en UNIDADES REALES --el residuo en cuentas dividido por la pendiente-- porque
     * es lo que se quiere saber: cuantos metros, o voltios, se equivoca la recta.
     */
    data class Fit(val slope: Double, val intercept: Double, val rmse: Double, val n: Int) {
        fun rawAt(real: Double): Double = intercept + slope * real
        fun realAt(raw: Double): Double = (raw - intercept) / slope
    }

    /** Null con menos de dos valores reales DISTINTOS o con pendiente nula. */
    fun fit(points: List<Point>): Fit? {
        val n = points.size
        if (n < 2) return null
        val mx = points.sumOf { it.real } / n
        val my = points.sumOf { it.raw.toDouble() } / n
        val sxx = points.sumOf { (it.real - mx) * (it.real - mx) }
        if (sxx <= 0.0) return null
        val sxy = points.sumOf { (it.real - mx) * (it.raw - my) }
        val slope = sxy / sxx
        if (slope == 0.0) return null
        val intercept = my - slope * mx
        val ss = points.sumOf { val r = it.raw - (intercept + slope * it.real); r * r }
        return Fit(slope, intercept, sqrt(ss / n) / abs(slope), n)
    }

    /**
     * La cuenta que contesta la placa: el numero entre parentesis al final de la linea.
     * `V` da "1270mV (712)" y `A0` da "Depth [m]:0.534V (96)". Se toma el ULTIMO parentesis
     * con solo cifras, porque el nombre del canal puede llevar los suyos.
     */
    fun rawFromReply(reply: String): Int? =
        Regex("""\((-?\d+)\)""").findAll(reply).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** Uno de los dos puntos que se mandan a la placa, ya redondeado a lo que ella guarda. */
    data class BoardPoint(val real: Double, val raw: Int) {
        /** Milesimas, que es lo que la placa guarda y lo que contesta. */
        val thousandths: Long get() = (real * 1000).roundToLong()
    }

    sealed class Plan {
        data class Ok(val first: BoardPoint, val second: BoardPoint) : Plan()
        data class Problem(val message: String) : Plan()
    }

    /**
     * Dos puntos de la recta para la placa: en los extremos de lo medido.
     *
     * EN LOS EXTREMOS porque la placa redondea --la cuenta a entero, el valor a milesimas--
     * y el error que eso mete en la pendiente es ese redondeo dividido por la distancia entre
     * los puntos: cuanto mas separados, menos se nota. Fuera del rango medido la recta es una
     * extrapolacion, asi que tampoco tiene sentido irse mas alla.
     *
     * Primero se redondea la CUENTA y despues se calcula el valor real que le corresponde en
     * la recta, y no al reves: la cuenta solo puede ser entera, el valor tiene tres
     * decimales, y asi el punto queda sobre la recta salvo media milesima.
     */
    fun plan(fit: Fit, points: List<Point>): Plan {
        if (points.size < 2) return Plan.Problem("At least two points are needed.")
        val reales = listOf(points.minOf { it.real }, points.maxOf { it.real })
        val pts = reales.map { r ->
            val c = fit.rawAt(r).roundToInt()
            if (c < 0 || c > MAX_RAW) {
                return Plan.Problem("The fitted line reaches $c counts at " +
                    "${Decimals.fixed(r, 3)}, outside the ADC's 0–$MAX_RAW.")
            }
            BoardPoint((fit.realAt(c.toDouble()) * 1000).roundToLong() / 1000.0, c)
        }
        if (pts[0].raw == pts[1].raw) {
            return Plan.Problem("Both ends of the line fall on the same count: the range " +
                "measured is too small for the ADC to tell apart.")
        }
        pts.firstOrNull { abs(it.real) > MAX_ABS_REAL }?.let {
            return Plan.Problem("${Decimals.fixed(it.real, 3)} is out of range: the board " +
                "stores thousandths in 16 bits, so values must stay within ±$MAX_ABS_REAL. " +
                "Use a larger unit.")
        }
        return Plan.Ok(pts[0], pts[1])
    }

    /** `A01=2.000>258`, `V2=1.450>800`. [index] es 1 o 2. */
    fun command(channel: Channel, index: Int, p: BoardPoint): String =
        "${channel.setPrefix}$index=${Decimals.fixed(p.real, 3)}>${p.raw}"

    /**
     * Si la placa guardo exactamente lo mandado. Contesta "2000mV->258" con lo que QUEDO en
     * la EEPROM, que es lo que se compara: un firmware que entendiera mal el comando no da
     * error, da otro numero.
     */
    fun confirms(reply: String, p: BoardPoint): Boolean {
        val m = Regex("""(-?\d+)mV->(-?\d+)""").find(reply) ?: return false
        return m.groupValues[1].toLongOrNull() == p.thousandths &&
               m.groupValues[2].toIntOrNull() == p.raw
    }

    /**
     * Lo que se copia: los pares separados por comas, listos para una hoja de calculo, y
     * debajo el ajuste. La cabecera dice que canal, de que placa y cuando, por lo mismo que
     * los informes de los sensores del telefono: pegado en un correo, sin eso no significa
     * nada una semana despues.
     */
    fun clipboard(channel: Channel, name: String?, points: List<Point>, fit: Fit?,
                  plan: Plan?, board: String?, cuando: String): String = buildString {
        val titulo = channel.label + (name?.let { " ($it)" } ?: "")
        appendLine("GlacioTools — calibration $titulo  $cuando")
        board?.let { appendLine("Board: $it") }
        appendLine("real,raw")
        points.forEach { appendLine("${num(it.real)},${it.raw}") }
        if (fit != null) {
            appendLine("Fit (least squares, raw on real): raw = ${num(fit.intercept)} + " +
                       "${num(fit.slope)} * real")
            appendLine("Inverse: real = (raw - ${num(fit.intercept)}) / ${num(fit.slope)}")
            append("RMSE: ${num(fit.rmse)}" + (channel.unit?.let { " $it" } ?: " (real units)") +
                   ", n = ${fit.n}")
            if (plan is Plan.Ok) {
                appendLine()
                append("Board points: ${command(channel, 1, plan.first)}, " +
                       command(channel, 2, plan.second))
            }
        } else {
            append("Fit: needs at least two different real values")
        }
    }

    /** Sin ceros de relleno, pero sin perder decimales que importen. */
    private fun num(v: Double): String {
        val s = Decimals.fixed(v, 6)
        return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }
}
