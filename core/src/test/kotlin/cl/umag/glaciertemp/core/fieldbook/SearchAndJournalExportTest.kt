package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.TimeZone
import java.util.zip.ZipInputStream

private val ZONA = ZoneId.of("America/Santiago")
private val TZ = TimeZone.getTimeZone(ZONA)

/** 2026-09-24 12:00 hora de Santiago, y desplazamientos a partir de ahi. */
private const val T0 = 1790262000000L
private const val DIA = 86_400_000L
private const val HORA = 3_600_000L

private fun nota(id: String, txt: String, ms: Long = T0, campana: String? = "c1") =
    FieldEntry(id = id, type = EntryType.NOTE, createdEpochMillis = ms, campaignId = campana,
               items = listOf(NoteItem(NoteItemKind.TEXT, ms, txt)))

private fun diario(id: String, titulo: String, txt: String, ms: Long, campana: String = "c1") =
    JournalEntry(id = id, campaignId = campana, epochMillis = ms, title = titulo, text = txt)

class FieldbookSearchTest {

    @Test fun `encuentra ignorando mayusculas y tildes`() {
        val notas = listOf(nota("n1", "Se instalaron tres BALÍZAS en el sector norte."))
        assertEquals(1, FieldbookSearch.search("balizas", notas).size)
        assertEquals(1, FieldbookSearch.search("BALIZAS", notas).size)
        assertEquals(1, FieldbookSearch.search("balízas", notas).size)
    }

    @Test fun `una consulta vacia no devuelve nada`() {
        val notas = listOf(nota("n1", "cualquier cosa"))
        assertTrue(FieldbookSearch.search("", notas).isEmpty())
        assertTrue(FieldbookSearch.search("   ", notas).isEmpty())
    }

    @Test fun `busca tambien en campanas archivadas`() {
        // La razon de ser de la busqueda: lo que no se recuerda esta en lo viejo.
        val notas = listOf(
            nota("viejo", "Puente de nieve en la ruta de acceso", T0 - 700 * DIA, "archivada"),
            nota("nuevo", "Nada que ver", T0))
        val r = FieldbookSearch.search("puente", notas)
        assertEquals(listOf("viejo"), r.map { it.id })
        assertEquals("archivada", r[0].campaignId)
    }

    @Test fun `una nota que repite la palabra es un solo resultado`() {
        val notas = listOf(nota("n1", "grieta, grieta, y otra grieta"))
        assertEquals(1, FieldbookSearch.search("grieta", notas).size)
    }

    @Test fun `busca en el diario y en las notas a la vez`() {
        val notas = listOf(nota("n1", "viento flojo por la manana"))
        val dia = listOf(diario("j1", "Mal tiempo", "viento de 60 nudos", T0 + DIA))
        val r = FieldbookSearch.search("viento", notas, dia)
        assertEquals(setOf("n1", "j1"), r.map { it.id }.toSet())
        assertEquals(FieldbookSearch.Kind.JOURNAL, r.first { it.id == "j1" }.kind)
        assertEquals(FieldbookSearch.Kind.NOTE, r.first { it.id == "n1" }.kind)
    }

    @Test fun `lo mas reciente va primero`() {
        val notas = listOf(
            nota("viejo", "hielo", T0),
            nota("nuevo", "hielo", T0 + 5 * DIA),
            nota("medio", "hielo", T0 + DIA))
        assertEquals(listOf("nuevo", "medio", "viejo"),
                     FieldbookSearch.search("hielo", notas).map { it.id })
    }

    @Test fun `encuentra en los nombres propios de cada tipo`() {
        val e = listOf(
            FieldEntry("s1", EntryType.STAKE, T0, stakeName = "Baliza E-12"),
            FieldEntry("g1", EntryType.GNSS, T0, pointName = "Punto E-12"),
            FieldEntry("d1", EntryType.DENDRO, T0, sampleLabel = "M-3", species = "Nothofagus"))
        assertEquals(setOf("s1", "g1"), FieldbookSearch.search("e-12", e).map { it.id }.toSet())
        assertEquals(listOf("d1"), FieldbookSearch.search("nothofagus", e).map { it.id })
        assertEquals("Species", FieldbookSearch.search("nothofagus", e)[0].field)
    }

    @Test fun `el fragmento rodea lo encontrado y marca lo que falta`() {
        val largo = "a".repeat(200) + " el puente de nieve cedio " + "b".repeat(200)
        val s = FieldbookSearch.snippet(largo, "puente")
        assertTrue(s.contains("puente"), "debe contener la palabra: $s")
        assertTrue(s.startsWith("…"), "debe marcar texto antes: $s")
        assertTrue(s.endsWith("…"), "debe marcar texto despues: $s")
        assertTrue(s.length < 160, "no debe traerse la nota entera: ${s.length}")
    }

    @Test fun `el fragmento de un texto corto va entero y sin marcas`() {
        val s = FieldbookSearch.snippet("viento flojo", "viento")
        assertEquals("viento flojo", s)
    }
}

class JournalMediaNamesTest {

