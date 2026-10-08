package cl.umag.glaciertemp.transport

import kotlin.test.*

class SerialSettingsTest {

    @Test fun `por defecto es la GlacierTemp`() {
        val s = SerialSettings()
        assertTrue(s.isGlacierTemp)
        assertEquals("115200 8N1", s.summary)
        assertEquals("\n", s.lineEnding.bytes)
        assertEquals("9600 7E2", s.copy(baud = 9600, dataBits = 7, parity = SerialSettings.Parity.EVEN,
            stopBits = SerialSettings.StopBits.TWO).summary)
    }

    @Test fun `la orden lleva el fin de linea elegido`() {
        val escrito = java.io.ByteArrayOutputStream()
        val t = object : Transport {
            override val isOpen = true
            override fun open() {}
            override fun close() {}
            @Synchronized override fun write(data: ByteArray) { escrito.write(data) }
            override fun read(timeoutMs: Int): ByteArray { Thread.sleep(minOf(timeoutMs, 5).toLong()); return ByteArray(0) }
        }
        val s = DeviceSession(t)
        s.exchange("AT", quietMs = 20, terminator = SerialSettings.LineEnding.CRLF.bytes)
        s.exchange("HELP", quietMs = 20)                 // por defecto, LF
        s.exchange("X", quietMs = 20, terminator = SerialSettings.LineEnding.NONE.bytes)
        assertEquals("AT\r\nHELP\nX", String(escrito.toByteArray()))
        s.cerrar()
    }
}
