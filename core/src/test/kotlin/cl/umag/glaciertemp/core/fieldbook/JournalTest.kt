package cl.umag.glaciertemp.core.fieldbook

import java.io.File
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Patagonia: tres horas al oeste. Es la zona en la que la app se usa. */
private val PUNTA_ARENAS: TimeZone = TimeZone.getTimeZone("America/Punta_Arenas")

class JournalDaysTest {

    private fun ms(iso: String): Long =
        java.time.Instant.parse(iso).toEpochMilli()

    /**
     * EL CASO QUE MOTIVA USAR HORA LOCAL. A las 22:00 del dia 10 en Patagonia ya es dia 11
     * en UTC. Con claves UTC, todo lo escrito despues de cenar --que es cuando se escribe un
     * diario de terreno-- caeria en el dia siguiente y partiria cada jornada en dos.
     */
    @Test fun `la clave del dia va en hora local, no UTC`() {
        val nocheDel10 = ms("2026-02-11T01:00:00Z")   // 22:00 del 10 en Punta Arenas
        assertEquals("2026-02-10", JournalDays.dayKey(nocheDel10, PUNTA_ARENAS))
        assertEquals("2026-02-11", JournalDays.dayKey(nocheDel10, TimeZone.getTimeZone("UTC")))
    }

    @Test fun `el dia anterior`() {
        val t = ms("2026-02-11T15:00:00Z")            // 12:00 del 11 en Punta Arenas
        assertEquals("2026-02-10", JournalDays.previousDayKey(t, PUNTA_ARENAS))
    }

    /** Y a traves de un cambio de mes, que es donde un "-1 dia" hecho a mano falla. */
    @Test fun `el dia anterior cruza fin de mes y fin de ano`() {
        assertEquals("2026-02-28",
            JournalDays.previousDayKey(ms("2026-03-01T15:00:00Z"), PUNTA_ARENAS))
        assertEquals("2025-12-31",
            JournalDays.previousDayKey(ms("2026-01-01T15:00:00Z"), PUNTA_ARENAS))
    }

    private fun e(id: String, iso: String) =
        JournalEntry(id, "C1", ms(iso))

    @Test fun `los dias van del mas reciente al mas antiguo`() {
        val dias = JournalDays.group(
            listOf(e("a", "2026-02-10T15:00:00Z"),
                   e("b", "2026-02-12T15:00:00Z"),
                   e("c", "2026-02-11T15:00:00Z")),
            emptyMap(), PUNTA_ARENAS)
        assertEquals(listOf("2026-02-12", "2026-02-11", "2026-02-10"), dias.map { it.key })
    }

    @Test fun `dentro de un dia, las entradas van en orden ascendente`() {
        val dias = JournalDays.group(
            listOf(e("tarde", "2026-02-10T20:00:00Z"),
                   e("manana", "2026-02-10T13:00:00Z"),
                   e("mediodia", "2026-02-10T16:00:00Z")),
            emptyMap(), PUNTA_ARENAS)
        assertEquals(1, dias.size)
        assertEquals(listOf("manana", "mediodia", "tarde"), dias.single().entries.map { it.id })
    }

    /**
     * Cambiarle la fecha a una entrada la MUEVE de dia. Es lo que permite anotar de noche
     * algo que paso ayer, y el unico mecanismo que hay para corregir una fecha mal puesta.
     */
    @Test fun `cambiar la fecha mueve la entrada de dia`() {
        val e1 = e("x", "2026-02-12T15:00:00Z")
        assertEquals("2026-02-12",
            JournalDays.group(listOf(e1), emptyMap(), PUNTA_ARENAS).single().key)
        val movida = e1.copy(epochMillis = ms("2026-02-09T15:00:00Z"))
        assertEquals("2026-02-09",
            JournalDays.group(listOf(movida), emptyMap(), PUNTA_ARENAS).single().key)
    }

    @Test fun `el titulo del dia viaja con su dia`() {
        val dias = JournalDays.group(
            listOf(e("a", "2026-02-10T15:00:00Z")),
            mapOf("2026-02-10" to "Instalación de balizas"), PUNTA_ARENAS)
        assertEquals("Instalación de balizas", dias.single().title)
    }
}

class JournalReminderTest {

    private fun ms(iso: String) = java.time.Instant.parse(iso).toEpochMilli()
    private val ahora = ms("2026-02-11T15:00:00Z")    // 12:00 del 11 en Punta Arenas

    @Test fun `avisa del dia anterior cuando no tiene entradas`() {
        assertEquals("2026-02-10",
            JournalReminder.missingDay(setOf("2026-02-11"), ahora, null, PUNTA_ARENAS))
    }

