package cl.umag.glaciertemp.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Un punto de la serie ya reducida. [lo]/[hi] coinciden salvo donde hubo reduccion. */
data class Sample(val time: LocalDateTime, val lo: Double, val hi: Double)

/** Serie lista para dibujar: puntos, rango vertical y marcas de los ejes. */
data class Series(
    val channel: String,
    val samples: List<Sample>,
    val yMin: Double,
    val yMax: Double,
    /** Indices donde la serie se corta: sin lectura valida, o un salto en el tiempo. */
    val gaps: List<Int>,
    val bucket: Int = 1,          // registros por columna; 1 = sin reduccion
) {
    /**
     * Posicion horizontal de cada punto entre 0 y 1, PROPORCIONAL AL TIEMPO.
     *
     * Antes se dibujaba por indice, y entonces una noche entera sin registrar ocupaba en
     * pantalla lo mismo que un intervalo de muestreo: el hueco desaparecia visualmente
     * aunque la linea estuviera cortada.
     */
    val xFractions: List<Float> by lazy {
        if (samples.isEmpty()) return@lazy emptyList()
        val t0 = samples.first().time
        val span = Duration.between(t0, samples.last().time).seconds.toDouble()
        if (span <= 0) samples.indices.map { 0f }
        else samples.map { (Duration.between(t0, it.time).seconds / span).toFloat() }
    }
    val isEmpty: Boolean get() = samples.isEmpty()
    /** Cierto solo si hubo reduccion de verdad, no si faltan puntos por huecos. */
    val isReduced: Boolean get() = bucket > 1
}

data class Tick(val value: Double, val label: String)

object Chart {

    /**
     * Cuantas veces el intervalo tipico tiene que superar un salto para considerarlo hueco.
     * Con 1,5 se tolera el jitter normal del RTC y de los despertares, y se marca cualquier
     * muestra que falte de verdad.
     */
    const val GAP_FACTOR = 1.5


