package cl.umag.glaciertemp.core

import kotlin.test.*

class LogDecoderTest {

    @Test fun `el formateo replica el redondeo half-even de Python`() {
        // String.format de Java usa HALF_UP y daria 1.24 / 1.25 / 2.47 aqui, produciendo
        // un CSV distinto del que genera decode_logh.py.
        assertEquals("1.24", LogDecoder.formatValue(1235 / 1000.0, 2))
        assertEquals("-1.25", LogDecoder.formatValue(-1250 / 1000.0, 2))
        assertEquals("2.46", LogDecoder.formatValue(2465 / 1000.0, 2))
        assertEquals("NaN", LogDecoder.formatValue(null, 2))
    }

    @Test fun `los registros en blanco se omiten`() {
        val sig = 0x100F
        val rec = LogFormat.recordBytes(sig)
        val data = ByteArray(rec * 3)
        // registro 0: nunca escrito (0); registro 1: flash borrada (0xFF); registro 2: valido
        for (i in rec until 2 * rec) data[i] = 0xFF.toByte()
        data[2 * rec] = 0x10
        assertEquals(1, LogDecoder.decode(data, sig).size)
        assertEquals(2, LogDecoder.blankCount(data, sig))
    }

    @Test fun `INVALID y humedad negativa se decodifican como ausentes`() {
        val sig = 0x100F
        val d = ByteArray(12)
        d[0] = 0x10                                   // marca de tiempo no nula
        d[4] = 0x00; d[5] = 0x80.toByte()             // Volt = -32768 (INVALID)
        d[6] = 0x64; d[7] = 0x00                      // Temp = 100
        d[8] = 0xFF.toByte(); d[9] = 0xFF.toByte()    // RH = -1, centinela antiguo
        val r = LogDecoder.decode(d, sig).single()
        assertNull(r.values[0])
        assertEquals(1.0, r.values[1])
        assertNull(r.values[2])
    }
}