    @Test fun `no avisa si ayer ya tiene alguna entrada`() {
        assertNull(JournalReminder.missingDay(
            setOf("2026-02-10", "2026-02-11"), ahora, null, PUNTA_ARENAS))
    }

    @Test fun `descartarlo lo calla, pero solo para ESE dia`() {
        assertNull(JournalReminder.missingDay(emptySet(), ahora, "2026-02-10", PUNTA_ARENAS))
        // Al dia siguiente, el aviso vuelve: el olvido del martes no silencia el del miercoles.
        val manana = ms("2026-02-12T15:00:00Z")
        assertEquals("2026-02-11",
            JournalReminder.missingDay(emptySet(), manana, "2026-02-10", PUNTA_ARENAS))
    }
}

class JournalStoreTest {

    private val dir = File(System.getProperty("java.io.tmpdir"),
                           "journal-test-" + System.nanoTime())
    private val store = JournalStore(dir)

    @AfterTest fun limpiar() { dir.deleteRecursively() }

    @Test fun `ida y vuelta conservando todo`() {
        val e = JournalEntry(
            id = store.newId(), campaignId = "C1", epochMillis = 1_770_000_000_000L,
            title = "Día de mal tiempo",
            text = "Viento 40 nudos.\nNo se pudo salir del campamento.\nSe revisaron equipos.",
            photos = listOf("m1.jpg", "m2.jpg"),
            audio = listOf(JournalAudio("a1.m4a", 12345L), JournalAudio("a2.m4a", null)))
        assertTrue(store.save(e))
        val leida = store.load(e.id)
        assertNotNull(leida)
        assertEquals(e, leida)
    }

    /** Los saltos de linea del texto libre NO pueden partir el fichero. */
    @Test fun `el texto con saltos de linea sobrevive`() {
        val e = JournalEntry(store.newId(), "C1", 1L,
                             text = "linea 1\nlinea 2\r\nid=falso\ntitle=tampoco")
        store.save(e)
        val l = store.load(e.id)!!
        assertEquals(e.text, l.text)
        assertEquals("", l.title)
    }

    @Test fun `solo devuelve las de la campana pedida`() {
        store.save(JournalEntry(store.newId(), "C1", 100L, title = "a"))
        store.save(JournalEntry(store.newId(), "C2", 200L, title = "b"))
        assertEquals(listOf("a"), store.list("C1").map { it.title })
        assertEquals(listOf("b"), store.list("C2").map { it.title })
    }

    @Test fun `los titulos de dia se guardan por campana`() {
        store.setDayTitle("C1", "2026-02-10", "Balizas")
        store.setDayTitle("C2", "2026-02-10", "Otra cosa")
        assertEquals(mapOf("2026-02-10" to "Balizas"), store.dayTitles("C1"))
        assertEquals(mapOf("2026-02-10" to "Otra cosa"), store.dayTitles("C2"))
    }

    @Test fun `un titulo vacio se borra en vez de guardarse`() {
        store.setDayTitle("C1", "2026-02-10", "Balizas")
        store.setDayTitle("C1", "2026-02-10", "")
        assertTrue(store.dayTitles("C1").isEmpty())
    }

    /**
     * Los medios del diario viven en SU carpeta. Si compartieran la de la libreta, el
     * barrido de huerfanos de FieldbookStore --que solo mira los .fieldnote-- los borraria
     * a los diez minutos de tomarlos.
     */
    @Test fun `la carpeta de medios es propia y distinta de la de la libreta`() {
        val libreta = FieldbookStore(File(dir, "../fieldbook-" + System.nanoTime()))
        assertTrue(store.mediaDir.absolutePath != libreta.mediaDir.absolutePath)
        val f = store.newMediaFile("jpg")
        assertEquals(store.mediaDir.absolutePath, f.parentFile.absolutePath)
        libreta.let { File(it.mediaDir.parentFile.absolutePath).deleteRecursively() }
    }

    @Test fun `borrar la campana se lleva sus entradas y sus titulos`() {
        val a = JournalEntry(store.newId(), "C1", 1L, title = "a")
        val b = JournalEntry(store.newId(), "C2", 1L, title = "b")
        store.save(a); store.save(b)
        store.setDayTitle("C1", "2026-02-10", "Balizas")
        store.deleteCampaign("C1")
        assertTrue(store.list("C1").isEmpty())
        assertTrue(store.dayTitles("C1").isEmpty())
        assertEquals(1, store.list("C2").size)
    }

    @Test fun `un fichero ilegible se salta en vez de tumbar la lista`() {
        store.save(JournalEntry(store.newId(), "C1", 1L, title = "buena"))
        File(dir, "basura${JournalStore.EXTENSION}").writeText("esto no es una entrada")
        assertEquals(listOf("buena"), store.list("C1").map { it.title })
    }
}
