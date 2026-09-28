package cl.umag.glaciertemp.core.fieldbook

import java.io.File
import kotlin.test.*

class NameStoreTest {

    private lateinit var dir: File
    private lateinit var names: NameStore

    @BeforeTest
    fun preparar() {
        dir = File(System.getProperty("java.io.tmpdir"),
                   "names-${System.nanoTime()}").apply { mkdirs() }
        names = NameStore(File(dir, "people.txt"))
    }

    @AfterTest
    fun limpiar() { dir.deleteRecursively() }

    @Test
    fun `una lista que no existe todavia esta vacia`() {
        assertEquals(emptyList(), names.list())
        assertNull(names.mostRecent())
    }

    /**
     * Lo que da el valor por defecto de una entrada nueva. No hay un campo "ultimo usado"
     * aparte: es el primero de la lista, asi que no puede discrepar de ella.
     */
    @Test
    fun `el ultimo usado queda arriba y es el que se propone`() {
        names.remember("Ana")
        names.remember("Luis")
        names.remember("Camilo")
        assertEquals(listOf("Camilo", "Luis", "Ana"), names.list())
        assertEquals("Camilo", names.mostRecent())

        names.remember("Ana")
        assertEquals(listOf("Ana", "Camilo", "Luis"), names.list())
    }

    @Test
    fun `un nombre no se duplica por escribirlo con otras mayusculas`() {
        names.remember("Camilo Rada")
        names.remember("camilo rada")
        assertEquals(listOf("camilo rada"), names.list(),
                     "se queda la grafia recien escrita, que es la ultima que el usuario eligio")
    }

    @Test
    fun `los espacios de los extremos se recortan y un nombre vacio no entra`() {
        names.remember("  Ana  ")
        names.remember("   ")
        names.remember("")
        assertEquals(listOf("Ana"), names.list())
    }

    @Test
    fun `quitar uno deja los demas y vaciar los quita todos`() {
        listOf("Ana", "Luis", "Camilo").forEach { names.remember(it) }
        names.remove("luis")
        assertEquals(listOf("Camilo", "Ana"), names.list())
        names.clear()
        assertEquals(emptyList(), names.list())
    }

    /**
     * La propiedad que pide la especificacion: borrar de la lista no toca los registros.
     *
     * Sale sola porque la entrada guarda el NOMBRE y no una referencia, y por eso se
     * comprueba aqui -- para que quede fijado el dia que a alguien le tiente normalizar
     * esto a una tabla de personas con ids.
     */
    @Test
    fun `borrar un nombre de la lista no cambia lo ya registrado con el`() {
        val store = FieldbookStore(File(dir, "book"))
        names.remember("Emlid RS2")
        val e = store.create(EntryType.GNSS, 1_700_000_000_000L)
        store.save(e.copy(gnss = GnssSession(receiver = "Emlid RS2",
                                             startEpochMillis = 1_700_000_000_000L)))

        names.remove("Emlid RS2")

        assertEquals(emptyList(), names.list())
        assertEquals("Emlid RS2", store.load(e.id)?.gnss?.receiver)
    }
}
