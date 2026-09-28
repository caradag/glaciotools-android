package cl.umag.glaciertemp.core

import java.time.Duration

/**
 * Que tan completo esta el registro.
 *
 * Un log con huecos se ve igual de bien en un grafico que uno completo, y la diferencia
 * importa: una serie a la que le falta una noche entera no puede promediarse como si nada.
 * Estas cifras salen de las marcas de tiempo, sin necesidad de preguntarle nada a la placa.
 */
data class SamplingStats(
    /** Intervalo mas frecuente entre muestras consecutivas, en segundos. */
    val typicalSeconds: Long,
    /** El salto mas grande encontrado. */
    val maxGapSeconds: Long,
    /** Cuantas muestras faltan, contadas con el intervalo tipico. */
    val missing: Long,
    /** Muestras presentes. */
    val present: Long,
    /** Cuantos tramos sin datos hay. */
    val gaps: Int,
) {
    /** Muestras que habria si no faltara ninguna. */
    val expected: Long get() = present + missing

    /** Porcentaje del periodo cubierto por datos. */
    val coverage: Double get() = if (expected == 0L) 0.0 else 100.0 * present / expected
}

object Sampling {

    /**
     * Intervalo tipico: la MEDIANA de los saltos, no la media.
     *
     * La media la arruina un solo hueco largo -- una noche sin pila entre miles de muestras
     * de diez minutos la dispara-- y precisamente cuando hay huecos es cuando hace falta el
     * valor. La mediana no se entera de los extremos.
     */
    fun typicalIntervalSeconds(records: List<Record>): Long {
        if (records.size < 2) return 0
        val deltas = LongArray(records.size - 1) {
            Duration.between(records[it].time, records[it + 1].time).seconds
        }
        deltas.sort()
        return deltas[deltas.size / 2].coerceAtLeast(0)
    }

    fun of(records: List<Record>): SamplingStats? {
        if (records.size < 2) return null
        val typical = typicalIntervalSeconds(records)
        if (typical <= 0) return null

        val umbral = typical * Chart.GAP_FACTOR
        var maxGap = 0L
        var faltan = 0L
        var huecos = 0
        for (i in 0 until records.size - 1) {
            val d = Duration.between(records[i].time, records[i + 1].time).seconds
            if (d > maxGap) maxGap = d
            if (d > umbral) {
                huecos++
                // Las que cabrian en ese salto, descontando la que si esta al otro lado.
                faltan += (d / typical) - 1
            }
        }
        return SamplingStats(
            typicalSeconds = typical,
            maxGapSeconds = maxGap,
            missing = faltan.coerceAtLeast(0),
            present = records.size.toLong(),
            gaps = huecos,
        )
    }
}
