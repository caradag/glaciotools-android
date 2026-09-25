package cl.umag.glaciertemp.core.gnss

/** Cuantos satelites por instante, para una constelacion. */
data class Series(val constellation: Constellation?, val counts: IntArray) {
    /** null en [constellation] significa el total de las habilitadas. */
    override fun equals(other: Any?) = other is Series &&
        constellation == other.constellation && counts.contentEquals(other.counts)
    override fun hashCode() = 31 * (constellation?.hashCode() ?: 0) + counts.contentHashCode()
}

data class Forecast(
    val startMillis: Long,
    val stepMillis: Long,
    val series: List<Series>,
) {
    val steps: Int get() = series.firstOrNull()?.counts?.size ?: 0
    fun millisAt(i: Int): Long = startMillis + i * stepMillis
    /** El mejor momento del periodo segun el total. */
    fun bestIndex(): Int? = series.firstOrNull { it.constellation == null }
        ?.counts?.withIndex()?.maxByOrNull { it.value }?.index
}

/**
 * Cuantos satelites habra a cada hora del dia, por constelacion.
 *
 * El proposito es elegir CUANDO medir, no apuntar una antena: por eso lo que se devuelve es
 * un recuento y no posiciones. Un error de un par de grados mueve la hora de salida de un
 * satelite unos cuatro minutos, asi que la forma de la curva --donde estan los picos-- es
 * robusta aunque los elementos orbitales lleven semanas guardados.
 */
object VisibilityForecast {

    fun compute(
        tles: List<Tle>,
        latDeg: Double,
        lonDeg: Double,
        startMillis: Long,
        stepMillis: Long = 15 * 60_000L,
        steps: Int = 96,                       // 24 h a pasos de 15 min
        maskDeg: Double = 10.0,
        enabled: Set<Constellation> = Constellation.entries.toSet(),
    ): Forecast {
        val usados = tles.filter { it.constellation in enabled }
        val porConst = enabled.sortedBy { it.ordinal }.associateWith { IntArray(steps) }
        val total = IntArray(steps)

        for (i in 0 until steps) {
            val t = startMillis + i * stepMillis
            for (sat in usados) {
                if (SkyModel.skyPos(sat, t, latDeg, lonDeg).elevationDeg >= maskDeg) {
                    porConst[sat.constellation]?.let { it[i]++ }
                    total[i]++
                }
            }
        }

        val series = porConst.map { (c, v) -> Series(c, v) } +
            // El total solo tiene sentido si hay mas de una: con una sola seria la misma
            // linea dibujada dos veces.
            if (enabled.size > 1) listOf(Series(null, total)) else emptyList()
        return Forecast(startMillis, stepMillis, series)
    }
}
