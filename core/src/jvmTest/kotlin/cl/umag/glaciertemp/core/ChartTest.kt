package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.test.*

class ChartTest {

    private val sig = 0x100F   // Volt, Temp, RH, HAtemp

    private fun recs(n: Int, f: (Int) -> Double?): List<Record> {
        val t0 = LocalDateTime.of(2025, 11, 24, 12, 0)
        return (0 until n).map { i ->
            Record(t0.plusSeconds(i * 600L), listOf(f(i), 1.0, 2.0, 3.0))
        }
    }

    @Test fun `la reduccion conserva los extremos en vez de promediarlos`() {
        // Un pico aislado entre 1000 puntos debe sobrevivir: promediar lo borraria.
        val r = recs(1000) { if (it == 500) 99.0 else 1.0 }
        val s = Chart.series(r, sig, "Volt", maxColumns = 50)
        assertTrue(s.samples.size <= 50)
        assertEquals(99.0, s.samples.maxOf { it.hi })
        assertEquals(1.0, s.samples.minOf { it.lo })
    }

    @Test fun `sin reduccion cuando caben todos los puntos`() {
        val s = Chart.series(recs(30) { it.toDouble() }, sig, "Volt", maxColumns = 480)
        assertEquals(30, s.samples.size)
    }

    @Test fun `los tramos sin lectura valida quedan como huecos`() {
        // Sin esto el grafico uniria los extremos con una recta que sugiere datos falsos.
        val r = recs(60) { if (it in 20..39) null else 5.0 }
        val s = Chart.series(r, sig, "Volt", maxColumns = 60)
        assertTrue(s.gaps.isNotEmpty(), "no se detecto el hueco")
        assertEquals(40, s.samples.size)
    }

    @Test fun `una serie sin ningun dato valido queda vacia`() {
        assertTrue(Chart.series(recs(10) { null }, sig, "Volt").isEmpty)
    }

    @Test fun `una serie constante recibe un rango vertical utilizable`() {
        val s = Chart.series(recs(10) { 7.0 }, sig, "Volt")
        assertTrue(s.yMax > s.yMin, "el rango no puede ser de altura cero")
    }

    @Test fun `el rango deja margen sobre los datos`() {
        val s = Chart.series(recs(10) { it.toDouble() }, sig, "Volt")
        assertTrue(s.yMin < 0.0 && s.yMax > 9.0)
    }

    @Test fun `las marcas verticales caen en valores redondos`() {
        val t = Chart.yTicks(0.0, 10.0)
        assertTrue(t.isNotEmpty())
        assertTrue(t.all { it.value % 2.0 < 1e-9 || it.value % 2.5 < 1e-9 })
        assertTrue(t.all { it.value in 0.0..10.0 })
    }

    @Test fun `el eje temporal usa horas en tramos cortos y fecha en los largos`() {
        val t0 = LocalDateTime.of(2025, 11, 24, 12, 0)
        assertEquals("12:00", Chart.timeTicks(t0, t0.plusHours(6)).first())
        assertTrue(Chart.timeTicks(t0, t0.plusDays(30)).first().count { it == '/' } == 2)
    }

    @Test fun `un canal inexistente se rechaza`() {
        assertFailsWith<IllegalArgumentException> { Chart.series(recs(5) { 1.0 }, sig, "DS0") }
    }

    @Test fun `isReduced distingue reduccion real de puntos perdidos por huecos`() {
        // 240 puntos en 480 columnas no se reducen aunque falten algunos por NaN.
        val conHuecos = Chart.series(recs(240) { if (it % 80 == 0) null else 1.0 },
                                     sig, "Volt", maxColumns = 480)
        assertFalse(conHuecos.isReduced, "no hubo reduccion, solo huecos")
        assertTrue(conHuecos.samples.size < 240)

        val reducida = Chart.series(recs(5000) { it.toDouble() }, sig, "Volt", maxColumns = 480)
        assertTrue(reducida.isReduced)
        assertTrue(reducida.bucket > 1)
    }
}
