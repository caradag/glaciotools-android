package cl.umag.glaciertemp.core

import kotlin.test.*

class LogbTest {

    private fun frame(idx: Int, data: ByteArray, corrupt: Boolean = false): ByteArray {
        val crc = if (corrupt) Logb.crc16(data) xor 0xFFFF else Logb.crc16(data)
        val out = java.io.ByteArrayOutputStream()
        out.write(0xAA); out.write(0x55)
        out.write(idx and 0xFF); out.write((idx shr 8) and 0xFF)
        out.write(data.size and 0xFF); out.write((data.size shr 8) and 0xFF)
        out.write(data)
        out.write(crc and 0xFF); out.write((crc shr 8) and 0xFF)
        return out.toByteArray()
    }

    @Test fun `el CRC16 CCITT-FALSE coincide con el vector conocido`() {
        // "123456789" -> 0x29B1, el vector estandar de CRC-16/CCITT-FALSE.
        assertEquals(0x29B1, Logb.crc16("123456789".toByteArray()))
    }

    @Test fun `la cabecera se parsea`() {
        val h = assertNotNull(Logb.parseHeader(
            "LOGB begin sig=0x141F rec=18 from=10 to=19 blocks=1 blocksize=256"))
        assertEquals(0x141F, h.signature)
        assertEquals(18, h.recordBytes)
        assertEquals(10L, h.from); assertEquals(19L, h.to)
        assertEquals(180L, h.payloadBytes)
    }

    @Test fun `el lector reensambla aunque los bytes lleguen de a uno`() {
        // Es exactamente lo que hace BLE: parte los bloques por donde cae el MTU.
        val payload = ByteArray(300) { (it % 251).toByte() }
        val stream = java.io.ByteArrayOutputStream().apply {
            write("LOGB begin sig=0x100F rec=12 from=0 to=24 blocks=2 blocksize=256\n".toByteArray())
            write(frame(0, payload.copyOfRange(0, 256)))
            write(frame(1, payload.copyOfRange(256, 300)))
            write("LOGB end\n".toByteArray())
        }.toByteArray()

        val reader = LogbReader()
        val events = ArrayList<LogbEvent>()
        for (b in stream) events += reader.feed(byteArrayOf(b))

        val header = assertNotNull(events.filterIsInstance<LogbEvent.Header>().firstOrNull()).header
        val blocks = events.filterIsInstance<LogbEvent.Block>()
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.crcOk })
        assertTrue(events.any { it is LogbEvent.End })
        val (data, bad) = Logb.assemble(header, blocks)
        assertTrue(bad.isEmpty())
        assertContentEquals(payload, data)
    }

    @Test fun `un bloque con CRC malo se senala para repetirlo`() {
        val payload = ByteArray(64) { it.toByte() }
        val stream = java.io.ByteArrayOutputStream().apply {
            write("LOGB begin sig=0x100F rec=12 from=0 to=4 blocks=1 blocksize=256\n".toByteArray())
            write(frame(0, payload, corrupt = true))
            write("LOGB end\n".toByteArray())
        }.toByteArray()
        val blocks = LogbReader().feed(stream).filterIsInstance<LogbEvent.Block>()
        assertEquals(1, blocks.size)
        assertFalse(blocks[0].crcOk)
    }
}
