package cl.umag.glaciertemp.core

/**
 * Formato del volcado binario LOGB.
 *
 *   texto   "LOGB begin sig=0x100F rec=12 from=0 to=99 blocks=5 blocksize=256"
 *   bloques  AA 55 <u16 idx LE> <u16 len LE> <len bytes> <u16 crc LE>
 *   texto   "LOGB end"
 *
 * El CRC por bloque es lo que permite reanudar una descarga cortada y detectar a un
 * modulo BLE que anuncia un MTU que no sostiene: el bloque se rechaza y se repite, en
 * vez de escribir un CSV con datos corruptos.
 */
data class LogbHeader(
    val signature: Int, val recordBytes: Int,
    val from: Long, val to: Long, val blocks: Int, val blockSize: Int,
) {
    val payloadBytes: Long get() = (to - from + 1) * recordBytes
}

sealed interface LogbEvent {
    data class Header(val header: LogbHeader) : LogbEvent
    data class Block(val index: Int, val data: ByteArray, val crcOk: Boolean) : LogbEvent
    data object End : LogbEvent
}

object Logb {

    const val SYNC0 = 0xAA.toByte()
    const val SYNC1 = 0x55.toByte()

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF. Barato de calcular en un AVR. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                      else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private val HEADER = Regex(
        """LOGB begin sig=0x([0-9A-Fa-f]+) rec=(\d+) from=(\d+) to=(\d+) blocks=(\d+) blocksize=(\d+)""")

    fun parseHeader(line: String): LogbHeader? = HEADER.find(line)?.let { m ->
        LogbHeader(
            signature = m.groupValues[1].toInt(16),
            recordBytes = m.groupValues[2].toInt(),
            from = m.groupValues[3].toLong(),
            to = m.groupValues[4].toLong(),
            blocks = m.groupValues[5].toInt(),
            blockSize = m.groupValues[6].toInt(),
        )
    }

    /**
     * Reensambla los bloques de una transferencia ya recibida entera. Devuelve el payload
     * y los indices de los bloques cuyo CRC no cuadra, que son los que hay que repetir.
     */
    fun assemble(header: LogbHeader, blocks: List<LogbEvent.Block>): Pair<ByteArray, List<Int>> {
        val out = ByteArray(header.payloadBytes.toInt())
        val bad = ArrayList<Int>()
        for (b in blocks) {
            if (!b.crcOk) { bad.add(b.index); continue }
            val off = b.index * header.blockSize
            b.data.copyInto(out, off, 0, minOf(b.data.size, out.size - off))
        }
        val received = blocks.filter { it.crcOk }.map { it.index }.toSet()
        for (i in 0 until header.blocks) if (i !in received && i !in bad) bad.add(i)
        return out to bad.sorted()
    }
}

/**
 * Maquina de estados incremental: se le entregan los bytes segun llegan -- fragmentados
 * por el MTU de BLE, que parte los bloques por cualquier sitio -- y emite eventos.
 */
class LogbReader {
    private val buf = java.io.ByteArrayOutputStream()
    private var header: LogbHeader? = null
    private var done = false

    fun feed(chunk: ByteArray): List<LogbEvent> {
        buf.write(chunk)
        val events = ArrayList<LogbEvent>()
        var bytes = buf.toByteArray()
        var consumed = 0

        while (!done) {
            if (header == null) {
                val nl = indexOf(bytes, consumed, '\n'.code.toByte())
                if (nl < 0) break
                val line = String(bytes, consumed, nl - consumed).trim()
                consumed = nl + 1
                val h = Logb.parseHeader(line)
                if (h != null) { header = h; events.add(LogbEvent.Header(h)) }
                continue
            }
            // Fin de la transferencia: la linea de texto "LOGB end".
            if (consumed + 2 <= bytes.size &&
                !(bytes[consumed] == Logb.SYNC0 && bytes[consumed + 1] == Logb.SYNC1)) {
                val nl = indexOf(bytes, consumed, '\n'.code.toByte())
                if (nl < 0) break
                val line = String(bytes, consumed, nl - consumed).trim()
                consumed = nl + 1
                if (line.startsWith("LOGB end")) { done = true; events.add(LogbEvent.End) }
                continue
            }
            if (consumed + 6 > bytes.size) break
            val idx = u16(bytes, consumed + 2)
            val len = u16(bytes, consumed + 4)
            val total = 6 + len + 2
            if (consumed + total > bytes.size) break
            val data = bytes.copyOfRange(consumed + 6, consumed + 6 + len)
            val crc = u16(bytes, consumed + 6 + len)
            events.add(LogbEvent.Block(idx, data, Logb.crc16(data) == crc))
            consumed += total
        }

        if (consumed > 0) {
            val rest = bytes.copyOfRange(consumed, bytes.size)
            buf.reset(); buf.write(rest)
        }
        return events
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun indexOf(b: ByteArray, from: Int, v: Byte): Int {
        for (i in from until b.size) if (b[i] == v) return i
        return -1
    }
}
