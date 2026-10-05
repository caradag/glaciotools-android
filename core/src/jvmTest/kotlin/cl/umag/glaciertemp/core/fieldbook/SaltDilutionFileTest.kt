package cl.umag.glaciertemp.core.fieldbook

import java.time.ZoneId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Guardar, releer y exportar la dilucion de sal, con el telefono en espanol. */
class SaltDilutionFileTest {

    private val previo: Locale = Locale.getDefault()
    @BeforeTest fun enEspanol() { Locale.setDefault(Locale.forLanguageTag("es-CL")) }
    @AfterTest fun restaura() { Locale.setDefault(previo) }

    private val T0 = 1_790_262_000_000L

    private fun nota() = FieldEntry(
        id = "sd1", type = EntryType.GAUGING, createdEpochMillis = T0, person = "Camilo",
        profileName = "Estero P2", photos = listOf("a.jpg", "b.jpg"),
        position = FieldPosition(-51.5, -73.2),
        gauging = StreamGauging(
            widthM = 2.0, intervalM = 0.5,
            bins = listOf(GaugingBin(depthM = 0.3, velocityMps = 0.4), GaugingBin(), GaugingBin(), GaugingBin()),
            method = GaugingMethod.SALT_DILUTION, locked = true,
            salt = SaltDilution(
                saltMassG = 1234.5,
                injectionPosition = FieldPosition(-51.6, -73.3, 400.0, 4.0, PositionSource.SAVED_POINT,
                                                  "Iny", "p9", T0 - 5),
                injectionEpochMillis = T0 + 60_000,
                injectionDistanceM = 75.5,
                injectionNotes = "Bajo el puente,\nlado izquierdo",
                calibrationFactor = 0.4987,
                calibration = SaltCalibration(points = 3,
                                              conductivities = listOf(30.1, null, 42.25, 48.0)),
                readings = listOf(ConductivityReading(T0 + 61_000, 30.0),
                                  ConductivityReading(T0 + 66_000, 85.5),
                                  ConductivityReading(T0 + 71_000, 31.0)),
                readingsSource = ReadingsSource.IMPORTED,
                importedFile = "hobo 1.csv",
                baseConductivity = 30.0,
                windowStartMillis = T0 + 60_000,
                windowEndMillis = T0 + 80_000,
                calculated = true)))

    @Test fun `la dilucion sobrevive al ida y vuelta`() {
        val original = nota()
        val leida = FieldbookFile.parse(FieldbookFile.write(original))
        assertNotNull(leida)
        assertEquals(original, leida, "todo, campo a campo")
    }

    @Test fun `las fotos de un aforo con tramos sobreviven al releer`() {
        // EL DEFECTO QUE HABIA: las fotos se escribian detras de los [bin], caian en el
        // bloque del ultimo tramo y al releer no estaban. El barrido de medios sueltos las
        // borraba despues del disco.
        val e = nota().let { it.copy(gauging = it.gauging!!.copy(salt = null)) }
        val leida = FieldbookFile.parse(FieldbookFile.write(e))!!
        assertEquals(listOf("a.jpg", "b.jpg"), leida.photos)
    }

    @Test fun `un fichero escrito por la version anterior conserva sus fotos`() {
        // Tal como lo escribia la 2.36: fotos al final, dentro del ultimo [bin].
        val viejo = """
            # GlacioTools fieldbook entry
            id=old1
            type=GAUGING
            created=1790262000000
            ---
            [gauging]
            profile=P
            width_m=1.0
            interval_m=0.5
            depth_from_bed=0
            [bin]
            depth_m=0.2
            [bin]
            photo=x.jpg
            photo=y.jpg
        """.trimIndent()
        val e = FieldbookFile.parse(viejo)!!
        assertEquals(listOf("x.jpg", "y.jpg"), e.photos)
        assertEquals(2, e.gauging!!.bins.size)
        assertEquals(GaugingMethod.VELOCITY_AREA, e.gauging!!.method)
        assertEquals(null, e.gauging!!.salt)
        assertEquals(false, e.gauging!!.locked)
    }

    @Test fun `el CSV de la dilucion lleva lo necesario para rehacer la cuenta`() {
        val csv = FieldbookCsv.saltDilution(nota(), "Bernal", ZoneId.of("America/Punta_Arenas"))
        assertTrue("# salt_mass_g: 1234.5" in csv, csv)
        assertTrue("# calibration_factor_mgl_per_uscm: 0.4987" in csv)
        assertTrue("# base_conductivity_uscm: 30" in csv)
        assertTrue("# injection_to_measurement_m: 75.5" in csv)
        assertTrue("#   Bajo el puente," in csv && "#   lado izquierdo" in csv)
        // Sigma = trapecios de 5 s: (0+55,5)/2*5 + (55,5+1)/2*5 = 138,75 + 141,25 = 280
        assertTrue("# sigma_uscm_s: 280" in csv, csv)
        val q = 1234.5 / (0.4987 * 280.0)
        assertTrue("# discharge_m3s: ${FieldbookCsv.num(q)}" in csv)
        val datos = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("time,seconds_after_injection,conductivity_uscm,excess_uscm,in_window", datos[0])
        assertEquals(4, datos.size)
        assertTrue(datos[2].endsWith(",6,85.5,55.5,1"), datos[2])
        assertTrue(",," !in datos[1].substringAfter(','), "sin comas decimales colandose")
    }

    @Test fun `el zip trae el CSV de la dilucion junto al del perfil`() {
        val out = java.io.ByteArrayOutputStream()
        FieldbookExport.writeZip(out, listOf(nota()), FieldbookExport.NoMedia, emptyList(),
                                 ZoneId.of("UTC"))
        val nombres = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z ->
            generateSequence { z.nextEntry }.map { it.name }.toList()
        }
        assertTrue(nombres.any { it.startsWith(FieldbookExport.GAUGING_DIR) && it.endsWith(" salt dilution.csv") },
                   nombres.toString())
        assertTrue(nombres.any { it.startsWith(FieldbookExport.GAUGING_DIR) && it.endsWith("Z.csv") ||
                                 it.startsWith(FieldbookExport.GAUGING_DIR) && !it.contains("salt") },
                   nombres.toString())
    }
}
