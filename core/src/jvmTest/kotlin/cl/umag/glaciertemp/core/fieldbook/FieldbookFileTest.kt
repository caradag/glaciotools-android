package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.*

class FieldbookFileTest {

    private val T = 1_700_000_000_000L

    private fun vuelta(e: FieldEntry): FieldEntry =
        assertNotNull(FieldbookFile.parse(FieldbookFile.write(e)), "no se pudo releer")

    @Test
    fun `una nota general vuelve identica con sus tres clases de anotacion`() {
        val e = FieldEntry(
            id = "e1", type = EntryType.NOTE, createdEpochMillis = T, updatedEpochMillis = T + 5,
            person = "Camilo Rada",
            position = FieldPosition(-50.12345678, -73.87654321, 1234.5, 4.2,
                                     PositionSource.PHONE, atEpochMillis = T),
            items = listOf(
                NoteItem(NoteItemKind.TEXT, T, "Grieta nueva al pie del serac"),
                NoteItem(NoteItemKind.PHOTO, T + 60_000, file = "m1.jpg"),
                NoteItem(NoteItemKind.AUDIO, T + 120_000, file = "m2.m4a",
                         durationMillis = 45_000),
            ))
        assertEquals(e, vuelta(e))
    }

    @Test
    fun `una baliza vuelve con sus mediciones, fotos y la sesion GNSS de una de ellas`() {
        val e = FieldEntry(
            id = "e2", type = EntryType.STAKE, createdEpochMillis = T,
            person = "Ana", stakeName = "E12", stakeLengthCm = 200.0,
            measurements = listOf(
                StakeMeasurement(T, "Ana", 80.0, listOf("a.jpg", "b.jpg")),
                StakeMeasurement(T + 3 * 86_400_000L, "Luis", 95.0, listOf("c.jpg"),
                                 GnssSession("Emlid RS2", T + 3 * 86_400_000L,
                                             T + 3 * 86_400_000L + 1_800_000L, 30)),
            ))
        assertEquals(e, vuelta(e))
    }

    @Test
    fun `una medicion GNSS suelta y una muestra dendro vuelven identicas`() {
        val g = FieldEntry(
            id = "e3", type = EntryType.GNSS, createdEpochMillis = T, person = "Ana",
            pointName = "BASE1", photos = listOf("x.jpg"),
            gnss = GnssSession("Trimble R10", T, T + 7_200_000L, 120))
        assertEquals(g, vuelta(g))

        val d = FieldEntry(
            id = "e4", type = EntryType.DENDRO, createdEpochMillis = T, person = "Luis",
            sampleLabel = "TR-001", species = "Nothofagus pumilio",
            samplingHeightCm = 130.0, trunkPerimeterCm = 88.5,
            notes = "Tronco inclinado.\nDos taladros a 180 grados.",
            photos = listOf("p1.jpg", "p2.jpg"))
        assertEquals(d, vuelta(d))
    }

    /**
     * El caso que parte el fichero si nadie lo escapa: una nota pegada de otro sitio, con
     * saltos de linea dentro. Sin escapar, todo lo que viene detras del primer salto se lee
     * como claves desconocidas y la entrada pierde el resto de sus campos.
     */
    @Test
    fun `el texto con saltos de linea y barras sobrevive`() {
        val raro = "Linea uno\nLinea dos\r\nRuta C:\\datos\\campo\ny un = por medio"
        val e = FieldEntry(id = "e5", type = EntryType.DENDRO, createdEpochMillis = T,
                           sampleLabel = "TR-2", notes = raro)
        val leida = vuelta(e)
        assertEquals(raro, leida.notes)
        assertEquals("TR-2", leida.sampleLabel, "el campo de despues no se perdio")
    }

