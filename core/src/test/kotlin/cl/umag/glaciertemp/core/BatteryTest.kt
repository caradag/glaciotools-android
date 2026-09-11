package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.*

class BatteryTest {

    private val sig = 0x100F                    // Volt, Temp, RH, HAtemp
    private val t0 = LocalDateTime.of(2025, 11, 24, 12, 0)

    /** Registros con un voltaje de celda dado en mV para cada indice. */
    private fun recs(n: Int, stepSeconds: Long = 3600, mv: (Int) -> Double?) =
        (0 until n).map { i ->
            Record(t0.plusSeconds(i * stepSeconds),
                   listOf(mv(i)?.div(1000.0), 1.0, 2.0, 3.0))
        }

    @Test fun `la curva alcalina coincide con la tabla del firmware`() {
        val a = BatteryType.ALKALINE_ENERGIZER
        assertEquals(0.0, a.capacityPercent(900.0))
        assertEquals(100.0, a.capacityPercent(1600.0))
        assertEquals(27.0, a.capacityPercent(1200.0), 0.01)
        assertEquals(65.0, a.capacityPercent(1400.0), 0.01)
        // Fuera de rango satura, no diverge: por eso es tabla y no polinomio.
        assertEquals(0.0, a.capacityPercent(500.0))
        assertEquals(100.0, a.capacityPercent(2000.0))
    }

    @Test fun `la curva es monotona creciente`() {
        for (t in BatteryType.entries) {
            var prev = -1.0
            var mv = 850.0
            while (mv <= 1850.0) {
                val p = t.capacityPercent(mv)
                assertTrue(p >= prev, "${t.name} no es monotona en $mv mV")
                prev = p; mv += 5.0
            }
        }
    }

    @Test fun `estima consumo y autonomia con una descarga lineal`() {
        // De 1500 mV a 1400 mV en 10 dias: 88% -> 65%, o sea 2,3 puntos por dia.
        val n = 240
        val r = recs(n) { 1500.0 - 100.0 * it / (n - 1) }
        val e = Battery.estimate(r, sig, BatteryType.ALKALINE_ENERGIZER) as BatteryEstimate.Available
        assertEquals(10.0, e.spanDays, 0.1)
        assertEquals(2.3, e.percentPerDay, 0.15)
        // 1200 mAh * 2,3 %/dia / 100
        assertEquals(27.6, e.mahPerDay, 2.0)
        assertEquals(65.0, e.percentNow, 1.0)
        assertEquals(65.0 / 2.3, e.daysRemaining, 3.0)
        assertFalse(e.lowConfidence)
    }

    @Test fun `la capacidad personalizada escala el consumo en mAh`() {
        val r = recs(240) { 1500.0 - 100.0 * it / 239.0 }
        val base = Battery.estimate(r, sig, BatteryType.CUSTOM, capacityMah = 1000)
                as BatteryEstimate.Available
        val doble = Battery.estimate(r, sig, BatteryType.CUSTOM, capacityMah = 2000)
                as BatteryEstimate.Available
        assertEquals(base.percentPerDay, doble.percentPerDay, 1e-9)
        assertEquals(2.0, doble.mahPerDay / base.mahPerDay, 1e-6)
    }

    @Test fun `el ajuste resiste el ruido termico del voltaje`() {
        // Dos puntos sueltos podrian caer en un dia frio y otro templado y dar una
        // pendiente inventada; la regresion sobre toda la serie no.
        val n = 240
        val rnd = java.util.Random(7)
        val r = recs(n) { 1500.0 - 100.0 * it / (n - 1) + rnd.nextGaussian() * 15.0 }
        val e = Battery.estimate(r, sig, BatteryType.ALKALINE_ENERGIZER) as BatteryEstimate.Available
        assertTrue(abs(e.percentPerDay - 2.3) < 0.6, "pendiente ${e.percentPerDay}")
    }

    @Test fun `el paquete de varias celdas se divide entre el numero de celdas`() {
        val r = recs(240) { 2 * (1500.0 - 100.0 * it / 239.0) }   // dos celdas en serie
        val e = Battery.estimate(r, sig, BatteryType.ALKALINE_ENERGIZER, cellCount = 2)
                as BatteryEstimate.Available
        assertEquals(65.0, e.percentNow, 1.0)
    }

    @Test fun `sin canal de voltaje no hay estimacion`() {
        // 0x100E = Temp, RH, HAtemp pero sin Volt.
        val r = (0 until 100).map { Record(t0.plusSeconds(it * 3600L), listOf(1.0, 2.0, 3.0)) }
        val e = Battery.estimate(r, 0x100E, BatteryType.ALKALINE_ENERGIZER)
        assertEquals(BatteryUnknown.NO_VOLTAGE_CHANNEL,
                     (e as BatteryEstimate.Unavailable).reason)
    }

    @Test fun `con pocos puntos o poco tiempo se dice que no se puede`() {
        val pocos = Battery.estimate(recs(4) { 1500.0 }, sig, BatteryType.ALKALINE_ENERGIZER)
        assertEquals(BatteryUnknown.TOO_FEW_POINTS, (pocos as BatteryEstimate.Unavailable).reason)

        val corto = Battery.estimate(recs(20, stepSeconds = 600) { 1500.0 - it * 0.5 },
                                     sig, BatteryType.ALKALINE_ENERGIZER)
        assertEquals(BatteryUnknown.TOO_SHORT, (corto as BatteryEstimate.Unavailable).reason)
    }

    @Test fun `una bateria que no se descarga no produce una cifra inventada`() {
        // Voltaje plano o subiendo: bateria nueva, panel solar, o la celda recuperandose
        // del frio. Extrapolar aqui daria una autonomia absurda.
        val plano = Battery.estimate(recs(240) { 1500.0 }, sig, BatteryType.ALKALINE_ENERGIZER)
        assertEquals(BatteryUnknown.NOT_DISCHARGING, (plano as BatteryEstimate.Unavailable).reason)

        val subiendo = Battery.estimate(recs(240) { 1400.0 + it * 0.2 }, sig,
                                        BatteryType.ALKALINE_ENERGIZER)
        assertEquals(BatteryUnknown.NOT_DISCHARGING,
                     (subiendo as BatteryEstimate.Unavailable).reason)
    }

    @Test fun `el litio se marca como poco fiable por su meseta plana`() {
        val r = recs(240) { 1650.0 - 100.0 * it / 239.0 }
        val e = Battery.estimate(r, sig, BatteryType.LITHIUM_ENERGIZER_ULTIMATE)
                as BatteryEstimate.Available
        assertTrue(e.lowConfidence, "el litio debe advertir de su curva plana")
    }
}
