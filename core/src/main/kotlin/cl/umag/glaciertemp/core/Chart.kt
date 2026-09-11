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
