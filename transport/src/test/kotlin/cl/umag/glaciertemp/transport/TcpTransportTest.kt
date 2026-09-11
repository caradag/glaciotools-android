package cl.umag.glaciertemp.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class TcpTransportTest {

    /**
     * Regresion: con `Socket().apply { connect(InetSocketAddress(host, port)) }` el `port`
     * se resolvia a la propiedad del Socket (0) en vez de al parametro del constructor, y la
     * app fallaba con "failed to connect to /10.0.2.2 (port 0)". El fallo no aparecia en la
     * suite porque los tests de punta a punta usan PipeTransport.
     */
    @Test fun `el endpoint conserva el puerto del constructor`() {
        val t = TcpTransport("10.0.2.2", 5599)
        assertEquals(5599, t.endpoint().port)
        assertEquals("10.0.2.2", t.endpoint().hostString)
    }
}
