package cl.umag.glaciertemp.core.fieldbook

import java.io.File
import kotlin.test.*

class FieldbookStoreTest {

    private val T = 1_700_000_000_000L
    private lateinit var dir: File
    private lateinit var store: FieldbookStore

    @BeforeTest
    fun preparar() {
        dir = File(System.getProperty("java.io.tmpdir"),
                   "fieldbook-${System.nanoTime()}").apply { mkdirs() }
        store = FieldbookStore(dir)
    }

    @AfterTest
    fun limpiar() { dir.deleteRecursively() }

    @Test
    fun `una entrada nueva se guarda sola y se vuelve a abrir`() {
        val e = store.create(EntryType.NOTE, T, "Camilo Rada")
        val leida = assertNotNull(store.load(e.id))
        assertEquals(EntryType.NOTE, leida.type)
        assertEquals("Camilo Rada", leida.person)
        assertEquals(listOf(e.id), store.list().map { it.id })
    }

    /**
     * Se guarda al CREAR y no al terminar de rellenar. Una medicion GNSS de tres horas que
     * solo existiera en memoria se perderia entera si Android mata la app mientras el
     * telefono esta en el bolsillo, que es justo lo que pasa durante esas tres horas.
     */
    @Test
    fun `la entrada esta en disco antes de rellenar nada`() {
        val e = store.create(EntryType.GNSS, T)
        assertTrue(File(dir, "${e.id}${FieldbookStore.EXTENSION}").exists())
    }

    @Test
    fun `la lista trae primero la modificada mas recientemente`() {
        val vieja = store.create(EntryType.NOTE, T)
        val nueva = store.create(EntryType.STAKE, T + 1000)
        store.save(vieja.copy(updatedEpochMillis = T + 5000))
        assertEquals(listOf(vieja.id, nueva.id), store.list().map { it.id })
    }

    @Test
    fun `borrar una entrada se lleva sus fotos`() {
        val foto = store.newMediaFile("jpg").apply { writeText("datos") }
        val e = store.create(EntryType.DENDRO, T)
        store.save(e.copy(sampleLabel = "TR-1", photos = listOf(foto.name)))

        store.delete(e.id)
        assertNull(store.load(e.id))
        assertFalse(foto.exists(), "la foto quedo huerfana")
    }

    /**
     * Una foto nombrada por DOS entradas no se puede borrar al borrar una de ellas: la otra
     * se quedaria sin su imagen y no hay forma de recuperarla.
     */
    @Test
    fun `borrar no toca una foto que otra entrada sigue usando`() {
        val foto = store.newMediaFile("jpg").apply { writeText("datos") }
        val a = store.create(EntryType.DENDRO, T)
        val b = store.create(EntryType.DENDRO, T + 1)
        store.save(a.copy(photos = listOf(foto.name)))
        store.save(b.copy(photos = listOf(foto.name)))

        store.delete(a.id)
        assertTrue(foto.exists(), "se borro una foto que b sigue usando")
    }

    @Test
    fun `una entrada ilegible no tumba la lista`() {
        val buena = store.create(EntryType.NOTE, T)
        File(dir, "rota${FieldbookStore.EXTENSION}").writeText("esto no es una entrada")
        assertEquals(listOf(buena.id), store.list().map { it.id })
    }

    @Test
    fun `los medios sin dueno se identifican sin borrarse solos`() {
        val usada = store.newMediaFile("jpg").apply { writeText("a") }
        val suelta = store.newMediaFile("jpg").apply { writeText("b") }
        val e = store.create(EntryType.NOTE, T)
        store.save(e.copy(items = listOf(NoteItem(NoteItemKind.PHOTO, T, file = usada.name))))

        assertEquals(listOf(suelta.name), store.orphanMedia().map { it.name })
        assertTrue(suelta.exists(), "orphanMedia no debe borrar nada")
    }

    /**
     * El caso que el barrido NO puede romper: el fichero de una foto existe ANTES de que la
     * camara escriba en el y antes de que ninguna entrada lo nombre. Un barrido sin edad
     * minima borraria justo ese, y la foto se perderia al volver de la camara.
     */
    @Test
    fun `el barrido respeta un fichero recien creado para la camara`() {
        val enVuelo = store.newMediaFile("jpg").apply { writeText("") }
        val viejo = store.newMediaFile("jpg").apply {
            writeText("b"); setLastModified(System.currentTimeMillis() - 30 * 60_000L)
        }

        assertEquals(1, store.purgeOrphanMedia())
        assertTrue(enVuelo.exists(), "se borro el fichero de una captura en curso")
        assertFalse(viejo.exists())
    }

    @Test
    fun `el barrido no toca un medio que una entrada nombra, por viejo que sea`() {
        val usada = store.newMediaFile("jpg").apply {
            writeText("a"); setLastModified(System.currentTimeMillis() - 30L * 86_400_000L)
        }
        val e = store.create(EntryType.DENDRO, T)
        store.save(e.copy(photos = listOf(usada.name)))

        assertEquals(0, store.purgeOrphanMedia())
        assertTrue(usada.exists())
    }

    @Test
    fun `guardar no deja ficheros temporales por medio`() {
        val e = store.create(EntryType.STAKE, T)
        store.save(e.copy(stakeName = "E1"))
        assertEquals(emptyList(), (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".tmp") }.map { it.name })
    }

    @Test
    fun `dos entradas creadas seguidas no se pisan`() {
        val ids = (1..50).map { store.create(EntryType.NOTE, T).id }
        assertEquals(50, ids.toSet().size)
        assertEquals(50, store.list().size)
    }
}
