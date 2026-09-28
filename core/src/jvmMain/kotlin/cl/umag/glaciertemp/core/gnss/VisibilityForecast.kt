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

    /**
     * Quita del catalogo los satelites cuya identidad no cuadra con lo que emite el cielo.
     *
     * POR QUE AFECTA AL RECUENTO. Un satelite que el catalogo nombra con un numero que hoy
     * emite otro es, casi siempre, uno retirado: sigue en orbita y sigue en el fichero, pero
     * ningun receptor lo va a rastrear. Contarlo infla la grafica justo donde uno se apoya
     * para decidir a que hora medir. Medido en Chile: C14, un BeiDou-2 MEO retirado, se
     * contaba como visible durante medio dia.
     *
     * LO QUE ESTA SUPOSICION PUEDE FALLAR. La evidencia es "el receptor vio ese numero en
     * otro sitio del cielo", y eso demuestra que el catalogo se equivoca de numero, no que
     * la nave este muerta: podria seguir emitiendo con OTRO numero, y entonces quitarla hace
     * que falte una. Se acepta porque contar de mas es el error que enganna --promete
     * satelites que no van a estar-- y contar de menos solo hace elegir una ventana algo
     * mejor de lo previsto.
     */
    fun withoutMismatched(tles: List<Tle>, excluded: Set<String>): List<Tle> =
        if (excluded.isEmpty()) tles
        else tles.filterNot { t -> t.svid?.let { key(t.constellation, it) in excluded } ?: false }

    /** La clave con la que se recuerda un satelite descartado. */
    fun key(c: Constellation, svid: Int) = "${c.name}:$svid"

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
