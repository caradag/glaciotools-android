package cl.umag.glaciertemp.core

import kotlin.test.*

class LogFormatTest {

    @Test fun `el build estandar reproduce el registro historico de 12 bytes`() {
        assertEquals(12, LogFormat.recordBytes(0x100F))
        assertEquals(listOf("Volt", "Temp", "RH", "HAtemp"),
            LogFormat.fields(0x100F).map { it.name })
    }

    @Test fun `los bits de conteo distinguen numeros de sonda que antes colisionaban`() {
        // El bug que el signature existe para atrapar: tres sondas y cinco producian
        // registros de 18 y 22 bytes bajo un mismo signature de "DS presente".
        assertEquals(1, LogFormat.dsCount(0x101F))
        assertEquals(3, LogFormat.dsCount(0x141F))
        assertEquals(8, LogFormat.dsCount(0x1E1F))
        assertEquals(14, LogFormat.recordBytes(0x101F))
        assertEquals(18, LogFormat.recordBytes(0x141F))
        assertEquals(28, LogFormat.recordBytes(0x1E1F))
    }

    @Test fun `una sonda codifica cero en los bits de conteo`() {
        // Asi los logs escritos antes de que el conteo existiera no se marcan como ajenos.
        assertEquals(0x101F and LogFormat.DS_MASK, 0)
    }

    @Test fun `la version de formato se lee del nibble alto`() {
        assertEquals(LogFormat.FORMAT_VERSION, LogFormat.formatVersion(0x100F))
        assertNotEquals(LogFormat.FORMAT_VERSION, LogFormat.formatVersion(0x2000))
        // La EEPROM en blanco lee 0xFFFF, que no puede ser un signature legal.
        assertNotEquals(LogFormat.FORMAT_VERSION, LogFormat.formatVersion(0xFFFF))
    }

    @Test fun `los analogicos van despues de las sondas`() {
        val names = LogFormat.fields(0x11FF).map { it.name }
        assertTrue(names.indexOf("DS0") < names.indexOf("A0"))
    }

    @Test
    fun `la firma se traduce a los canales que trae`() {
        // 0x1007 es la que reporto una placa real: version 1, con Volt, Temp y RH.
        assertEquals(
            listOf("Battery voltage", "Air temperature (HDC1080)",
                   "Relative humidity (HDC1080)"),
            LogFormat.describe(0x1007))
        assertEquals("3 channels \u00b7 10 bytes per record", LogFormat.summary(0x1007))
    }

    @Test
    fun `las sondas y las entradas analogicas tambien se nombran`() {
        assertTrue(LogFormat.describe(0x141F).any { it.startsWith("Probe 2") })
        assertTrue(LogFormat.describe(0x11FF).any { it == "Analog input A3" })
    }

    @Test
    fun `la unidad convierte a los segundos que entiende la placa`() {
        assertEquals(21600L, TimeUnit.HOURS.toSeconds(6))
        assertEquals(600L, TimeUnit.MINUTES.toSeconds(10))
        assertEquals(90L, TimeUnit.SECONDS.toSeconds(90))
    }
}
