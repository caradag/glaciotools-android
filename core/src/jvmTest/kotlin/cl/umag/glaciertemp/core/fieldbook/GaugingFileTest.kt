package cl.umag.glaciertemp.core.fieldbook

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guardar, releer y exportar un aforo, con el aparato EN ESPANOL.
 *
 * El idioma se fuerza a proposito por lo que paso la sesion pasada: un formateo que mira el
 * idioma del telefono escribia coma decimal dentro de ficheros que separan columnas por
 * comas, y el registro entero quedaba ilegible al recargarlo sin dar ningun error. Un aforo
 * es justamente una tabla de decimales dentro de un CSV, asi que es el peor sitio posible
 * para volver a caer.
 */
class GaugingFileTest {

    private val previo: Locale = Locale.getDefault()
    @BeforeTest fun enEspanol() { Locale.setDefault(Locale.forLanguageTag("es-CL")) }
    @AfterTest fun restaura() { Locale.setDefault(previo) }

    private fun aforo() = FieldEntry(
        id = "gg1", type = EntryType.GAUGING, createdEpochMillis = 1790262000000L,
        person = "Camilo", profileName = "Rio Tranquilo P1",
        position = FieldPosition(latitude = -51.512345, longitude = -73.254321,
                                 altitudeMetres = 412.5, atEpochMillis = 1790262000000L),
        gauging = StreamGauging(
            widthM = 7.1, intervalM = 0.2, depthFromBed = true,
            comments = "Agua turbia,\nmolinete numero 3",
            bins = listOf(
                GaugingBin(depthM = 0.45, velocityMps = 0.25,
                           depthFirstEditMillis = 1790262060000L,
                           depthLastEditMillis = 1790262900000L,
                           velocityFirstEditMillis = 1790262120000L,
                           velocityLastEditMillis = 1790262120000L),
                // UN TRAMO VACIO EN MEDIO, a proposito: si el formato se saltara los vacios,
                // todos los de despues se correrian una posicion al releer y el dato de una
                // vertical apareceria en la de al lado.
                GaugingBin(),
                GaugingBin(depthM = 1.25, velocityMps = -0.5,
                           depthFirstEditMillis = 1790262300000L)) +
                List(33) { GaugingBin() }))

    @Test fun `un aforo sobrevive al ida y vuelta sin perder un decimal`() {
        val leida = FieldbookFile.parse(FieldbookFile.write(aforo()))
        assertNotNull(leida)
        val g = leida.gauging!!
        assertEquals("Rio Tranquilo P1", leida.profileName)
        assertEquals(7.1, g.widthM!!, 1e-12)
        assertEquals(0.2, g.intervalM!!, 1e-12)
        assertTrue(g.depthFromBed, "el toggle no se guardaba")
        assertEquals("Agua turbia,\nmolinete numero 3", g.comments,
                     "el salto de linea y la coma del comentario")
        assertEquals(36, g.bins.size, "se guardan TODOS los tramos, vacios incluidos")
    }

    @Test fun `los tramos vacios no corren a los demas de sitio`() {
        val g = FieldbookFile.parse(FieldbookFile.write(aforo()))!!.gauging!!
        assertEquals(0.45, g.bins[0].depthM!!, 1e-12)
        assertTrue(g.bins[1].isEmpty, "el hueco sigue siendo el hueco")
        assertEquals(1.25, g.bins[2].depthM!!, 1e-12, "el tercero sigue siendo el tercero")
        assertEquals(-0.5, g.bins[2].velocityMps!!, 1e-12)
    }

    @Test fun `las horas de cada casilla sobreviven`() {
        val g = FieldbookFile.parse(FieldbookFile.write(aforo()))!!.gauging!!
        assertEquals(1790262060000L, g.bins[0].depthFirstEditMillis)
        assertEquals(1790262900000L, g.bins[0].depthLastEditMillis, "la correccion posterior")
        assertEquals(1790262120000L, g.bins[0].velocityFirstEditMillis)
        assertEquals(1790262300000L, g.bins[2].depthFirstEditMillis)
        // Un tramo al que solo se le puso la profundidad no inventa hora de velocidad.
        assertEquals(null, g.bins[2].velocityFirstEditMillis)
    }

