package cl.umag.glaciertemp.transport

import kotlin.test.*

class SerialTapTest {

    /** Un transporte de mentira que devuelve lo que se le programe. */
    private class Fake(var toRead: MutableList<ByteArray> = mutableListOf()) : Transport {
        val written = mutableListOf<ByteArray>()
        override var isOpen = true
        override fun open() {}
        override fun write(data: ByteArray) { written += data }
        override fun read(timeoutMs: Int): ByteArray =
            if (toRead.isEmpty()) ByteArray(0) else toRead.removeAt(0)
        override fun close() { isOpen = false }
    }

    private fun tap(f: Transport, out: MutableList<Pair<String, Boolean>>) =
        SerialTap.wrap(f) { linea, deLaPlaca -> out += linea to deLaPlaca }

    /** Un transporte que ademas sabe cambiar de velocidad, como el cable. */
    private class FakeCable : Transport by Fake(), BaudSwitchable {
        var baud = 0
        override fun setBaudRate(baud: Int) { this.baud = baud }
    }

    @Test
    fun `lo enviado y lo recibido aparecen los dos`() {
        val f = Fake(mutableListOf("Time: 2026-09-10 23:48:27\n".toByteArray()))
        val out = mutableListOf<Pair<String, Boolean>>()
        val t = tap(f, out)
        t.write("TIME\n".toByteArray())
        t.read(100)

        assertEquals(listOf("> TIME" to false, "Time: 2026-09-10 23:48:27" to true), out)
        // Y lo escrito llega igual al transporte de debajo: el espia no altera la linea.
        assertEquals("TIME\n", String(f.written.single()))
    }

    @Test
    fun `una linea partida entre dos lecturas sale entera y una sola vez`() {
        val f = Fake(mutableListOf("INFO fw=2.8 pro".toByteArray(), "to=3 id=DF65\n".toByteArray()))
        val out = mutableListOf<Pair<String, Boolean>>()
        val t = tap(f, out)
        t.read(100); t.read(100)
        assertEquals(listOf("INFO fw=2.8 proto=3 id=DF65" to true), out)
    }

    @Test
    fun `los bloques binarios se resumen y no se vuelcan`() {
        // Cabecera de texto, un bloque binario y el cierre, como en un LOGB de verdad.
        val bin = ByteArray(300) { (it % 256).toByte() }
        val f = Fake(mutableListOf(
            "LOGB begin blocks=1\n".toByteArray(), bin, "\nLOGB end\n".toByteArray()))
        val out = mutableListOf<Pair<String, Boolean>>()
        val t = tap(f, out)
        repeat(3) { t.read(100) }
        t.flush()

        val texto = out.map { it.first }
        assertEquals("LOGB begin blocks=1", texto.first())
        assertTrue(texto.any { it.contains("bytes]") }, "no resumio el bloque binario: $texto")
        assertTrue(texto.contains("LOGB end"))
        // Nada de lo publicado puede llevar bytes no imprimibles.
        for (l in texto) {
            assertTrue(l.all { it.code in 0x20..0x7E || it == '\t' }, "linea con binario: $l")
        }
    }

    @Test
    fun `un volcado largo no deja el terminal mudo`() {
        // Sin saltos de linea durante mucho rato: el resumen tiene que salir igual.
        val f = Fake((1..10).map { ByteArray(2000) { 0xAA.toByte() } }.toMutableList())
        val out = mutableListOf<Pair<String, Boolean>>()
        val t = tap(f, out)
        repeat(10) { t.read(100) }
        assertTrue(out.isNotEmpty(), "el terminal no recibio nada durante el volcado")
        assertTrue(out.all { it.first.contains("bytes]") })
    }

    @Test
    fun `el espia conserva si el transporte sabe cambiar de velocidad`() {
        // Es la propiedad con la que DeviceSession decide si puede pedir el volcado rapido.
        // Si el espia la pierde, el cable baja a la mitad de velocidad sin decir nada.
        val cable = FakeCable()
        val conCable = SerialTap.wrap(cable) { _, _ -> }
        assertTrue(conCable is BaudSwitchable, "el espia perdio BaudSwitchable sobre cable")
        (conCable as BaudSwitchable).setBaudRate(230400)
        assertEquals(230400, cable.baud, "no llego al transporte de debajo")
    }

    @Test
    fun `y no se la inventa cuando no la hay`() {
        // Si el espia dijera que si sobre BLE, la placa cambiaria a 230400 y el modulo se
        // quedaria en 115200: no llegaria nada legible y el CRC de cada bloque fallaria.
        val radio = Fake()
        val conRadio = SerialTap.wrap(radio) { _, _ -> }
        assertFalse(conRadio is BaudSwitchable,
                    "el espia dice que puede cambiar de velocidad sobre un enlace que no puede")
    }
}
