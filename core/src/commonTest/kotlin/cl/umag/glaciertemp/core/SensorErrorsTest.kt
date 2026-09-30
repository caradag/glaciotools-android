package cl.umag.glaciertemp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorErrorsTest {

    @Test fun `el nibble bajo es el codigo mas reciente`() {
        val log = SensorErrors.parse("0x3218", "1234")!!
        assertEquals(listOf(8, 1, 2, 3), log.codes)
        assertEquals(1234, log.failedAttempts)
    }

    @Test fun `los huecos vacios no son codigos`() {
        assertEquals(listOf(3), SensorErrors.parse("0x0003", "5")!!.codes)
        val limpio = SensorErrors.parse("0x0000", "0")!!
        assertTrue(limpio.isEmpty)
    }

    @Test fun `un firmware sin el registro no afirma que no haya errores`() {
        assertNull(SensorErrors.parse(null, null))
        assertNull(SensorErrors.parse("0x0003", null))
        assertNull(SensorErrors.parse("0xZZ", "1"))
        assertNull(SensorErrors.parse("0x12345", "1"))
    }

    @Test fun `el contador saturado se reconoce`() {
        assertTrue(SensorErrors.parse("0x0001", "65535")!!.saturated)
    }

    @Test fun `la tabla nombra el sensor de cada codigo`() {
        for (c in 1..4) assertTrue(SensorErrors.describe(c).startsWith("TMP119"))
        for (c in 5..6) assertTrue(SensorErrors.describe(c).startsWith("HDC1080"))
        assertTrue(SensorErrors.describe(7).startsWith("DS18B20"))
        assertTrue(SensorErrors.describe(9).contains("9"))
    }
}
