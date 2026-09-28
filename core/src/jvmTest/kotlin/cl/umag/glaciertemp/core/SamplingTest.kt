package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.test.*

class SamplingTest {

    private val t0 = LocalDateTime.of(2026, 9, 1, 0, 0)

    /** Serie de [n] muestras cada [cada] segundos, con huecos en los indices indicados. */
    private fun serie(n: Int, cada: Long, saltos: Map<Int, Long> = emptyMap()): List<Record> {
        var t = t0
        return (0 until n).map { i ->
            if (i > 0) t = t.plusSeconds(saltos[i] ?: cada)
            Record(t, listOf(1.5, 2.0))
        }
    }

    @Test
    fun `el intervalo tipico es la mediana y no la media`() {
        // Una noche entera parada entre muestras de 10 min dispara la media; la mediana no
        // se entera, y es cuando hay huecos cuando hace falta el valor.
        val r = serie(200, 600, mapOf(100 to 36000L))
        assertEquals(600, Sampling.typicalIntervalSeconds(r))
    }

    @Test
    fun `cuenta las muestras que faltan con el intervalo tipico`() {
        // Un salto de 10 intervalos deja 9 muestras sin registrar.
        val r = serie(100, 600, mapOf(50 to 6000L))
        val s = assertNotNull(Sampling.of(r))
        assertEquals(600, s.typicalSeconds)
        assertEquals(6000, s.maxGapSeconds)
        assertEquals(9, s.missing)
        assertEquals(100, s.present)
        assertEquals(1, s.gaps)
    }

    @Test
    fun `la cobertura sale de las presentes frente a las esperadas`() {
        val r = serie(90, 600, mapOf(45 to 6600L))   // faltan 10
        val s = assertNotNull(Sampling.of(r))
        assertEquals(10, s.missing)
        assertEquals(100, s.expected)
        assertEquals(90.0, s.coverage, 0.01)
    }

    @Test
    fun `una serie sin huecos cubre el cien por cien`() {
        val s = assertNotNull(Sampling.of(serie(500, 600)))
        assertEquals(0, s.missing)
        assertEquals(0, s.gaps)
        assertEquals(100.0, s.coverage, 0.01)
    }

    @Test
    fun `el jitter normal no cuenta como hueco`() {
        // Un despertar que se retrasa un segundo no es una muestra perdida.
        val r = serie(100, 600, mapOf(30 to 601L, 60 to 599L))
        val s = assertNotNull(Sampling.of(r))
        assertEquals(0, s.gaps)
        assertEquals(0, s.missing)
    }

    @Test
    fun `sin suficientes muestras no se inventa una estadistica`() {
        assertNull(Sampling.of(emptyList()))
        assertNull(Sampling.of(serie(1, 600)))
    }

    @Test
    fun `el grafico corta la linea en un hueco de tiempo`() {
        // Es lo que se veia mal: la placa paso la noche sin pila y el grafico unia los dos
        // extremos con una recta, como si hubiera medido.
        val r = serie(300, 600, mapOf(150 to 36000L))
        val serie = Chart.series(r, 0x100F, "Volt", maxColumns = 480)
        assertTrue(serie.gaps.isNotEmpty(), "no marco el corte del apagon")
    }

    @Test
    fun `la posicion horizontal es proporcional al tiempo`() {
        // Con el eje por indice, una noche entera ocupaba lo mismo que una muestra.
        val r = serie(11, 600, mapOf(5 to 36000L))
        val s = Chart.series(r, 0x100F, "Volt", maxColumns = 480)
        val x = s.xFractions
        val antes = x[4] - x[3]          // un intervalo normal
        val salto = x[5] - x[4]          // las diez horas paradas
        assertTrue(salto > antes * 10, "el hueco no ocupa mas ancho: $antes vs $salto")
    }
}
