package cl.umag.glaciertemp.core.fieldbook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1790262000000L

private fun muestra() = FieldEntry(
    id = "c1", type = EntryType.COSMO, createdEpochMillis = T0,
    person = "Camilo", campaignId = "camp1",
    cosmoName = "MOR-14",
    position = FieldPosition(-51.0, -73.0, altitudeMetres = 412.0,
                             source = PositionSource.PHONE, atEpochMillis = T0),
    cosmo = CosmoSample(
        site = "Morrena lateral derecha, cordon externo.",
        place = "Bloque aislado sobre la cresta.\nSin senales de haber rodado.",
        heightMaxM = 1.8, heightMinM = 0.9, longAxisM = 3.2, shortAxisM = 2.1,
        boulder = "Granodiorita, grano medio.",
        surface = "Pulido glaciar preservado en la cara norte; liquen escaso.",
        strikeDeg = 120.0, dipDeg = 12.0,
        horizonDeg = List(72) { 5.0 + it * 0.1 },
        shieldingFactor = 0.9871),
    photos = listOf("a.jpg"))

class CosmoFileTest {

    @Test fun `la muestra sobrevive al ida y vuelta del fichero`() {
        val leida = FieldbookFile.parse(FieldbookFile.write(muestra()))
        assertNotNull(leida)
        assertEquals(EntryType.COSMO, leida.type)
        assertEquals("MOR-14", leida.cosmoName)
        val c = leida.cosmo!!
        assertEquals(1.8, c.heightMaxM!!, 1e-9)
        assertEquals(0.9, c.heightMinM!!, 1e-9)
        assertEquals(3.2, c.longAxisM!!, 1e-9)
        assertEquals(2.1, c.shortAxisM!!, 1e-9)
        assertEquals(120.0, c.strikeDeg!!, 1e-9)
        assertEquals(12.0, c.dipDeg!!, 1e-9)
        assertEquals(0.9871, c.shieldingFactor!!, 1e-6)
        assertEquals(listOf("a.jpg"), leida.photos)
    }

    @Test fun `los saltos de linea de las descripciones no parten el fichero`() {
        // "Place" y las descripciones son parrafos: si el salto de linea se escribiera crudo,
        // la segunda linea se leeria como una clave nueva y el resto de la nota se perderia.
        val leida = FieldbookFile.parse(FieldbookFile.write(muestra()))!!
        assertTrue(leida.cosmo!!.place.contains("\n"), "el parrafo debe conservar su salto")
        assertEquals("Bloque aislado sobre la cresta.\nSin senales de haber rodado.",
                     leida.cosmo!!.place)
    }

    @Test fun `el perfil de horizonte vuelve entero`() {
        // Es LA MEDIDA: el factor se puede recalcular, el horizonte no se vuelve a tomar sin
        // regresar al sitio.
        val c = FieldbookFile.parse(FieldbookFile.write(muestra()))!!.cosmo!!
        assertEquals(72, c.horizonDeg.size)
        assertEquals(5.0, c.horizonDeg.first(), 1e-6)
        assertEquals(12.1, c.horizonDeg.last(), 1e-6)
        assertEquals(5, c.horizonBinDeg)
    }

    @Test fun `una muestra vacia no se guarda como si tuviera datos`() {
        val vacia = FieldEntry("c2", EntryType.COSMO, T0, cosmoName = "X")
        val leida = FieldbookFile.parse(FieldbookFile.write(vacia))!!
        assertEquals("X", leida.cosmoName)
        assertNull(leida.cosmo)
    }

    @Test fun `el nombre de la muestra no se confunde con el de un dendro`() {
        // Campos distintos a proposito: un dendro y una muestra cosmogenica pueden llamarse
        // igual sin que el aviso de duplicado salte entre tipos.
        val e = muestra()
        assertEquals("", e.sampleLabel)
        assertEquals("MOR-14", e.title())
    }
}

class CosmoExportTest {

    private fun entradasDe(zip: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var e = z.nextEntry
            while (e != null) { out += e.name; z.closeEntry(); e = z.nextEntry }
        }
        return out
    }

    @Test fun `el zip lleva su propio csv`() {
        val zip = ByteArrayOutputStream()
        FieldbookExport.writeZip(zip, listOf(muestra()))
        assertTrue(entradasDe(zip.toByteArray()).contains(FieldbookExport.COSMO_CSV))
    }

    @Test fun `el csv lleva las medidas y las descripciones`() {
        val csv = FieldbookCsv.cosmo(listOf(muestra()), campaignName = { "Campana" })
        assertTrue(csv.contains("MOR-14"), csv)
        assertTrue(csv.contains("0.9871"), "falta el factor: $csv")
        assertTrue(csv.contains("Granodiorita"), "las descripciones van en el csv: $csv")
        assertTrue(csv.contains("yes"), "debe decir que hay horizonte medido")
        // El parrafo de "place" lleva un salto de linea, asi que el registro ocupa dos
        // lineas fisicas. Lo que importa es que vaya ENTRECOMILLADO: asi una hoja de
        // calculo lo lee como un solo registro y no como dos filas rotas.
        assertTrue(csv.contains("\"Bloque aislado sobre la cresta.\nSin senales"),
                   "el salto de linea debe ir dentro de comillas: $csv")
    }

    @Test fun `sin horizonte medido se dice que no`() {
        val sinHorizonte = muestra().copy(
            cosmo = muestra().cosmo!!.copy(horizonDeg = emptyList()))
        val csv = FieldbookCsv.cosmo(listOf(sinHorizonte))
        assertTrue(csv.lines()[1].contains(",no,") || csv.lines()[1].contains("\"no\""),
                   "debe marcar que no hay horizonte: ${csv.lines()[1]}")
    }

    @Test fun `la busqueda encuentra por el texto de las descripciones`() {
        val r = FieldbookSearch.search("granodiorita", listOf(muestra()))
        assertEquals(1, r.size)
        assertEquals("Boulder", r[0].field)
        val r2 = FieldbookSearch.search("pulido", listOf(muestra()))
        assertEquals("Surface", r2[0].field)
    }

    @Test fun `las fotos van a una carpeta con el nombre de la muestra`() {
        assertEquals("MOR-14", FieldbookExport.folderFor(listOf(muestra()))["c1"])
    }
}
