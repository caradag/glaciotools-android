package cl.umag.glaciertemp.core

import kotlin.test.*

/**
 * Las lineas de la captura continua, con el texto EXACTO que emite el firmware 3.10: se
 * sacaron compilando en el PC las mismas sentencias de Continuous.ino con lightOStream, que
 * es quien decide espacios y signos. Un parser probado contra texto inventado aprueba
 * aunque la placa escriba otra cosa.
 */
class ContCaptureTest {

    @Test fun `inicio con calentador y tamano de registro`() {
        val b = assertNotNull(ContCapture.parseBegin("CONT begin heater=on rec=16\r\n"))
        assertTrue(b.heater)
        assertEquals(16, b.recordBytes)
        assertEquals(5, ContCapture.valuesPerRecord(b.recordBytes))
        assertFalse(assertNotNull(ContCapture.parseBegin("CONT begin heater=off rec=14")).heater)
    }

    @Test fun `el estado que vale es el ultimo`() {
        val st = assertNotNull(ContCapture.parseStatus("CONT n=5 t=0s\nLIVE x\nCONT n=8412 t=63s\n"))
        assertEquals(8412L, st.records)
        assertEquals(63L, st.seconds)
        assertNull(ContCapture.parseStatus("CONT idle"))
    }

    @Test fun `fin con deriva negativa y positiva`() {
        val e = assertNotNull(ContCapture.parseEnd(
            "CONT end reason=stop n=8412 dur=1203456ms mean=143ms max=402ms drift=-4ms heater=on"))
        assertEquals("stop", e.reason)
        assertEquals(8412L, e.records)
        assertEquals(1203456L, e.durationMs)
        assertEquals(143L, e.meanMs)
        assertEquals(402L, e.maxMs)
        assertEquals(-4L, e.driftMs)
        assertTrue(e.heater)

        val t = assertNotNull(ContCapture.parseEnd(
            "CONT end reason=time n=0 dur=0ms mean=0ms max=402ms drift=7ms heater=off"))
        assertEquals("time", t.reason)
        assertEquals(7L, t.driftMs)
        assertFalse(t.heater)
    }

    @Test fun `las respuestas de rechazo y reposo se reconocen`() {
        assertTrue(ContCapture.needsEmptyLog("CONT needs an empty log: download it, then RC\n"))
        assertTrue(ContCapture.isIdle("CONT idle\r\n"))
        assertFalse(ContCapture.isIdle("CONT n=1 t=0s"))
    }

    @Test fun `el resumen dice ritmo deriva calentador y motivo`() {
        val s = ContCapture.summary(ContCapture.End("stop", 8412, 1203456, 143, 402, -4, true))
        assertEquals("8412 records in 20 min 03 s · every 143 ms on average, longest gap 402 ms" +
                     " · clock drift -4 ms · heater on · stopped on request", s)
        assertTrue(ContCapture.summary(ContCapture.End("full", 1, 0, 0, 0, 3, false))
            .contains("clock drift +3 ms"))
        assertEquals("45 s", ContCapture.duration(45_000))
    }
}
