package cl.umag.glaciertemp.transport

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Transporte de depuracion: habla con tools/fake_glaciertemp.py por TCP. Es JVM puro,
 * asi que sirve tanto en el escritorio como dentro del emulador Android, donde el host
 * se alcanza en 10.0.2.2. Solo se compila en la variante de debug.
 */
class TcpTransport(private val host: String, private val port: Int) : Transport {

    private var socket: Socket? = null

    override val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * La direccion de destino, expuesta para poder comprobarla en un test.
     *
     * Existe por un bug real: escrito como `Socket().apply { connect(InetSocketAddress(host,
     * port), ...) }`, el `port` se resuelve a la propiedad `port` del propio Socket -- que
     * vale 0 mientras no esta conectado -- y no al parametro del constructor. El sintoma era
     * "failed to connect to /10.0.2.2 (port 0)". Nada de `apply` sobre un Socket aqui.
     */
    fun endpoint(): InetSocketAddress = InetSocketAddress(host, port)

    override fun open() {
        val s = Socket()
        s.connect(endpoint(), 5000)
        s.tcpNoDelay = true
        socket = s
    }

    override fun write(data: ByteArray) {
        val s = checkNotNull(socket) { "transporte no abierto" }
        s.getOutputStream().write(data)
        s.getOutputStream().flush()
    }

    override fun read(timeoutMs: Int): ByteArray {
        val s = checkNotNull(socket) { "transporte no abierto" }
        s.soTimeout = timeoutMs
        val buf = ByteArray(4096)
        return try {
            val n = s.getInputStream().read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        } catch (e: java.net.SocketTimeoutException) {
            ByteArray(0)
        }
    }

    override fun close() { socket?.close(); socket = null }
}
