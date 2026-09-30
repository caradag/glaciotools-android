package cl.umag.glaciertemp.core.geomag

/**
 * Instantes <-> anos decimales, en UTC.
 *
 * Los modelos magneticos se indexan por ano decimal: 2025.5 es la mitad de 2025. Se cuenta
 * sobre la longitud REAL de cada ano (365 o 366 dias), como el software de WMM. Escrito sin
 * java.time porque vive en commonMain; el calendario civil sale del algoritmo de H. Hinnant
 * (days_from_civil), exacto para cualquier fecha del calendario gregoriano proleptico.
 */
object DecimalYear {
    private const val DIA = 86_400_000L

    /** Dias desde 1970-01-01 del 1 de enero de ese ano. */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** Ano civil (UTC) de un instante. */
    fun yearOf(epochMillis: Long): Int {
        val dias = epochMillis.floorDiv(DIA)
        var y = (1970 + dias / 365.2425).toInt()
        while (daysFromCivil(y, 1, 1) > dias) y--
        while (daysFromCivil(y + 1, 1, 1) <= dias) y++
        return y
    }

    fun fromEpochMillis(epochMillis: Long): Double {
        val y = yearOf(epochMillis)
        val inicio = daysFromCivil(y, 1, 1) * DIA
        val fin = daysFromCivil(y + 1, 1, 1) * DIA
        return y + (epochMillis - inicio).toDouble() / (fin - inicio)
    }

    fun toEpochMillis(year: Double): Long {
        val y = kotlin.math.floor(year).toInt()
        val inicio = daysFromCivil(y, 1, 1) * DIA
        val fin = daysFromCivil(y + 1, 1, 1) * DIA
        return inicio + ((year - y) * (fin - inicio)).toLong()
    }

    private val FECHA = Regex("""^\s*(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?\s*$""")

    /**
     * "AAAA-MM-DD" o "AAAA-MM-DD HH:MM", en UTC. Null si no es una fecha real: el 31 de
     * abril no existe y no se corre al 1 de mayo en silencio.
     */
    fun parseUtc(text: String): Long? {
        val m = FECHA.matchEntire(text) ?: return null
        val (y, mo, d) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        val hh = m.groupValues[4].toIntOrNull() ?: 0
        val mm = m.groupValues[5].toIntOrNull() ?: 0
        if (mo !in 1..12 || hh > 23 || mm > 59) return null
        val diasMes = (daysFromCivil(if (mo == 12) y + 1 else y, if (mo == 12) 1 else mo + 1, 1) -
                       daysFromCivil(y, mo, 1)).toInt()
        if (d !in 1..diasMes) return null
        return epochMillisOf(y, mo, d, hh + mm / 60.0)
    }

    /** Instante UTC de una fecha civil (mes 1-12), a medianoche mas las horas indicadas. */
    fun epochMillisOf(year: Int, month: Int, day: Int, hours: Double = 0.0): Long =
        daysFromCivil(year, month, day) * DIA + (hours * 3_600_000).toLong()
}
