package cl.umag.glaciertemp.transport

import kotlin.test.*

/**
 * El terminal durante un volcado.
 *
 * El resumen normal del espia --texto, y `[N bytes]` para lo que no lo es-- esta pensado
 * para texto con algun byte raro, y un bloque LOGB es exactamente lo contrario. De los 256
 * valores de un byte, 95 son ASCII imprimible: uno de cada 2,7 bytes de dato rompe el
 * resumen y sale como un caracter suelto, en lineas de cientos de caracteres cortadas donde
 * cayo un 0x0A. Medido: unos 2.585 caracteres de terminal por cada 1.024 bytes descargados.
 *
 * En modo volcado los bytes se PESAN. Lo que se fija aqui es esa relacion, no el formato:
 * el formato puede cambiar, pero el dia que el terminal vuelva a crecer con la descarga es
 * un fallo, y es el tipo de fallo que solo se nota en una descarga de verdad.
 */
class DumpTerminalTest {

    /** Entrega lo que se le encole, para que el espia lo vea por donde lo ve de verdad. */
    private class Cinta : Transport {
        private val cola = ArrayDeque<ByteArray>()
        override var isOpen = true
        override fun open() {}
        override fun close() { isOpen = false }
        override fun write(data: ByteArray) {}
        override fun read(timeoutMs: Int) = cola.removeFirstOrNull() ?: ByteArray(0)
        fun encolar(d: ByteArray) { cola.addLast(d) }
        val vacia get() = cola.isEmpty()
    }

    private fun datos(n: Int, semilla: Long = 7): ByteArray {
        val r = java.util.Random(semilla)
        return ByteArray(n).also { r.nextBytes(it) }
    }

    /** Alimenta el espia como lo hace la bomba --leyendo-- y devuelve lo que fue al terminal. */
    private fun espiar(bytes: ByteArray, enVolcado: Boolean, trozo: Int = 256): List<String> {
        val lineas = ArrayList<String>()
        val cinta = Cinta()
        val tap = SerialTap(cinta) { l, _ -> lineas.add(l) }
        var i = 0
        while (i < bytes.size) {
            val n = minOf(trozo, bytes.size - i)
            cinta.encolar(bytes.copyOfRange(i, i + n))
            i += n
        }
        if (enVolcado) tap.volcadoEmpieza()
        while (!cinta.vacia) tap.read(0)
        if (enVolcado) tap.volcadoTermina()
        return lineas
    }

    @Test
    fun `un volcado no hace crecer el terminal con la descarga`() {
        val n = 256 * 1024
        val sueltas = espiar(datos(n), enVolcado = false)
        val pesadas = espiar(datos(n), enVolcado = true)

        val antes = sueltas.sumOf { it.length }
        val ahora = pesadas.sumOf { it.length }

        // El de antes crecia con los datos; el de ahora con su logaritmo, mas o menos.
        assertTrue(antes > n, "el resumen viejo deberia pasarse de largo, y midio $antes")
        assertTrue(ahora < n / 1000,
                   "el terminal sigue creciendo con la descarga: $ahora caracteres " +
                   "para $n bytes (antes eran $antes)")
        assertTrue(pesadas.size <= n / SerialTap.AVISO_CADA + 2,
                   "demasiadas lineas de resumen: ${pesadas.size}")
    }

    @Test
    fun `el resumen dice cuanto paso, y lo dice bien`() {
        val pesadas = espiar(datos(200 * 1024), enVolcado = true)
        assertTrue(pesadas.isNotEmpty(), "el volcado no dejo ni una linea")
        assertTrue(pesadas.all { it.startsWith("[dump:") },
                   "se colo algo que no es un resumen: $pesadas")
        // El ultimo lleva el total, y el total es el total.
        val ultimo = pesadas.last()
        val kb = Regex("""([\d.]+) kB""").find(ultimo)?.groupValues?.get(1)?.toDouble()
        assertNotNull(kb, "el ultimo resumen no trae los kB: $ultimo")
        assertEquals(200.0, kb, 0.1, "el total no cuadra: $ultimo")
    }

    @Test
    fun `fuera del volcado el terminal sigue mostrando el texto`() {
        val lineas = espiar("fw=3.3 proto=4\nLOGB end\n".toByteArray(), enVolcado = false)
        assertEquals(listOf("fw=3.3 proto=4", "LOGB end"), lineas)
    }

    @Test
    fun `el texto a medias sale antes de que empiece el volcado, no despues`() {
        // La cabecera puede llegar pegada al primer bloque. Si se entrara en modo volcado
        // sin sacarla, se contaria como datos y el usuario no veria nunca que empezo.
        val lineas = ArrayList<String>()
        val cinta = Cinta()
        val tap = SerialTap(cinta) { l, _ -> lineas.add(l) }
        cinta.encolar("LOGB begin sig=0x100F".toByteArray())   // sin salto de linea todavia
        tap.read(0)
        tap.volcadoEmpieza()
        cinta.encolar(datos(4096)); tap.read(0)
        tap.volcadoTermina()

        assertEquals("LOGB begin sig=0x100F", lineas.first(),
                     "la cabecera a medias se perdio dentro del volcado: $lineas")
    }

    @Test
    fun `miles de tramos son un solo volcado, no miles`() {
        // Por radio una descarga son miles de LOGB dentro de UNA operacion. Con un booleano
        // en vez de un contador, el primer tramo que termina apaga el modo para los demas y
        // el terminal se llena igual que antes.
        val lineas = ArrayList<String>()
        val cinta = Cinta()
        val tap = SerialTap(cinta) { l, _ -> lineas.add(l) }
        tap.volcadoEmpieza()
        repeat(50) {
            tap.volcadoEmpieza()
            cinta.encolar(datos(432, semilla = it.toLong())); tap.read(0)
            tap.volcadoTermina()
        }
        tap.volcadoTermina()

        assertTrue(lineas.all { it.startsWith("[dump:") },
                   "se rompio el modo volcado a mitad de la descarga: " +
                   lineas.filterNot { it.startsWith("[dump:") }.take(3))
    }
}