    /**
     * Construye la serie de un canal, reduciendola a [maxColumns] puntos.
     *
     * La reduccion es por MIN/MAX de cada columna, no por promedio: con 699.050 registros
     * y unos cientos de pixeles, promediar borraria justo los picos que interesa ver.
     * Los tramos sin lectura valida se marcan como huecos para no unirlos con una recta
     * que sugiera datos que no existen.
     */
    fun series(records: List<Record>, signature: Int, channel: String,
               maxColumns: Int = 480): Series {
        val fields = LogFormat.fields(signature)
        val idx = fields.indexOfFirst { it.name == channel }
        require(idx >= 0) { "unknown channel: $channel" }
        if (records.isEmpty()) return Series(channel, emptyList(), 0.0, 1.0, emptyList())

        val bucket = ceil(records.size.toDouble() / maxColumns).toInt().coerceAtLeast(1)
        val samples = ArrayList<Sample>()
        val gaps = ArrayList<Int>()

        // Un salto en el tiempo mayor que este umbral es un hueco de verdad: el logger
        // estuvo parado. Se compara contra el intervalo TIPICO de los propios datos, no
        // contra el que tenga configurado la placa hoy, que puede haber cambiado.
        val typical = Sampling.typicalIntervalSeconds(records)
        val gapThreshold = typical * GAP_FACTOR

        var i = 0
        var lastTime: LocalDateTime? = null
        while (i < records.size) {
            val end = minOf(i + bucket, records.size)
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var any = false
            for (k in i until end) {
                val v = records[k].values.getOrNull(idx) ?: continue
                any = true
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (any) {
                val t = records[i].time
                // El corte se anota ANTES de anadir el punto, para que la linea no una dos
                // instantes separados por un apagon.
                val prev = lastTime
                if (prev != null && typical > 0 &&
                    Duration.between(prev, t).seconds > gapThreshold) {
                    gaps.add(samples.size)
                }
                samples.add(Sample(t, lo, hi))
                lastTime = records[minOf(end, records.size) - 1].time
            } else if (samples.isNotEmpty()) {
                gaps.add(samples.size)     // el corte va antes del proximo punto valido
            }
            i = end
        }
        if (samples.isEmpty()) return Series(channel, emptyList(), 0.0, 1.0, emptyList(), bucket)

        var yMin = samples.minOf { it.lo }
        var yMax = samples.maxOf { it.hi }
        if (yMin == yMax) { yMin -= 0.5; yMax += 0.5 }      // serie constante
        val margin = (yMax - yMin) * 0.08
        return Series(channel, samples, yMin - margin, yMax + margin, gaps, bucket)
    }

    /** Marcas del eje vertical en valores redondos (1, 2, 5 por decada). */
    fun yTicks(min: Double, max: Double, target: Int = 5): List<Tick> {
        if (max <= min) return emptyList()
        val raw = (max - min) / target
        val mag = 10.0.pow(floor(log10(raw)))
        val step = when {
            raw / mag < 1.5 -> 1.0
            raw / mag < 3.5 -> 2.0
            raw / mag < 7.5 -> 5.0
            else -> 10.0
        } * mag
        val first = ceil(min / step) * step
        val out = ArrayList<Tick>()
        var v = first
        while (v <= max + step * 1e-9) {
            // El cero que llega por acumulacion o por ceil de un negativo puede ser -0.0, y
            // "%.0f" lo escribe "-0" en el eje.
            if (abs(v) < step * 1e-9) v = 0.0
            out.add(Tick(v, formatTick(v, step)))
            v += step
        }
        return out
    }

    private fun formatTick(v: Double, step: Double): String {
        val decimals = when {
            step >= 10 -> 0
            step >= 1 -> if (abs(v % 1.0) < 1e-9) 0 else 1
            step >= 0.1 -> 1
            step >= 0.01 -> 2
            else -> 3
        }
        return String.format("%.${decimals}f", v)
    }

    /** Una marca del eje de tiempo: donde cae y que se escribe. */
    data class TimeTick(val time: LocalDateTime, val label: String)

    /** Pasos posibles de menos de un dia, en segundos. */
    private val PASOS_CORTOS = longArrayOf(
        1, 2, 5, 10, 15, 30,
        60, 2 * 60, 5 * 60, 10 * 60, 15 * 60, 30 * 60,
        3600, 2 * 3600, 3 * 3600, 6 * 3600, 12 * 3600)
    private val PASOS_DIAS = intArrayOf(1, 2, 5, 10, 15)
    private val PASOS_MESES = intArrayOf(1, 2, 3, 6, 12, 24, 60, 120)

    /**
     * Marcas del eje de tiempo en instantes REDONDOS: minutos, horas, dias o meses enteros.
     *
     * Las anteriores ([timeTicks]) repartian el tramo en partes iguales a partir del primer
     * registro, y salian marcas como "29/09 21:40" y "01/10 11:10": correctas y casi inutiles,
     * porque para saber donde cae la medianoche habia que hacer la cuenta. Aqui se elige el
     * paso mas pequeno de una lista de pasos redondos que no da mas de [target] marcas, y las
     * marcas caen en sus multiplos: las 12:00, las 18:00, el dia 1.
     *
     * La etiqueta lleva la fecha solo donde hace falta: en la primera marca y en las que caen
     * a medianoche. Las demas, solo la hora. Asi caben cinco en el ancho de un telefono y no
     * hay ninguna ambigua.
     */
    fun roundTimeTicks(from: LocalDateTime, to: LocalDateTime, target: Int = 5): List<TimeTick> {
        if (!to.isAfter(from) || target < 1) return emptyList()
        val span = Duration.between(from, to).seconds.coerceAtLeast(1)

        PASOS_CORTOS.firstOrNull { span.toDouble() / it <= target }?.let { paso ->
            val dia0 = from.toLocalDate().atStartOfDay()
            val desde = Duration.between(dia0, from).seconds
            var t = dia0.plusSeconds(ceilDiv(desde, paso) * paso)
            val out = ArrayList<TimeTick>()
            val conSegundos = paso < 60
            val varios = from.toLocalDate() != to.toLocalDate()
            while (!t.isAfter(to)) {
                val medianoche = t.toLocalTime() == java.time.LocalTime.MIDNIGHT
                val patron = when {
                    medianoche && !conSegundos -> "dd/MM"
                    out.isEmpty() && varios -> if (conSegundos) "dd/MM HH:mm:ss" else "dd/MM HH:mm"
                    conSegundos -> "HH:mm:ss"
                    else -> "HH:mm"
                }
                out += TimeTick(t, DateTimeFormatter.ofPattern(patron).format(t))
                t = t.plusSeconds(paso)
            }
            return out
        }

        val dias = span / 86_400.0
        PASOS_DIAS.firstOrNull { dias / it <= target }?.let { paso ->
            // Dias 1, 1+paso, 1+2*paso... de cada mes: el 1 y el 15 se leen; un multiplo
            // contado desde 1970 cae en cualquier dia y no le dice nada a nadie.
            val out = ArrayList<TimeTick>()
            var d = from.toLocalDate()
            if (from.toLocalTime() != java.time.LocalTime.MIDNIGHT) d = d.plusDays(1)
            val varios = from.year != to.year
            while (!d.atStartOfDay().isAfter(to)) {
                if ((d.dayOfMonth - 1) % paso == 0 && d.dayOfMonth <= 31 - paso / 2) {
                    val patron = if (out.isEmpty() && varios || d.dayOfYear == 1) "dd/MM/yy" else "dd/MM"
                    out += TimeTick(d.atStartOfDay(), DateTimeFormatter.ofPattern(patron).format(d))
                }
                d = d.plusDays(1)
            }
            return out
        }

        val meses = dias / 30.44
        val paso = PASOS_MESES.firstOrNull { meses / it <= target } ?: PASOS_MESES.last()
        val out = ArrayList<TimeTick>()
        var m = java.time.YearMonth.from(from)
        if (from != m.atDay(1).atStartOfDay()) m = m.plusMonths(1)
        val fmt = DateTimeFormatter.ofPattern(if (paso >= 12) "yyyy" else "MMM yy", java.util.Locale.ENGLISH)
        while (!m.atDay(1).atStartOfDay().isAfter(to)) {
            if ((m.year * 12 + m.monthValue - 1) % paso == 0)
                out += TimeTick(m.atDay(1).atStartOfDay(), fmt.format(m))
            m = m.plusMonths(1)
        }
        return out
    }

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

    /**
     * Marcas del eje horizontal. El formato se elige segun el tramo cubierto: para menos
     * de un dia bastan las horas, y para tramos largos hace falta la fecha.
     */
    fun timeTicks(from: LocalDateTime, to: LocalDateTime, target: Int = 4): List<String> {
        if (target <= 1) return emptyList()
        val span = Duration.between(from, to)
        val fmt = DateTimeFormatter.ofPattern(
            when {
                span.toHours() < 24 -> "HH:mm"
                span.toDays() < 7 -> "dd/MM HH:mm"
                else -> "dd/MM/yy"
            })
        return (0 until target).map { k ->
            fmt.format(from.plus(Duration.ofSeconds(span.seconds * k / (target - 1L))))
        }
    }
}
