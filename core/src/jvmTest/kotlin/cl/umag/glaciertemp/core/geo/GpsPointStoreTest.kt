package cl.umag.glaciertemp.core.geo

import java.io.File
import kotlin.test.*

class GpsPointStoreTest {

    private lateinit var dir: File
    private lateinit var store: GpsPointStore

    @BeforeTest
    fun preparar() {
        dir = File(System.getProperty("java.io.tmpdir"),
                   "gpspoints-${System.nanoTime()}").apply { mkdirs() }
        store = GpsPointStore(dir)
    }

    @AfterTest
    fun limpiar() { dir.deleteRecursively() }

    private fun muestra(i: Int) = GpsSample(
        1_700_000_000_000L + i * 1000L, -53.1638 + i * 1e-6, -70.9171, 120.0, 4.0)

    @Test
    fun `un punto nuevo esta vacio y se puede volver a abrir`() {
        val id = store.create("Estaca 3")
        val p = assertNotNull(store.load(id))
        assertEquals("Estaca 3", p.header.name)
        assertEquals(0, p.samples.size)
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun `seguir promediando anade al final y no reescribe`() {
        val id = store.create("Estaca 3")
        store.append(id, (0 until 5).map { muestra(it) })
        val tamanoTrasPrimera = File(dir, "$id.gpspoint").length()

        // Segunda visita, dias despues.
        store.append(id, (5 until 9).map { muestra(it) })
        val p = assertNotNull(store.load(id))

        assertEquals(9, p.samples.size)
        assertTrue(File(dir, "$id.gpspoint").length() > tamanoTrasPrimera)
        // El orden se conserva, que es lo que permite dibujar la altitud contra el tiempo.
        assertEquals((0 until 9).map { muestra(it).epochMillis },
                     p.samples.map { it.epochMillis })
    }

    @Test
    fun `dos puntos creados a la vez no se pisan`() {
        // El precio de una colision es perder una medida de terreno, asi que el id no puede
        // ser solo el reloj.
        val ids = (1..200).map { store.create("p$it") }
        assertEquals(200, ids.toSet().size, "hay ids repetidos")
        assertEquals(200, store.list().size)
    }

    @Test
    fun `un fichero corrupto no se lleva por delante a los demas`() {
        val bueno = store.create("bueno")
        store.append(bueno, listOf(muestra(0)))
        File(dir, "roto.gpspoint").writeText("esto no es un punto")

        val lista = store.list()
        assertEquals(listOf(bueno), lista.map { it.id },
                     "el fichero ilegible tumbo la lista entera")
        assertEquals(1, lista[0].samples)
    }

    @Test
    fun `renombrar conserva las muestras`() {
        val id = store.create("sin nombre")
        store.append(id, (0 until 7).map { muestra(it) })
        assertTrue(store.rename(id, "Estaca 3"))

        val p = assertNotNull(store.load(id))
        assertEquals("Estaca 3", p.header.name)
        assertEquals(7, p.samples.size, "renombrar se llevo las muestras por delante")
        assertEquals(id, p.header.id, "renombrar cambio la identidad del punto")
    }

    @Test
    fun `la lista pone primero el que se toco mas recientemente`() {
        val viejo = store.create("viejo")
        store.append(viejo, listOf(muestra(0)))
        val nuevo = store.create("nuevo")
        store.append(nuevo, listOf(muestra(5000)))
        assertEquals(listOf(nuevo, viejo), store.list().map { it.id })
    }

    @Test
    fun `borrar un punto lo borra de verdad`() {
        val id = store.create("x")
        assertTrue(store.delete(id))
        assertNull(store.load(id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `anadir a un punto que no existe no crea uno por su cuenta`() {
        // Si lo creara, un id equivocado produciria un punto fantasma sin nombre ni fecha
        // que aparece en la lista sin que nadie lo haya pedido.
        store.append("p-inexistente", listOf(muestra(0)))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `las muestras sobreviven a una escritura interrumpida`() {
        val id = store.create("Estaca 3")
        store.append(id, (0 until 10).map { muestra(it) })
        // Se simula el corte: la ultima linea queda a medias.
        val f = File(dir, "$id.gpspoint")
        f.writeText(f.readText() + "1700000099000,-53.16")

        val p = assertNotNull(store.load(id))
        assertEquals(10, p.samples.size)
        assertEquals(1, p.skipped)
    }
}