    /**
     * Lo que rompe un fichero escrito con `%f` sin Locale: un telefono en espanol escribe
     * la coma decimal. Aqui se comprueba que la escritura no depende del idioma activo.
     */
    @Test
    fun `los decimales se escriben con punto aunque el idioma use coma`() {
        val antes = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-CL"))
            val e = FieldEntry(id = "e6", type = EntryType.STAKE, createdEpochMillis = T,
                               stakeName = "E1", stakeLengthCm = 180.5,
                               measurements = listOf(StakeMeasurement(T, "Ana", 82.5)))
            val texto = FieldbookFile.write(e)
            assertTrue("length_cm=180.5" in texto, "escrito con coma: $texto")
            assertEquals(82.5, vuelta(e).measurements.first().exposedHeightCm)
        } finally {
            java.util.Locale.setDefault(antes)
        }
    }

    /** Una latitud tiene que volver bit a bit: redondearla mueve el punto sobre el terreno. */
    @Test
    fun `la latitud vuelve exacta y no redondeada`() {
        val lat = -50.123456789012345
        val e = FieldEntry(id = "e7", type = EntryType.NOTE, createdEpochMillis = T,
                           position = FieldPosition(lat, -73.9876543210987))
        assertEquals(lat, vuelta(e).position!!.latitude)
    }

    /** Los campos nuevos tienen que volver del disco como salieron. */
    @Test
    fun `campana, titulo y altura de antena vuelven en la ida y vuelta`() {
        val nota = FieldEntry(
            id = "n1", type = EntryType.NOTE, createdEpochMillis = T, campaignId = "c7",
            title = "Grietas del frente",
            items = listOf(NoteItem(NoteItemKind.TEXT, T, "algo")))
        assertEquals(nota, vuelta(nota))

        val punto = FieldEntry(
            id = "g1", type = EntryType.GNSS, createdEpochMillis = T, campaignId = "c7",
            pointName = "BASE1",
            gnss = GnssSession("Emlid RS2", T, T + 1000, 30, antennaHeightCm = 182.5))
        assertEquals(punto, vuelta(punto))

        val baliza = FieldEntry(
            id = "s1", type = EntryType.STAKE, createdEpochMillis = T, campaignId = "c7",
            stakeName = "E12",
            measurements = listOf(StakeMeasurement(T, "Ana", 80.0, emptyList(),
                GnssSession("Trimble", T, T + 500, null, antennaHeightCm = 160.0))))
        assertEquals(baliza, vuelta(baliza))
    }

    /**
     * Una entrada escrita por la version anterior no tiene ninguno de los campos nuevos, y
     * tiene que seguir abriendose: en el telefono del usuario ya hay entradas asi.
     */
    @Test
    fun `una entrada sin los campos nuevos se lee igual`() {
        val texto = """
            # GlacioTools fieldbook entry
            id=viejo
            type=GNSS
            created=$T
            ---
            [gnss]
            name=P1
            receiver=Emlid
            start=$T
        """.trimIndent()
        val e = assertNotNull(FieldbookFile.parse(texto))
        assertEquals("P1", e.pointName)
        assertNull(e.campaignId)
        assertNull(e.gnss?.antennaHeightCm)
        assertEquals("", e.title)
    }

    /** El titulo escrito gana; sin el, las primeras palabras del texto. */
    @Test
    fun `el titulo de una nota sale del campo o del texto`() {
        val conTitulo = FieldEntry("a", EntryType.NOTE, T, title = "  Serac  ",
            items = listOf(NoteItem(NoteItemKind.TEXT, T, "otra cosa")))
        assertEquals("Serac", conTitulo.title())

        val sinTitulo = FieldEntry("b", EntryType.NOTE, T,
            items = listOf(NoteItem(NoteItemKind.TEXT, T, "  \nGrieta nueva al pie\nsegunda linea")))
        assertEquals("Grieta nueva al pie", sinTitulo.title(),
                     "salta las lineas en blanco y se queda con la primera con texto")

        assertEquals("(untitled note)", FieldEntry("c", EntryType.NOTE, T).title())
    }

    @Test
    fun `sin id o sin tipo no hay entrada`() {
        assertNull(FieldbookFile.parse("id=e1\n---\n"), "sin tipo")
        assertNull(FieldbookFile.parse("type=NOTE\n---\n"), "sin id")
        assertNull(FieldbookFile.parse("id=e1\ntype=NOTE\n"), "sin separador")
    }

    /**
     * Una clave que no se entiende no puede tirar la entrada: un fichero escrito por una
     * version posterior de la app tiene que seguir abriendose en esta, con lo que sepa leer.
     */
    @Test
    fun `una clave desconocida se ignora y el resto se lee`() {
        val texto = """
            # GlacioTools fieldbook entry
            id=e9
            type=DENDRO
            created=$T
            inventado=algo
            ---
            [dendro]
            label=TR-9
            tambien.inventado=otra cosa
            species=Lenga
        """.trimIndent()
        val e = assertNotNull(FieldbookFile.parse(texto))
        assertEquals("TR-9", e.sampleLabel)
        assertEquals("Lenga", e.species)
    }

    /** Las mediciones se guardan ordenadas, se hayan anadido en el orden que se hayan anadido. */
    @Test
    fun `las mediciones se escriben en orden cronologico`() {
        val e = FieldEntry(
            id = "e10", type = EntryType.STAKE, createdEpochMillis = T, stakeName = "E3",
            measurements = listOf(
                StakeMeasurement(T + 2000, "Ana", 90.0),
                StakeMeasurement(T, "Ana", 80.0),
                StakeMeasurement(T + 1000, "Ana", 85.0),
            ))
        assertEquals(listOf(80.0, 85.0, 90.0),
                     vuelta(e).measurements.map { it.exposedHeightCm })
    }
}
