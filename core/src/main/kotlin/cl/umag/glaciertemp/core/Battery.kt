package cl.umag.glaciertemp.core

import java.time.Duration

/**
 * Modelos de bateria para estimar consumo y autonomia a partir del canal Volt del log.
 *
 * La curva alcalina es la MISMA tabla que usa `voltageToCapacity()` en el firmware
 * (Power.ino): asi la app y la placa no dan porcentajes distintos para el mismo voltaje.
 * Son celdas AAA a bajo drenaje y ~21 degC, con el 0% fijado en 900 mV porque por debajo
 * el convertidor boost ya no sostiene 3,3 V.
 */
enum class BatteryType(
    val label: String,
    val nominalMah: Int,
    /** Puntos (mV, % restante) en orden creciente de voltaje. */
    val curveMv: IntArray,
    val curvePct: IntArray,
    /** Cierto cuando la meseta es tan plana que el voltaje predice mal el estado. */
    val flatCurve: Boolean,
) {
    ALKALINE_ENERGIZER(
        "Alcalina Energizer", 1200,
        intArrayOf(900, 1000, 1050, 1100, 1150, 1200, 1250, 1300, 1350, 1400, 1450, 1500, 1550, 1600),
        intArrayOf(0, 5, 9, 14, 20, 27, 35, 44, 54, 65, 77, 88, 95, 100),
        flatCurve = false),

    ALKALINE_DURACELL(
        "Alcalina Duracell", 1200,
        intArrayOf(900, 1000, 1050, 1100, 1150, 1200, 1250, 1300, 1350, 1400, 1450, 1500, 1550, 1600),
        intArrayOf(0, 5, 9, 14, 20, 27, 35, 44, 54, 65, 77, 88, 95, 100),
        flatCurve = false),

    /**
     * Li-FeS2 (L92). La meseta va de ~1,45 V a ~1,7 V durante casi toda la descarga, asi
     * que el voltaje distingue mal el 80% del 30%: la estimacion solo se vuelve fiable
     * cerca del final. La app lo advierte en vez de dar una cifra con falsa precision.
     */
    LITHIUM_ENERGIZER_ULTIMATE(
        "Energizer Ultimate Lithium", 1250,
        intArrayOf(900, 1100, 1250, 1350, 1400, 1450, 1500, 1550, 1600, 1650, 1700, 1750, 1800),
        intArrayOf(0, 3, 8, 15, 25, 40, 55, 70, 82, 90, 95, 98, 100),
        flatCurve = true),

    CUSTOM("Capacidad personalizada", 1200,
        intArrayOf(900, 1000, 1050, 1100, 1150, 1200, 1250, 1300, 1350, 1400, 1450, 1500, 1550, 1600),
        intArrayOf(0, 5, 9, 14, 20, 27, 35, 44, 54, 65, 77, 88, 95, 100),
        flatCurve = false);

    /** Porcentaje restante para un voltaje de celda en mV, interpolando la tabla. */
    fun capacityPercent(mv: Double): Double {
        if (mv <= curveMv.first()) return 0.0
        if (mv >= curveMv.last()) return 100.0
        for (i in 1 until curveMv.size) {
            if (mv <= curveMv[i]) {
                val v0 = curveMv[i - 1].toDouble(); val v1 = curveMv[i].toDouble()
                val p0 = curvePct[i - 1].toDouble(); val p1 = curvePct[i].toDouble()
                return p0 + (mv - v0) * (p1 - p0) / (v1 - v0)
            }
        }
        return 100.0
    }
}

/** Motivo por el que no se puede dar una estimacion. */
enum class BatteryUnknown { NO_VOLTAGE_CHANNEL, TOO_FEW_POINTS, TOO_SHORT, NOT_DISCHARGING }

sealed interface BatteryEstimate {
    data class Unavailable(val reason: BatteryUnknown) : BatteryEstimate
    data class Available(
        val type: BatteryType,
        val capacityMah: Int,
        val currentMv: Double,
        val percentNow: Double,
        val percentPerDay: Double,
        val mahPerDay: Double,
        val daysRemaining: Double,
        val spanDays: Double,
        val points: Int,
        /** La curva del tipo elegido es demasiado plana para fiarse del resultado. */
        val lowConfidence: Boolean,
    ) : BatteryEstimate
}

object Battery {

    /** Minimos para que la pendiente signifique algo y no sea ruido de medida. */
    const val MIN_POINTS = 8
    const val MIN_SPAN_DAYS = 1.0

    /**
     * Estima consumo medio y autonomia a partir del canal Volt.
     *
     * Se ajusta una recta al porcentaje restante frente al tiempo por minimos cuadrados,
     * en vez de tomar el primer y el ultimo punto: el voltaje de una alcalina sube y baja
     * con la temperatura, y dos puntos sueltos pueden caer justo en un dia frio y otro
     * templado, dando una pendiente inventada.
     *
     * [cellCount] es el numero de celdas en serie; el log guarda el voltaje del paquete.
     */
    fun estimate(
        records: List<Record>,
        signature: Int,
        type: BatteryType,
        capacityMah: Int = type.nominalMah,
        cellCount: Int = 1,
    ): BatteryEstimate {
        val fields = LogFormat.fields(signature)
        val idx = fields.indexOfFirst { it.name == "Volt" }
        if (idx < 0) return BatteryEstimate.Unavailable(BatteryUnknown.NO_VOLTAGE_CHANNEL)

        // El canal Volt esta en voltios (escala /1000 en el registro); a mV por celda.
        val pts = records.mapNotNull { r ->
            val v = r.values.getOrNull(idx) ?: return@mapNotNull null
            r.time to (v * 1000.0 / cellCount)
        }
        if (pts.size < MIN_POINTS) return BatteryEstimate.Unavailable(BatteryUnknown.TOO_FEW_POINTS)

        val t0 = pts.first().first
        val spanDays = Duration.between(t0, pts.last().first).seconds / 86400.0
        if (spanDays < MIN_SPAN_DAYS) return BatteryEstimate.Unavailable(BatteryUnknown.TOO_SHORT)

        // Regresion lineal de % restante contra dias.
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((time, mv) in pts) {
            val x = Duration.between(t0, time).seconds / 86400.0
            val y = type.capacityPercent(mv)
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val n = pts.size
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return BatteryEstimate.Unavailable(BatteryUnknown.TOO_SHORT)
        val slope = (n * sxy - sx * sy) / denom          // % por dia, negativo al descargar

        val percentPerDay = -slope
        // Una pendiente nula o positiva significa que no se esta descargando de forma
        // medible: bateria recien puesta, panel solar, o la celda recuperandose del frio.
        if (percentPerDay <= 0.0) return BatteryEstimate.Unavailable(BatteryUnknown.NOT_DISCHARGING)

        val currentMv = pts.last().second
        val percentNow = type.capacityPercent(currentMv)
        val mahPerDay = capacityMah * percentPerDay / 100.0
        return BatteryEstimate.Available(
            type = type, capacityMah = capacityMah, currentMv = currentMv,
            percentNow = percentNow, percentPerDay = percentPerDay, mahPerDay = mahPerDay,
            daysRemaining = percentNow / percentPerDay,
            spanDays = spanDays, points = n, lowConfidence = type.flatCurve,
        )
    }
}
