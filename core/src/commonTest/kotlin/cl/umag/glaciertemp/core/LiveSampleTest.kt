package cl.umag.glaciertemp.core

import kotlin.test.*

class LiveSampleTest {

    private val fila = "LIVE 2026-09-13 10:22:31,4.12,+12.34,55.1"

    @Test
    fun `una fila se parte en hora y valores`() {
        val s = LiveSample.parse(fila, expectedValues = 3)
        assertNotNull(s)
        assertEquals("2026-09-13 10:22:31", s.time)
        assertEquals(listOf("4.12", "+12.34", "55.1"), s.values)
    }

    @Test
    fun `una fila cortada se descarta entera`() {
        // Por radio pasa: la linea llega a medias. Los campos que SI llegaron son correctos,
        // y esa es justamente la trampa -- ensenarlos corre las etiquetas un puesto y el
        // resultado parece un dato bueno. Lo mismo que hace LOGB con un bloque sin CRC.
        assertNull(LiveSample.parse("LIVE 2026-09-13 10:22:31,4.12,+12.34", 3))
        assertNull(LiveSample.parse("LIVE 2026-09-13 10:22:31,4.12,+12.34,55.1,9.9", 3))
        assertNull(LiveSample.parse("LIVE ,4.12,+12.34,55.1", 3))
    }

    @Test
    fun `las lineas de control no son muestras`() {
        assertNull(LiveSample.parse("LIVE begin every 1000 ms", 3))
        assertNull(LiveSample.parse("LIVE end", 3))
        assertNull(LiveSample.parse("LIVE timeout", 3))
        assertTrue(LiveSample.isStart("LIVE begin every 1000 ms"))
        assertTrue(LiveSample.isEnd("LIVE end"))
        assertTrue(LiveSample.isEnd("LIVE timeout"))
        assertTrue(LiveSample.isTimeout("LIVE timeout"))
        assertFalse(LiveSample.isTimeout("LIVE end"))
    }

    @Test
    fun `el NaN de un sensor mudo pasa tal cual`() {
        // No se convierte a numero en ningun momento: la placa ya decidio como se escribe
        // cada valor, y volver a formatearlo aqui solo anade una forma de equivocarse.
        val s = LiveSample.parse("LIVE 2026-09-13 10:22:31,4.12,NaN,55.1", 3)
        assertEquals(listOf("4.12", "NaN", "55.1"), assertNotNull(s).values)
    }

    @Test
    fun `las columnas son las que dice la firma del log`() {
        // El contrato entero: la app no aprende un formato nuevo, aplica a los valores los
        // campos que ya conoce. Si esto se rompe, las etiquetas dejan de corresponder.
        val sig = 0x1007          // Volt + Temp + RH, version de formato 1
        val campos = LogFormat.fields(sig)
        val s = LiveSample.parse(fila, campos.size)
        assertNotNull(s)
        assertEquals(listOf("Volt", "Temp", "RH"), campos.map { it.name })
        assertEquals(campos.size, s.values.size)
    }

    @Test
    fun `cada canal ensena su unidad`() {
        // Un numero suelto en la pantalla no dice si son grados o voltios, y el CSV no
        // lleva unidades porque sus columnas son nombres pelados.
        assertEquals("V", LogFormat.unitOf("Volt"))
        assertEquals("°C", LogFormat.unitOf("Temp"))
        assertEquals("°C", LogFormat.unitOf("HAtemp"))
        assertEquals("°C", LogFormat.unitOf("DS0"))
        assertEquals("%", LogFormat.unitOf("RH"))
        assertEquals("V", LogFormat.unitOf("A0"))
        // Un canal que no se conoce no se inventa una unidad.
        assertEquals("", LogFormat.unitOf("Loquesea"))
    }

    @Test
    fun `otra linea cualquiera del terminal no se confunde con una muestra`() {
        assertNull(LiveSample.parse("fw=3.4 proto=5", 3))
        assertNull(LiveSample.parse("LOGB end", 3))
        assertNull(LiveSample.parse("", 3))
    }
}
