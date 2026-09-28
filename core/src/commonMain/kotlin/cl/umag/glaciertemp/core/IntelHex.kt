package cl.umag.glaciertemp.core

/** Resultado de leer una captura LOGH: los bytes reconstruidos y el signature declarado. */
data class Capture(val data: ByteArray, val signature: Int?, val badLines: Int)

/**
 * Lee el Intel HEX que emite LOGH. La captura es autodescriptiva: la cabecera declara el
 * signature del build que ESCRIBIO el log, asi que el layout se recupera del propio fichero.
 *
 * Se validan los checksums por linea, que es el motivo de usar Intel HEX y no un volcado
 * crudo: un caracter perdido en la linea serie se detecta en vez de decodificarse en silencio.
 */
object IntelHex {

    fun parse(text: String): Capture {
        val mem = HashMap<Int, Byte>()
        var base = 0
        var signature: Int? = null
        var bad = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (signature == null && line.contains("signature", ignoreCase = true)) {
                val i = line.indexOf("0x", ignoreCase = true)
                if (i >= 0) {
                    signature = line.substring(i + 2).takeWhile { it.isLetterOrDigit() }
                        .toIntOrNull(16)
                }
            }
            if (!line.startsWith(":")) continue
            val bytes = hexOrNull(line.substring(1))
            if (bytes == null || bytes.size < 5) { bad++; continue }
            if (bytes.sumOf { it.toInt() and 0xFF } and 0xFF != 0) { bad++; continue }

            val n = bytes[0].toInt() and 0xFF
            val addr = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            when (bytes[3].toInt() and 0xFF) {
                0x04 -> base = (((bytes[4].toInt() and 0xFF) shl 8) or
                                 (bytes[5].toInt() and 0xFF)) shl 16
                0x00 -> for (k in 0 until n) mem[base + addr + k] = bytes[4 + k]
                0x01 -> return finish(mem, signature, bad)
            }
        }
        return finish(mem, signature, bad)
    }

    private fun finish(mem: Map<Int, Byte>, sig: Int?, bad: Int): Capture {
        if (mem.isEmpty()) return Capture(ByteArray(0), sig, bad)
        val top = mem.keys.max()
        // Los huecos se rellenan con 0xFF, que es el valor de la flash borrada.
        return Capture(ByteArray(top + 1) { mem[it] ?: 0xFF.toByte() }, sig, bad)
    }

    private fun hexOrNull(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val v = s.substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: return null
            out[i] = v.toByte()
        }
        return out
    }
}
