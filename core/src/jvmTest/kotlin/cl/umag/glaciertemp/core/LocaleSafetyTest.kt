package cl.umag.glaciertemp.core

import cl.umag.glaciertemp.core.fieldbook.CosmoSample
import cl.umag.glaciertemp.core.fieldbook.EntryType
import cl.umag.glaciertemp.core.fieldbook.FieldEntry
import cl.umag.glaciertemp.core.fieldbook.FieldbookCsv
import cl.umag.glaciertemp.core.fieldbook.FieldbookFile
import cl.umag.glaciertemp.core.fieldbook.GnssSession
import cl.umag.glaciertemp.core.sensors.HorizonPoints
import cl.umag.glaciertemp.core.sensors.PressureSample
import cl.umag.glaciertemp.core.sensors.PressureStore
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Todo lo que se escribe a un fichero o al portapapeles, con el telefono en ESPAÑOL.
 *
 * POR QUE ESTA SUITE EXISTE. `"%.3f".format(1013.25)` devuelve "1013,25" cuando el idioma del
 * aparato usa coma decimal, que en Chile es siempre. Eso no es una diferencia de presentacion:
 * el fichero de presiones separa sus columnas por comas, asi que una linea de cinco campos
 * pasaba a tener nueve y la latitud -51,5 se leia como 250. El perfil de horizonte, separado
 * por espacios, se perdia entero porque "25,30" no es un numero para toDoubleOrNull.
 *
 * Todas las pruebas de aqui corren con el idioma cambiado a proposito. El resto de la suite
 * corre en el idioma de la maquina, que en el escritorio de desarrollo es ingles -- que es
 * justo por lo que esto no se habia visto nunca.
 */
class LocaleSafetyTest {

    private val previo: Locale = Locale.getDefault()
    private val dir = File(System.getProperty("java.io.tmpdir"), "locale-" + System.nanoTime())

    @BeforeTest fun enEspanol() { Locale.setDefault(Locale.forLanguageTag("es-CL")) }

    @AfterTest fun restaura() { Locale.setDefault(previo); dir.deleteRecursively() }

    @Test fun `la coma decimal es de verdad el idioma por defecto aqui`() {
        // Si esto fallara, el resto de la suite no estaria probando nada.
        assertEquals("1013,25", String.format("%.2f", 1013.25),
                     "el entorno de prueba tiene que usar coma decimal")
    }

    @Test fun `el registro de presion sobrevive al ida y vuelta`() {
        val store = PressureStore(dir)
        val id = store.create("Campamento")
        store.append(id, PressureSample(1790262000000L, 1013.25, -51.5, -73.25, 412.0))
        val s = store.load(id)!!.samples.single()
        assertEquals(1013.25, s.hPa, 1e-6, "la presion perdia los decimales")
        assertEquals(-51.5, s.latitude!!, 1e-6, "la latitud se leia como otra cosa")
        assertEquals(-73.25, s.longitude!!, 1e-6)
        assertEquals(412.0, s.altitudeMetres!!, 1e-3)
    }

    @Test fun `el fichero de presion tiene las columnas que dice tener`() {
        val store = PressureStore(dir)
        val id = store.create("X")
        store.append(id, PressureSample(1790262000000L, 1013.25, -51.5, -73.25, 412.0))
        val linea = File(dir, "$id${PressureStore.EXTENSION}").readLines().last { it.isNotBlank() }
        assertEquals(5, linea.split(",").size,
                     "cinco columnas, no nueve: '$linea'")
    }

    @Test fun `el horizonte de una muestra cosmogenica no se pierde`() {
        // EL FALLO: el perfil se guardaba con coma decimal y al releerlo toDoubleOrNull
        // devolvia null para cada valor, asi que la lista quedaba vacia sin decir nada.
        val e = FieldEntry(
            id = "c1", type = EntryType.COSMO, createdEpochMillis = 1790262000000L,
            cosmoName = "MOR-14",
            cosmo = CosmoSample(
                horizonDeg = List(72) { 5.0 + it * 0.25 },
                manualAzimuths = listOf(0.0, 55.5, 115.0),
                manualElevations = listOf(3.5, 0.0, 5.25)))
        val leida = FieldbookFile.parse(FieldbookFile.write(e))
        assertNotNull(leida)
        val c = leida.cosmo!!
        assertEquals(72, c.horizonDeg.size, "el perfil entero se perdia")
        assertEquals(5.0, c.horizonDeg.first(), 1e-6)
        assertEquals(22.75, c.horizonDeg.last(), 1e-6)
        assertEquals(3, c.manualAzimuths.size, "los puntos a mano se perdian")
        assertEquals(55.5, c.manualAzimuths[1], 1e-6)
        assertEquals(5.25, c.manualElevations[2], 1e-6)
    }

    @Test fun `lo que se copia para la calculadora lleva punto decimal`() {
        // Se pega en una pagina que espera notacion inglesa.
        val t = HorizonPoints.toClipboard(listOf(
            HorizonPoints.Point(0.0, 3.5), HorizonPoints.Point(55.0, 0.0),
            HorizonPoints.Point(115.0, 5.0)))
        assertTrue(t.contains("3.5"), "punto decimal, no coma: '$t'")
        assertTrue(!t.contains(","), "una coma partiria los campos de la pagina: '$t'")
    }

    @Test fun `el CSV de la libreta no parte sus columnas`() {
        // La entrada NECESITA su GnssSession: gnssRows solo emite fila para las entradas GNSS
        // que tienen sesion, asi que sin ella el CSV sale con cabecera y nada debajo y la
        // prueba pasaria sin haber mirado un solo numero.
        val e = FieldEntry(
            id = "g1", type = EntryType.GNSS, createdEpochMillis = 1790262000000L,
            pointName = "E-12",
            gnss = GnssSession(receiver = "R10", startEpochMillis = 1790262000000L,
                               endEpochMillis = 1790262600000L, antennaHeightCm = 165.5),
            position = cl.umag.glaciertemp.core.fieldbook.FieldPosition(
                latitude = -51.512345, longitude = -73.254321, altitudeMetres = 412.5,
                atEpochMillis = 1790262000000L))
        val csv = FieldbookCsv.gnss(listOf(e))
        assertTrue(csv.contains("-51.512345"), "la latitud sale mal: $csv")
        val cabecera = csv.lines().first().split(",").size
        val fila = csv.lines()[1].split(",").size
        assertEquals(cabecera, fila, "la fila tiene que tener tantas columnas como la cabecera")
    }

    @Test fun `el punto GPS guarda sus coordenadas con punto`() {
        val store = cl.umag.glaciertemp.core.geo.GpsPointStore(dir)
        val id = store.create("P1")
        store.append(id, listOf(cl.umag.glaciertemp.core.geo.GpsSample(
            1790262000000L, -51.512345, -73.254321, altitudeMetres = 412.5,
            sessionStartMillis = 1790262000000L)))
        val s = store.load(id)!!.samples.single()
        assertEquals(-51.512345, s.latitude, 1e-8)
        assertEquals(-73.254321, s.longitude, 1e-8)
    }
}