    @Test fun `el CSV del aforo no parte sus columnas ni escribe comas decimales`() {
        val csv = FieldbookCsv.gauging(aforo(), "Bernal 2026")
        val lineas = csv.lines().filter { it.isNotBlank() }
        val datos = lineas.filterNot { it.startsWith("#") }
        val cabecera = datos.first().split(",").size
        assertTrue(datos.size >= 37, "cabecera de tabla mas 36 filas")
        datos.drop(1).forEachIndexed { i, fila ->
            assertEquals(cabecera, fila.split(",").size,
                         "la fila ${i + 1} tiene otro numero de columnas: $fila")
        }
        assertTrue(csv.contains("-51.512345"), "la latitud con punto: $csv")
        assertTrue(csv.contains("0.45"), "la profundidad con punto")
    }

    @Test fun `la cabecera comentada lleva todo lo que hace falta para entender la tabla`() {
        val csv = FieldbookCsv.gauging(aforo(), "Bernal 2026")
        val cabecera = csv.lines().takeWhile { it.startsWith("#") }.joinToString("\n")
        listOf("profile: Rio Tranquilo P1", "campaign: Bernal 2026", "observer: Camilo",
               "latitude: -51.512345", "section_width_m: 7.1", "bin_interval_m: 0.2",
               "velocity_method", "velocity_depth_shown_from: bed",
               "discharge_is_complete: no", "entry_id: gg1")
            .forEach { assertTrue(it in cabecera, "falta en la cabecera: '$it'\n$cabecera") }
        // El comentario multilinea lleva # en CADA linea: si no, la segunda se leeria como
        // una fila de datos con una sola columna y descuadraria el fichero.
        assertTrue(cabecera.contains("#   Agua turbia,"))
        assertTrue(cabecera.contains("#   molinete numero 3"))
    }

    @Test fun `el CSV dice el caudal de cada tramo y el ultimo tramo es mas corto`() {
        val csv = FieldbookCsv.gauging(aforo())
        val datos = csv.lines().filterNot { it.startsWith("#") }.filter { it.isNotBlank() }
        val col = datos.first().split(",")
        val fila1 = datos[1].split(",")
        // 0,2 m de ancho x 0,45 m x 0,25 m/s = 0,0225 m3/s
        assertEquals(0.0225, fila1[col.indexOf("discharge_m3s")].toDouble(), 1e-9)
        assertEquals(0.09, fila1[col.indexOf("area_m2")].toDouble(), 1e-9)
        // Con el toggle en "desde el fondo", 0,45 m dan 0,18 m y no 0,27.
        assertEquals(0.18, fila1[col.indexOf("velocity_depth_m")].toDouble(), 1e-9)
        assertEquals("bed", fila1[col.indexOf("velocity_depth_from")])

        val ultima = datos.last().split(",")
        assertEquals(36.0, ultima[col.indexOf("bin")].toDouble(), 1e-9)
        assertEquals(7.1, ultima[col.indexOf("end_m")].toDouble(), 1e-9)
        assertEquals(0.1, ultima[col.indexOf("bin_width_m")].toDouble(), 1e-9)
        assertEquals(7.05, ultima[col.indexOf("centre_m")].toDouble(), 1e-9)
    }

    @Test fun `la exportacion pone un fichero por aforo y no los amontona`() {
        val a = aforo()
        val b = aforo().copy(id = "gg2", createdEpochMillis = 1790348400000L,
                             profileName = "Rio Tranquilo P2")
        // Dos aforos del MISMO perfil el mismo minuto: el segundo no puede pisar al primero.
        val c = aforo().copy(id = "gg3")
        val salida = java.io.ByteArrayOutputStream()
        FieldbookExport.writeZip(salida, listOf(a, b, c), zone = java.time.ZoneOffset.UTC)

        val nombres = ArrayList<String>()
        java.util.zip.ZipInputStream(salida.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; nombres += e.name }
        }
        val aforos = nombres.filter { it.startsWith(FieldbookExport.GAUGING_DIR + "/") }
        assertEquals(3, aforos.size, "uno por aforo: $nombres")
        assertEquals(3, aforos.toSet().size, "ninguno pisa a otro: $aforos")
        assertTrue(aforos.any { it.contains("Rio Tranquilo P1") })
        assertTrue(aforos.any { it.contains("Rio Tranquilo P2") })
    }
}
