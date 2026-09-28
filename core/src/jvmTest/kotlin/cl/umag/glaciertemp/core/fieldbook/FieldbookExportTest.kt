package cl.umag.glaciertemp.core.fieldbook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlin.test.*

class FieldbookExportTest {

    private val T = 1_700_000_000_000L          // 2023-11-14 22:13 UTC
    private val DIA = 86_400_000L
    private val ZONA = ZoneId.of("UTC")

    /** Fuente de medios falsa: devuelve bytes para lo que conoce y nada para lo demas. */
    private class Medios(private val presentes: Set<String>) : FieldbookExport.Media {
        override fun open(name: String): InputStream? =
            if (name in presentes) ByteArrayInputStream("bytes de $name".toByteArray()) else null
        override fun preview(name: String): OdtWriter.Image? =
            if (name in presentes)
                OdtWriter.Image(name, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00), 800, 600)
            else null
    }

    private fun campana() = Campaign("c1", "Bernal 2026", T, null)

    private fun libreta(): List<FieldEntry> = listOf(
        FieldEntry(
            id = "e1", type = EntryType.NOTE, createdEpochMillis = T, campaignId = "c1",
            person = "Camilo Rada", title = "Grietas del frente",
            position = FieldPosition(-50.5, -73.5, 850.0, 4.0),
            items = listOf(
                NoteItem(NoteItemKind.TEXT, T, "Grieta nueva, con coma, al pie del serac."),
                NoteItem(NoteItemKind.PHOTO, T + 60_000, file = "foto1.jpg"),
                NoteItem(NoteItemKind.AUDIO, T + 120_000, file = "audio1.m4a",
                         durationMillis = 45_000),
            )),
        FieldEntry(
            id = "e2", type = EntryType.STAKE, createdEpochMillis = T + DIA,
            campaignId = "c1", person = "Ana", stakeName = "E12", stakeLengthCm = 200.0,
            position = FieldPosition(-50.51, -73.51),
            measurements = listOf(
                StakeMeasurement(T + DIA, "Ana", 80.0, listOf("foto2.jpg")),
                StakeMeasurement(T + 4 * DIA, "Luis", 95.0, emptyList(),
                                 GnssSession("Emlid RS2", T + 4 * DIA, T + 4 * DIA + 1_800_000L,
                                             30, antennaHeightCm = 182.5)),
            )),
        FieldEntry(
            id = "e3", type = EntryType.GNSS, createdEpochMillis = T + 2 * DIA,
            campaignId = "c1", person = "Ana", pointName = "BASE1",
            position = FieldPosition(-50.52, -73.52, 900.0, 0.02,
                                     PositionSource.SAVED_POINT, "BASE1", "p9"),
            gnss = GnssSession("Trimble R10", T + 2 * DIA, T + 2 * DIA + 7_200_000L, 120,
                               antennaHeightCm = 160.0),
            photos = listOf("foto3.jpg", "perdida.jpg")),
        FieldEntry(
            id = "e4", type = EntryType.DENDRO, createdEpochMillis = T + 3 * DIA,
            campaignId = "c1", person = "Luis", sampleLabel = "TR-001",
            species = "Nothofagus pumilio", samplingHeightCm = 130.0,
            trunkPerimeterCm = 88.5, notes = "Tronco inclinado.\nDos taladros."),
    )

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
                z.closeEntry()
            }
        }
        return out
    }

    private fun exportar(): Pair<Map<String, ByteArray>, FieldbookExport.Result> {
        val salida = ByteArrayOutputStream()
        val r = FieldbookExport.writeZip(
            salida, libreta(),
            Medios(setOf("foto1.jpg", "foto2.jpg", "foto3.jpg", "audio1.m4a")),
            listOf(campana()), ZONA)
        return zipEntries(salida.toByteArray()) to r
    }

    // ------------------------------------ estructura ------------------------------------

    @Test
    fun `el zip trae los tres csv, el odt y los medios en carpetas por punto`() {
        val (z, r) = exportar()
        assertTrue(FieldbookExport.GNSS_CSV in z)
        assertTrue(FieldbookExport.STAKE_CSV in z)
        assertTrue(FieldbookExport.DENDRO_CSV in z)
        assertTrue(FieldbookExport.NOTEBOOK_ODT in z)

        assertTrue("Pictures/E12/foto2.jpg" in z, z.keys.toString())
        assertTrue("Pictures/BASE1/foto3.jpg" in z, z.keys.toString())
        assertTrue("Audio/Grietas del frente/audio1.m4a" in z, z.keys.toString())

        assertEquals(4, r.entries)
        assertEquals(3, r.photos)
        assertEquals(1, r.audioNotes)
        assertEquals(1, r.missingMedia, "perdida.jpg ya no esta en el telefono")
    }

    /**
     * Una foto que ya no esta NO puede abortar la exportacion. Perderla es malo; quedarse sin
     * exportar la campana entera por culpa de una foto borrada es peor.
     */
    @Test
    fun `una foto que falta se salta, se cuenta, y el resto sale igual`() {
        val (z, r) = exportar()
        assertFalse(z.keys.any { it.endsWith("perdida.jpg") })
        assertTrue(r.describe().contains("1 file(s) missing"), r.describe())
        assertTrue("Pictures/BASE1/foto3.jpg" in z, "la otra foto del mismo punto sigue ahi")
    }

    /**
     * Dos puntos con nombres que se limpian igual no pueden compartir carpeta: sus fotos se
     * mezclarian sin que nada lo dijera.
     */
    @Test
    fun `dos nombres que limpian igual reciben carpetas distintas`() {
        val entradas = listOf(
            FieldEntry("a", EntryType.GNSS, T, pointName = "E/12"),
            FieldEntry("b", EntryType.GNSS, T + 1, pointName = "E:12"),
        )
        val carpetas = FieldbookExport.folderFor(entradas)
        assertEquals(2, carpetas.values.toSet().size, carpetas.toString())
        assertEquals("E_12", carpetas["a"])
        assertEquals("E_12 (2)", carpetas["b"])
    }

    // --------------------------------------- CSV ---------------------------------------

    @Test
    fun `el csv de GNSS junta los puntos sueltos y los de baliza`() {
        val csv = FieldbookCsv.gnss(libreta()) { "Bernal 2026" }
        val filas = csv.trim().lines()
        assertEquals(3, filas.size, "cabecera mas dos ocupaciones")
        assertTrue(filas[0].startsWith("point_name,source,campaign"))
        // Cronologico: BASE1 es del dia 2, la de la baliza del dia 4.
        assertTrue(filas[1].startsWith("BASE1,GNSS,"), filas[1])
        assertTrue(filas[2].startsWith("E12,STAKE,"), filas[2])
        assertTrue("182.5" in filas[2], "la altura de antena de la baliza: ${filas[2]}")
        assertTrue(filas[1].endsWith(",2,e3"), "dos fotos asociadas: ${filas[1]}")
    }

    @Test
    fun `el csv de balizas trae la tasa entre lecturas consecutivas`() {
        val csv = FieldbookCsv.stakes(libreta())
        val filas = csv.trim().lines()
        assertEquals(3, filas.size)
        val primera = filas[1].split(",")
        val segunda = filas[2].split(",")
        assertEquals("", primera[8], "la primera lectura no tiene tasa")
        // 80 -> 95 cm en 3 dias = 5 cm/dia.
        assertEquals(5.0, segunda[8].toDouble(), 1e-9, filas[2])
        assertEquals(3.0, segunda[7].toDouble(), 1e-9, "dias transcurridos")
    }

    @Test
    fun `el csv de dendro escapa comas y saltos de linea en las notas`() {
        val csv = FieldbookCsv.dendro(libreta())
        assertTrue("\"Tronco inclinado.\nDos taladros.\"" in csv, csv)
        // Y sigue teniendo exactamente dos lineas logicas: cabecera y una muestra.
        assertEquals(1, csv.lines().count { it.startsWith("TR-001") })
    }

    /**
     * Lo que rompe un CSV en un telefono en espanol: `%f` sin Locale escribe coma decimal, y
     * la coma ya es el separador de campos.
     */
    @Test
    fun `los decimales del csv usan punto aunque el idioma use coma`() {
        val antes = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-CL"))
            val csv = FieldbookCsv.stakes(libreta())
            assertTrue("182.5" in FieldbookCsv.gnss(libreta()), "altura de antena")
            assertTrue(csv.lines()[2].split(",")[8].startsWith("5"), csv.lines()[2])
        } finally {
            java.util.Locale.setDefault(antes)
        }
    }

    // --------------------------------------- ODT ---------------------------------------

    /**
     * La regla rara de ODF: `mimetype` tiene que ser la PRIMERA entrada y estar SIN comprimir.
     * Un zip por lo demas correcto que no la cumpla lo rechazan LibreOffice y Word sin decir
     * por que -- y eso solo se descubre abriendo el fichero, nunca escribiendolo.
     */
    @Test
    fun `el odt cumple la regla del mimetype`() {
        val odt = FieldbookOdt.build(libreta(), FieldbookExport.NoMedia, listOf(campana()), ZONA)
        ZipInputStream(ByteArrayInputStream(odt)).use { z ->
            val primera = assertNotNull(z.nextEntry)
            assertEquals("mimetype", primera.name)
            assertEquals(java.util.zip.ZipEntry.STORED, primera.method,
                         "tiene que ir sin comprimir")
            assertEquals("application/vnd.oasis.opendocument.text",
                         z.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `el odt trae content, styles y manifest, y el xml es valido`() {
        val z = zipEntries(FieldbookOdt.build(libreta(), Medios(setOf("foto1.jpg")),
                                              listOf(campana()), ZONA))
        listOf("content.xml", "styles.xml", "META-INF/manifest.xml").forEach {
            assertTrue(it in z, "falta $it")
        }
        // Se parsea de verdad: un & sin escapar o un caracter de control lo tumbarian.
        val fabrica = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        fabrica.isNamespaceAware = true
        listOf("content.xml", "styles.xml", "META-INF/manifest.xml").forEach { nombre ->
            fabrica.newDocumentBuilder().parse(ByteArrayInputStream(z[nombre]!!))
        }
        assertTrue("Pictures/foto1.jpg" in z, "la vista previa va dentro del odt")
    }

    @Test
    fun `el odt incrusta la vista previa y declara la imagen en el manifest`() {
        val z = zipEntries(FieldbookOdt.build(libreta(), Medios(setOf("foto1.jpg")),
                                              listOf(campana()), ZONA))
        val manifest = z["META-INF/manifest.xml"]!!.toString(Charsets.UTF_8)
        assertTrue("""manifest:full-path="Pictures/foto1.jpg"""" in manifest, manifest)
        val content = z["content.xml"]!!.toString(Charsets.UTF_8)
        assertTrue("""xlink:href="Pictures/foto1.jpg"""" in content)
        // 800x600 al ancho de 7.5 cm dan 5.625 cm de alto: la proporcion se conserva.
        assertTrue("""svg:height="5.625cm"""" in content, content.substringAfter("draw:frame").take(300))
    }

    @Test
    fun `una foto ausente se dice en el documento en vez de dejar un hueco`() {
        val z = zipEntries(FieldbookOdt.build(libreta(), FieldbookExport.NoMedia,
                                              listOf(campana()), ZONA))
        val content = z["content.xml"]!!.toString(Charsets.UTF_8)
        assertTrue("is no longer on the phone" in content, "el hueco tiene que decirse")
    }

    /**
     * Un `&` o un caracter de control en una nota tumba el XML entero. Llegan desde texto
     * pegado mas a menudo de lo que parece.
     */
    @Test
    fun `un ampersand y un caracter de control no rompen el documento`() {
        val entradas = listOf(FieldEntry(
            "x", EntryType.DENDRO, T, sampleLabel = "A & B",
            notes = "uno \u0001 dos <tres> \"cuatro\""))
        val z = zipEntries(FieldbookOdt.build(entradas, FieldbookExport.NoMedia, emptyList(), ZONA))
        val fabrica = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        fabrica.isNamespaceAware = true
        fabrica.newDocumentBuilder().parse(ByteArrayInputStream(z["content.xml"]!!))
        val content = z["content.xml"]!!.toString(Charsets.UTF_8)
        assertTrue("A &amp; B" in content)
        assertFalse("\u0001" in content, "el caracter de control tiene que desaparecer")
    }

    @Test
    fun `el odt ordena cronologicamente y cruza los cuatro tipos`() {
        val z = zipEntries(FieldbookOdt.build(libreta(), FieldbookExport.NoMedia,
                                              listOf(campana()), ZONA))
        val content = z["content.xml"]!!.toString(Charsets.UTF_8)
        val orden = listOf("Grietas del frente", "E12", "BASE1", "TR-001")
            .map { content.indexOf(it) }
        assertTrue(orden.all { it >= 0 }, "faltan entradas: $orden")
        // La nota es del dia 0, la baliza del 1, el punto del 2 y la muestra del 3.
        assertEquals(orden.sorted(), orden, "no estan en orden cronologico")
    }

    @Test
    fun `una libreta vacia produce un documento que se abre igual`() {
        val z = zipEntries(FieldbookOdt.build(emptyList(), FieldbookExport.NoMedia,
                                              emptyList(), ZONA))
        val fabrica = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        fabrica.isNamespaceAware = true
        fabrica.newDocumentBuilder().parse(ByteArrayInputStream(z["content.xml"]!!))
        assertTrue("no entries" in z["content.xml"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `la altura de antena que falta se dice en el documento, no se calla`() {
        val entradas = listOf(FieldEntry(
            "g", EntryType.GNSS, T, pointName = "P1",
            gnss = GnssSession("Emlid", T, T + 600_000L)))
        val z = zipEntries(FieldbookOdt.build(entradas, FieldbookExport.NoMedia, emptyList(), ZONA))
        assertTrue("NOT RECORDED" in z["content.xml"]!!.toString(Charsets.UTF_8))
    }
}
