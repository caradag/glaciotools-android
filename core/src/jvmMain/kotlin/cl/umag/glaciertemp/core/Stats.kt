package cl.umag.glaciertemp.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter

/**
 * Resumen numerico de un canal, calculado sobre los registros CRUDOS.
 *
 * No se saca de la serie del grafico a proposito: esa esta reducida a unos cientos de
 * columnas, y aunque la reduccion por min/max conserve los extremos, no conserva ni cuando
 * ocurrieron ni cuantas lecturas validas hubo.
 */
data class ChannelStats(
    val channel: String,
    val decimals: Int,
    /** Lecturas validas; [missing] son las que el sensor no pudo entregar. */
    val count: Int,
    val missing: Int,
    val min: Double,
    val minAt: LocalDateTime,
    val max: Double,
    val maxAt: LocalDateTime,
    val mean: Double,
    val first: LocalDateTime,
    val last: LocalDateTime,
) {
    val span: Duration get() = Duration.between(first, last)

    fun format(v: Double): String = LogDecoder.formatValue(v, decimals)
}

/**
 * El reloj de la placa frente al del telefono.
 *
 * Un desfase no impide medir, pero deja MAL cada marca de tiempo del log, y eso no se nota
 * hasta que alguien compara los datos con otra fuente meses despues. Por eso se avisa en
 * cuanto se conecta y no solo cuando el reloj esta descaradamente mal.
 */
object BoardClock {

    /**
     * Desde cuanto desfase se avisa.
     *
     * Estaba en 5 s con el argumento de que por debajo es deriva normal de un DS3231. Es
     * falso: un DS3231 deriva unos 2 ppm, es decir un minuto AL ANO, asi que tres segundos
     * ya son meses de deriva o un ajuste que se hizo mal. Y sobre todo, ese desfase es el
     * que corrige las marcas de tiempo del log: si existe, hay que verlo antes de decidir si
     * sincronizar --que lo borra-- o descargar primero. Cualquier desfase distinto de cero
     * se anuncia.
     */
    const val WARN_SECONDS = 1L

    private val STAMP = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2}):(\d{2})""")

    /** Lee la hora de la respuesta a TIME, que llega como "Time: 2026-09-04 18:30:00". */
    fun parse(reply: String): java.time.LocalDateTime? {
        val m = STAMP.find(reply) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured
        return runCatching {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(),
                                       h.toInt(), mi.toInt(), se.toInt())
        }.getOrNull()
    }

    /**
     * El desfase en la unidad que le toca. Stats.formatSpan no sirve aqui: esta pensado para
     * despliegues y por debajo de un minuto devuelve "0 min", que para un reloj es
     * precisamente el caso interesante.
     */
    fun format(seconds: Long): String {
        val s = kotlin.math.abs(seconds)
        return when {
            s < 60 -> plural(s, "second", "seconds")
            s < 3600 -> {
                val m = s / 60
                val r = s % 60
                if (r == 0L) plural(m, "minute", "minutes")
                else "${plural(m, "minute", "minutes")} ${plural(r, "second", "seconds")}"
            }
            else -> {
                val h = s / 3600
                val m = (s % 3600) / 60
                if (m == 0L) plural(h, "hour", "hours")
                else "${plural(h, "hour", "hours")} ${plural(m, "minute", "minutes")}"
            }
        }
    }

    private fun plural(n: Long, one: String, many: String) =
        if (n == 1L) "$n $one" else "$n $many"

    /** Segundos que la placa va adelantada (positivo) o atrasada (negativo). */
    fun driftSeconds(board: java.time.LocalDateTime, phone: java.time.LocalDateTime): Long =
        java.time.Duration.between(phone, board).seconds

    /**
     * El aviso, o null si el desfase es despreciable. Se redacta en la unidad que
     * corresponde: "4023 seconds off" no dice nada, "1 hour 7 minutes" si.
     */
    fun warning(driftSeconds: Long): String? {
        if (kotlin.math.abs(driftSeconds) < WARN_SECONDS) return null
        val ahead = driftSeconds > 0
        val magnitude = format(kotlin.math.abs(driftSeconds))
        return "Board clock is $magnitude ${if (ahead) "ahead of" else "behind"} this phone. " +
               "Download the log BEFORE synchronizing: setting the clock erases this offset " +
               "and the timestamps can no longer be corrected."
    }
}

object Stats {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun formatInstant(t: LocalDateTime): String = DATE.format(t)

    /**
     * Duracion en la unidad que le corresponde a su tamano. Un despliegue de dos meses
     * medido en horas no se lee; uno de seis horas medido en dias tampoco.
     */
    fun formatSpan(first: LocalDateTime, last: LocalDateTime): String {
        val d = Duration.between(first, last)
        if (d.isNegative || d.isZero) return "instantaneous"
        val totalMinutes = d.toMinutes()

        // Menos de dos dias: horas y minutos, que es la escala de una prueba de banco.
        if (totalMinutes < 48 * 60) {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            return when {
                h == 0L -> "$m min"
                m == 0L -> plural(h, "hour", "hours")
                else -> "${plural(h, "hour", "hours")} $m min"
            }
        }

        // Menos de dos meses: dias, y horas solo si aportan algo.
        val days = d.toDays()
        if (days < 60) {
            val h = d.minusDays(days).toHours()
            return if (h == 0L) plural(days, "day", "days")
                   else "${plural(days, "day", "days")} ${plural(h, "hour", "hours")}"
        }

        // A partir de ahi, meses y dias de calendario: los meses no duran todos lo mismo,
        // asi que se cuentan sobre las fechas y no dividiendo por 30.
        val p = Period.between(first.toLocalDate(), last.toLocalDate())
        val months = p.years * 12 + p.months
        val restDays = p.days
        val m = plural(months.toLong(), "month", "months")
        return if (restDays == 0) m else "$m and ${plural(restDays.toLong(), "day", "days")}"
    }

    private fun plural(n: Long, one: String, many: String) =
        if (n == 1L) "$n $one" else "$n $many"

    /** Estadisticas de un canal, o null si no hay ninguna lectura valida. */
    fun channel(records: List<Record>, signature: Int, channel: String): ChannelStats? {
        val fields = LogFormat.fields(signature)
        val idx = fields.indexOfFirst { it.name == channel }
        require(idx >= 0) { "unknown channel: $channel" }
        if (records.isEmpty()) return null

        var min = Double.MAX_VALUE; var minAt = records.first().time
        var max = -Double.MAX_VALUE; var maxAt = records.first().time
        var sum = 0.0; var n = 0; var missing = 0

        for (r in records) {
            val v = r.values.getOrNull(idx)
            if (v == null || v.isNaN()) { missing++; continue }
            n++; sum += v
            if (v < min) { min = v; minAt = r.time }
            if (v > max) { max = v; maxAt = r.time }
        }
        if (n == 0) return null

        return ChannelStats(
            channel = channel, decimals = fields[idx].decimals,
            count = n, missing = missing,
            min = min, minAt = minAt, max = max, maxAt = maxAt,
            mean = sum / n,
            // El periodo lo marcan los registros, tengan o no lectura valida en este canal:
            // el logger estuvo funcionando igual.
            first = records.first().time, last = records.last().time,
        )
    }
}
