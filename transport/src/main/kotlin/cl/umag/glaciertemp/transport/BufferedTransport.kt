package cl.umag.glaciertemp.transport

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Base para los transportes que reciben por CALLBACK y no por lectura bloqueante.
 *
 * BLE entrega por `onCharacteristicChanged` y USB por un hilo lector; ninguno de los dos
 * encaja con el `read(timeoutMs)` de [Transport] sin un buffer intermedio. Aqui vive ese
 * buffer, junto con el troceado de las escrituras, que es la otra cosa que ambos
 * comparten: un enlace BLE no acepta mas de `MTU-3` bytes por escritura.
 *
 * Se prueba sin Android ni radio: basta con una subclase que registre lo que se escribe
 * y llame a [onReceived] con lo que se quiera simular.
 */
abstract class BufferedTransport(
    /** Maximo por escritura al enlace. En BLE, `MTU-3`; en USB, el tamano del endpoint. */
    val writeChunkSize: Int,
    /**
     * Pausa entre trozos consecutivos. Un HM-10 pierde datos si se le escribe mas rapido
     * que su intervalo de conexion, y el sintoma es un comando truncado, no un error.
     */
    val writeGapMs: Long = 0L,
    /**
     * Tope de trozos pendientes de leer. Si se desborda no se corrompe la descarga en
     * silencio: los bloques afectados fallan el CRC y se reintentan, y [overflowed] queda
     * marcado para poder decirlo en vez de adivinarlo.
     */
    queueCapacity: Int = 8192,
) : Transport {

    private val incoming = LinkedBlockingQueue<ByteArray>(queueCapacity)

    @Volatile
    var overflowed = false
        private set

    /** La llama la subclase desde el hilo del callback. */
    protected fun onReceived(data: ByteArray) {
        if (data.isEmpty()) return
        if (!incoming.offer(data)) overflowed = true
    }

    protected fun clearBuffer() {
        incoming.clear()
        overflowed = false
    }

    override fun read(timeoutMs: Int): ByteArray {
        val first = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        // Una sola llamada devuelve TODO lo que ya llego. Devolver solo el primer trozo
        // convertiria una descarga de miles de fragmentos de 20 bytes en miles de vueltas
        // del bucle de lectura.
        val rest = ArrayList<ByteArray>()
        incoming.drainTo(rest)
        if (rest.isEmpty()) return first
        val total = first.size + rest.sumOf { it.size }
        val out = ByteArray(total)
        System.arraycopy(first, 0, out, 0, first.size)
        var at = first.size
        for (c in rest) { System.arraycopy(c, 0, out, at, c.size); at += c.size }
        return out
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(writeChunkSize, data.size - off)
            writeChunk(data.copyOfRange(off, off + n))
            off += n
            if (writeGapMs > 0 && off < data.size) Thread.sleep(writeGapMs)
        }
    }

    /** Entrega un trozo que ya cabe en una escritura del enlace. */
    protected abstract fun writeChunk(chunk: ByteArray)
}
