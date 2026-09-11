package cl.umag.glaciertemp.transport

import java.io.InputStream
import java.io.OutputStream

/**
 * Transporte sobre un par de streams. Se usa contra tools/fake_glaciertemp.py --stdio,
 * lo que permite probar toda la cadena -- comandos, LOGB, fragmentacion, decodificacion --
 * sin red, sin emulador y sin hardware.
 */
class PipeTransport(
    private val input: InputStream,
    private val output: OutputStream,
) : Transport {

    private var open = false
    override val isOpen: Boolean get() = open

    override fun open() { open = true }

    override fun write(data: ByteArray) {
        output.write(data); output.flush()
    }

    override fun read(timeoutMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        // available() no bloquea; se sondea hasta el plazo para imitar un puerto serie.
        while (System.currentTimeMillis() < deadline) {
            val n = input.available()
            if (n > 0) {
                val buf = ByteArray(minOf(n, 8192))
                val r = input.read(buf)
                return if (r <= 0) ByteArray(0) else buf.copyOf(r)
            }
            Thread.sleep(5)
        }
        return ByteArray(0)
    }

    override fun close() { open = false }
}