    @Test fun `el nombre lleva la marca de tiempo de su entrada`() {
        val e = listOf(diario("j1", "t", "x", T0).copy(photos = listOf("m111.jpg")))
        assertEquals("20260924_120000.jpg",
                     FieldbookExport.journalMediaNames(e, ZONA)["m111.jpg"])
    }

    @Test fun `dos medios del mismo segundo no se pisan`() {
        val e = listOf(diario("j1", "t", "x", T0)
            .copy(photos = listOf("a.jpg", "b.jpg"), audio = listOf(JournalAudio("c.m4a", 1000))))
        val n = FieldbookExport.journalMediaNames(e, ZONA)
        assertEquals(3, n.values.toSet().size)
        assertEquals("20260924_120000.jpg", n["a.jpg"])
        assertEquals("20260924_120000_2.jpg", n["b.jpg"])
        assertEquals("20260924_120000.m4a", n["c.m4a"])
    }

    @Test fun `fotos y audios comparten una sola carpeta`() {
        // Lo pedido: todo junto, distinguido por el nombre y no por la ruta.
        val e = listOf(diario("j1", "t", "x", T0)
            .copy(photos = listOf("a.jpg"), audio = listOf(JournalAudio("c.m4a", 1000))))
        val zip = ByteArrayOutputStream()
        FieldbookExport.writeZip(zip, emptyList(), FieldbookExport.NoMedia, emptyList(), ZONA,
                                 e, emptyMap(), MediaFalsa)
        val rutas = entradasDe(zip.toByteArray()).filter { it.startsWith("Journal/") }
        assertEquals(setOf("Journal/20260924_120000.jpg", "Journal/20260924_120000.m4a"),
                     rutas.toSet())
    }
}

/** Devuelve bytes para cualquier nombre: basta para comprobar rutas dentro del zip. */
private object MediaFalsa : FieldbookExport.Media {
    override fun open(name: String) = ByteArrayInputStream("xx".toByteArray())
    override fun preview(name: String): OdtWriter.Image? = null
}

private fun entradasDe(zip: ByteArray): List<String> {
    val out = ArrayList<String>()
    ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        var e = z.nextEntry
        while (e != null) { out += e.name; z.closeEntry(); e = z.nextEntry }
    }
    return out
}

private fun contenidoOdt(zip: ByteArray): String {
    ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        var e = z.nextEntry
        while (e != null) {
            if (e.name == "content.xml") return z.readBytes().toString(Charsets.UTF_8)
            z.closeEntry(); e = z.nextEntry
        }
    }
    fail("el ODT no tiene content.xml"); return ""
}

class JournalOdtTest {

    private val tresDias = listOf(
        diario("j1", "Instalacion", "primera", T0),
        diario("j2", "Tarde", "segunda", T0 + 5 * HORA),
        diario("j3", "Temporal", "tercera", T0 + DIA),
        diario("j4", "Regreso", "cuarta", T0 + 2 * DIA))

    @Test fun `los dias van del mas viejo al mas nuevo`() {
        val xml = contenidoOdt(JournalOdt.build(tresDias, zone = ZONA))
        val i1 = xml.indexOf("primera")   // 24 de septiembre
        val i3 = xml.indexOf("tercera")   // 25
        val i4 = xml.indexOf("cuarta")    // 26
        assertTrue(i1 >= 0 && i3 >= 0 && i4 >= 0, "faltan entradas: $i1 $i3 $i4")
        assertTrue(i1 < i3, "el 24 debe ir antes que el 25")
        assertTrue(i3 < i4, "el 25 debe ir antes que el 26")
        // Y cada dia lleva su fecha como encabezado.
        assertTrue(xml.contains("25 September 2026"), "falta el encabezado del 25")
    }

    @Test fun `dentro de un dia las entradas van en orden cronologico`() {
        val xml = contenidoOdt(JournalOdt.build(tresDias, zone = ZONA))
        assertTrue(xml.indexOf("primera") < xml.indexOf("segunda"))
    }

    @Test fun `el titulo del dia sale junto a la fecha`() {
        val titulos = mapOf(JournalDays.dayKey(T0 + DIA, TZ) to "Dia de temporal")
        val xml = contenidoOdt(JournalOdt.build(tresDias, titulos, zone = ZONA))
        assertTrue(xml.contains("Dia de temporal"))
    }

    @Test fun `el audio se nombra con su nombre de exportacion`() {
        val e = listOf(diario("j1", "t", "x", T0).copy(audio = listOf(JournalAudio("m9.m4a", 65000))))
        val nombres = FieldbookExport.journalMediaNames(e, ZONA)
        val xml = contenidoOdt(JournalOdt.build(e, exportNames = nombres, zone = ZONA))
        assertTrue(xml.contains("20260924_120000.m4a"), "debe citar el nombre del zip")
        assertFalse(xml.contains("m9.m4a"), "no debe citar el nombre interno")
        assertTrue(xml.contains("1:05"), "debe decir la duracion")
    }

    @Test fun `un diario vacio no se escribe en el zip`() {
        val zip = ByteArrayOutputStream()
        FieldbookExport.writeZip(zip, emptyList())
        assertFalse(entradasDe(zip.toByteArray()).contains(FieldbookExport.JOURNAL_ODT))
    }
}
