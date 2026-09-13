package cl.umag.glaciertemp.core.geo

import kotlin.test.*

class GpsExportTest {

    private val muestras = (0 until 20).map { i ->
        GpsSample(
            epochMillis = 1_700_000_000_000L + i * 1000L,
            latitude = -53.1638 + (i % 5 - 2) * 0.00001,
            longitude = -70.9171 + (i % 3 - 1) * 0.00001,
            altitudeMetres = 120.0 + (i % 4 - 1.5),
            accuracyMetres = 4.0,
        )
    }
    private val stats = GpsAverager().apply { addAll(muestras) }.stats()!!

    @Test
    fun `el csv de muestras trae una fila por muestra y su proyeccion`() {
        val csv = GpsExport.csvSamples("Estaca 3", stats, muestras)
        val lineas = csv.trim().lines()
        val datos = lineas.filterNot { it.startsWith("#") }
        assertEquals(1 + muestras.size, datos.size, "faltan o sobran filas")
        assertTrue(datos[0].startsWith("index,time_utc,"))
        val primera = datos[1].split(",")
        assertEquals("1", primera[0])
        assertEquals("2023-11-14T22:13:20Z", primera[1])
        // El easting proyectado tiene que coincidir con proyectar esa muestra a mano, en la
        // zona del punto: si el export proyectara por su cuenta podria elegir otra.
        val esperado = Utm.fromLatLon(muestras[0].latitude, muestras[0].longitude,
                                      forceZone = stats.zone)
        assertEquals(esperado.easting, primera[6].toDouble(), 0.01)
    }

    @Test
    fun `el csv promedio lleva la calidad y no solo la posicion`() {
        // Un punto sin una medida de su calidad no se puede combinar con otro ni descartar
        // cuando no da la talla, y quien lo use dentro de dos anos no es quien lo midio.
        val csv = GpsExport.csvAverage("Estaca 3", stats)
        val (cab, fila) = csv.trim().lines().let { it[0].split(",") to it[1].split(",") }
        assertEquals(cab.size, fila.size, "la fila no cuadra con la cabecera")
        listOf("easting_sd_m", "horizontal_se_m", "samples", "zone").forEach {
            assertTrue(it in cab, "falta la columna $it")
        }
        assertEquals("20", fila[cab.indexOf("samples")])
        assertEquals(stats.zone.toString(), fila[cab.indexOf("zone")])
    }

    @Test
    fun `un nombre con coma no corre las columnas del csv`() {
        val csv = GpsExport.csvAverage("Estaca 3, sur", stats)
        val cab = csv.lines()[0].split(",")
        val fila = csv.lines()[1]
        assertTrue(fila.startsWith("\"Estaca 3, sur\""), "no entrecomillo el nombre: $fila")
        // Y al leerlo con un lector de CSV de verdad, las columnas siguen cuadrando.
        assertEquals(cab.size, splitCsv(fila).size)
    }

    @Test
    fun `el gpx promedio es un waypoint con su calidad en la descripcion`() {
        val gpx = GpsExport.gpxAverage("Estaca 3", stats)
        assertTrue(gpx.startsWith("<?xml"))
        assertEquals(1, Regex("<wpt ").findAll(gpx).count())
        assertTrue("<name>Estaca 3</name>" in gpx)
        assertTrue("Median of 20 fixes" in gpx, "el waypoint no dice de donde sale")
        assertTrue("uncertainty of the estimate" in gpx)
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
        // La latitud del waypoint es la de la mediana proyectada, no la de una muestra.
        val lat = Regex("""lat="([-\d.]+)"""").find(gpx)!!.groupValues[1].toDouble()
        assertEquals(stats.medianLatitude, lat, 1e-7)
    }

    @Test
    fun `el gpx de muestras es una traza y no mil waypoints apilados`() {
        val gpx = GpsExport.gpxSamples("Estaca 3", muestras)
        assertEquals(0, Regex("<wpt ").findAll(gpx).count(),
                     "mil waypoints en el mismo sitio hacen ilegible cualquier mapa")
        assertEquals(muestras.size, Regex("<trkpt ").findAll(gpx).count())
        assertTrue("<time>2023-11-14T22:13:20Z</time>" in gpx)
    }

    @Test
    fun `un nombre con caracteres de xml no rompe el fichero`() {
        val gpx = GpsExport.gpxAverage("Estaca <3> & \"sur\"", stats)
        assertTrue("<name>Estaca &lt;3&gt; &amp; &quot;sur&quot;</name>" in gpx)
        // Y sigue siendo XML que un parser acepta.
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(gpx.byteInputStream())
        assertEquals("gpx", doc.documentElement.nodeName)
    }

    @Test
    fun `el gpx de muestras tambien es xml valido`() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(GpsExport.gpxSamples("Estaca 3", muestras).byteInputStream())
        assertEquals(muestras.size, doc.getElementsByTagName("trkpt").length)
    }

    @Test
    fun `sin altitud el gpx no inventa una elevacion`() {
        val sinAlt = muestras.map { it.copy(altitudeMetres = null) }
        val s = GpsAverager().apply { addAll(sinAlt) }.stats()!!
        assertNull(s.altitude)
        assertFalse("<ele>" in GpsExport.gpxAverage("x", s))
        assertFalse("<ele>" in GpsExport.gpxSamples("x", sinAlt))
    }

    /** Un partidor de CSV que respeta las comillas, para comprobar el entrecomillado. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var dentro = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && dentro && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> dentro = !dentro
                c == ',' && !dentro -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
