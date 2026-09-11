package cl.umag.glaciertemp.transport

import kotlin.test.*

private class FakeLink(chunkSize: Int, gapMs: Long = 0L, capacity: Int = 8192) :
    BufferedTransport(chunkSize, gapMs, capacity) {

    val written = mutableListOf<ByteArray>()
    override var isOpen = false
    override fun open() { isOpen = true }
    override fun close() { isOpen = false }
    override fun writeChunk(chunk: ByteArray) { written += chunk }
    fun deliver(vararg chunks: ByteArray) { chunks.forEach { onReceived(it) } }
    fun flushBuffer() = clearBuffer()
}

class BufferedTransportTest {

    @Test
    fun `trocea la escritura al tamano del enlace`() {
        val t = FakeLink(20)
        t.write(ByteArray(45) { it.toByte() })
        assertEquals(listOf(20, 20, 5), t.written.map { it.size })
        assertContentEquals(ByteArray(45) { it.toByte() },
            t.written.fold(ByteArray(0)) { a, b -> a + b })
    }

    @Test
    fun `una escritura que cabe no se trocea`() {
        val t = FakeLink(20)
        t.write(ByteArray(20))
        assertEquals(1, t.written.size)
    }

    @Test
    fun `una lectura devuelve todo lo que ya llego`() {
        // Devolver solo el primer fragmento convertiria una descarga de miles de trozos
        // de 20 bytes en miles de vueltas del bucle de lectura.
        val t = FakeLink(20)
        t.deliver(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5, 6))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), t.read(100))
    }

    @Test
    fun `sin datos devuelve vacio al expirar el plazo`() {
        val t = FakeLink(20)
        val t0 = System.currentTimeMillis()
        assertEquals(0, t.read(120).size)
        assertTrue(System.currentTimeMillis() - t0 >= 100)
    }

    @Test
    fun `un dato que llega durante la espera despierta la lectura`() {
        val t = FakeLink(20)
        Thread { Thread.sleep(50); t.deliver(byteArrayOf(9)) }.start()
        assertContentEquals(byteArrayOf(9), t.read(2000))
    }

    @Test
    fun `los fragmentos vacios no cuentan como dato`() {
        // Una notificacion BLE vacia haria que read() devolviera un array vacio, que el
        // bucle de descarga interpreta como silencio del enlace.
        val t = FakeLink(20)
        t.deliver(ByteArray(0))
        assertEquals(0, t.read(50).size)
    }

    @Test
    fun `el desbordamiento se marca en vez de corromper en silencio`() {
        val t = FakeLink(20, capacity = 4)
        repeat(10) { t.deliver(byteArrayOf(it.toByte())) }
        assertTrue(t.overflowed)
        assertEquals(4, t.read(50).size)
    }

    @Test
    fun `vaciar el buffer descarta lo pendiente y el aviso`() {
        val t = FakeLink(20, capacity = 2)
        repeat(5) { t.deliver(byteArrayOf(1)) }
        assertTrue(t.overflowed)
        t.flushBuffer()
        assertFalse(t.overflowed)
        assertEquals(0, t.read(50).size)
    }

    @Test
    fun `la pausa entre trozos se aplica entre ellos y no al final`() {
        val t = FakeLink(10, gapMs = 30)
        val t0 = System.currentTimeMillis()
        t.write(ByteArray(30))          // tres trozos, dos pausas
        val ms = System.currentTimeMillis() - t0
        assertTrue(ms >= 60, "esperaba al menos 60 ms, fueron $ms")
        assertTrue(ms < 200, "no deberia pausar tras el ultimo trozo, fueron $ms")
    }
}
