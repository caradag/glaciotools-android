package cl.umag.glaciertemp.core.fieldbook

import java.io.File
import kotlin.test.*

class CampaignStoreTest {

    private val T = 1_700_000_000_000L
    private lateinit var dir: File
    private lateinit var store: CampaignStore

    @BeforeTest
    fun preparar() {
        dir = File(System.getProperty("java.io.tmpdir"),
                   "campaigns-${System.nanoTime()}").apply { mkdirs() }
        store = CampaignStore(File(dir, "campaigns.txt"))
    }

    @AfterTest
    fun limpiar() { dir.deleteRecursively() }

    @Test
    fun `sin fichero no hay campanas y no revienta`() {
        assertEquals(emptyList(), store.list())
        assertNull(store.active())
    }

    /**
     * Es idempotente porque lo llama la libreta en CADA entrada nueva: anotar no puede exigir
     * haber creado antes una campana a mano.
     */
    @Test
    fun `abrir una campana dos veces devuelve la misma`() {
        val a = store.openOrCurrent(now = T)
        val b = store.openOrCurrent(now = T + 5000)
        assertEquals(a.id, b.id)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `el nombre se pone al terminar y sobrevive al archivado`() {
        val c = store.openOrCurrent(now = T)
        assertEquals("Unnamed campaign", c.displayName())

        store.rename(c.id, "Bernal feb 2026")
        store.archive(c.id, T + 86_400_000L)

        val leida = assertNotNull(store.byId(c.id))
        assertEquals("Bernal feb 2026", leida.name)
        assertTrue(leida.archived)
        assertEquals(T + 86_400_000L, leida.archivedEpochMillis)
        assertNull(store.active(), "archivada deja de ser la abierta")
    }

    @Test
    fun `archivar deja sitio para una campana nueva`() {
        val primera = store.openOrCurrent(now = T)
        store.archive(primera.id, T + 1000)
        val segunda = store.openOrCurrent(now = T + 2000)

        assertNotEquals(primera.id, segunda.id)
        assertEquals(segunda.id, store.active()?.id)
        assertEquals(listOf(primera.id), store.archivedCampaigns().map { it.id })
    }

    /**
     * Dos campanas abiertas a la vez no significan nada: una campana es donde se esta ahora, y
     * estar en dos sitios seria un error de datos a resolver en cada entrada.
     */
    @Test
    fun `no se puede reabrir una campana si hay otra abierta`() {
        val primera = store.openOrCurrent(now = T)
        store.archive(primera.id, T + 1000)
        store.openOrCurrent(now = T + 2000)

        assertFalse(store.unarchive(primera.id))
        assertTrue(assertNotNull(store.byId(primera.id)).archived, "sigue archivada")
    }

    @Test
    fun `se puede reabrir cuando no hay ninguna abierta`() {
        val c = store.openOrCurrent(now = T)
        store.archive(c.id, T + 1000)
        assertTrue(store.unarchive(c.id))
        assertEquals(c.id, store.active()?.id)
    }

    /**
     * Un nombre lo escribe una persona y puede traer saltos de linea pegados de otro sitio.
     * Uno solo partiria el fichero y la campana siguiente se leeria como basura.
     */
    @Test
    fun `un nombre con saltos de linea y barras verticales sobrevive`() {
        val c = store.openOrCurrent(now = T)
        val raro = "Bernal | feb\n2026 \\ campana"
        store.rename(c.id, raro)

        val releida = CampaignStore(File(dir, "campaigns.txt"))
        assertEquals(raro, releida.byId(c.id)?.name)
        assertEquals(1, releida.list().size, "el fichero no se partio")
    }

    /**
     * Borrar la campana NO borra el trabajo de terreno. Las entradas quedan sin campana, que
     * es donde estaban antes de que las campanas existieran.
     */
    @Test
    fun `borrar una campana la quita de la lista y nada mas`() {
        val libreta = FieldbookStore(File(dir, "book"))
        val c = store.openOrCurrent(now = T)
        val e = libreta.create(EntryType.NOTE, T)
        libreta.save(e.copy(campaignId = c.id, title = "Grietas"))

        store.delete(c.id)

        assertEquals(emptyList(), store.list())
        val leida = assertNotNull(libreta.load(e.id))
        assertEquals("Grietas", leida.title)
        assertEquals(c.id, leida.campaignId, "la entrada sigue diciendo de que campana era")
    }

    @Test
    fun `una linea corrupta se salta sin tumbar la lista`() {
        val c = store.openOrCurrent(now = T)
        File(dir, "campaigns.txt").appendText("esto no es una campana\n")
        assertEquals(listOf(c.id), store.list().map { it.id })
    }
}
