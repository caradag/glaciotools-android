package cl.umag.glaciertemp.core.geo

import kotlin.test.*

class GpsPointFileTest {

    private val cabecera = GpsPointFile.Header("p-1", "Estaca 3", 1_700_000_000_000L)
    private val muestras = listOf(
        GpsSample(1_700_000_000_000L, -53.16380000, -70.91710000, 120.4, 4.0),
        GpsSample(1_700_000_001_000L, -53.16380500, -70.91709000, 121.1, 3.5),
        GpsSample(1_700_000_002_000L, -53.16379500, -70.91711000, null, null),
    )

    private fun fichero() =
        GpsPointFile.header(cabecera) + muestras.joinToString("") { GpsPointFile.sampleLine(it) }

    @Test
    fun `lo escrito se vuelve a leer igual`() {
        val p = assertNotNull(GpsPointFile.parse(fichero()))
        assertEquals(cabecera, p.header)
        assertEquals(3, p.samples.size)
        assertEquals(0, p.skipped)
        muestras.zip(p.samples).forEach { (a, b) ->
            assertEquals(a.epochMillis, b.epochMillis)
            assertEquals(a.latitude, b.latitude, 1e-8)
            assertEquals(a.longitude, b.longitude, 1e-8)
            assertEquals(a.altitudeMetres, b.altitudeMetres)
            assertEquals(a.accuracyMetres, b.accuracyMetres)
        }
    }

    @Test
    fun `seguir promediando es anadir lineas al final`() {
        // La propiedad que hace util el formato: una segunda visita al mismo punto no
        // reescribe nada. Si reescribiera, una interrupcion a mitad costaria el punto entero.
        val despues = fichero() + GpsPointFile.sampleLine(
            GpsSample(1_700_003_000_000L, -53.16381000, -70.91712000, 119.8, 5.0))
        val p = assertNotNull(GpsPointFile.parse(despues))
        assertEquals(4, p.samples.size)
        assertEquals("Estaca 3", p.header.name)
    }

    @Test
    fun `una linea a medias cuesta esa linea, no el fichero`() {
        // Se queda sin bateria escribiendo. Tirar cientos de muestras buenas por culpa de la
        // ultima seria perder trabajo de campo que no se puede repetir.
        val roto = fichero() + "1700000003000,-53.1638"
        val p = assertNotNull(GpsPointFile.parse(roto))
        assertEquals(3, p.samples.size)
        assertEquals(1, p.skipped, "se saltó en silencio en vez de contarlo")
    }

    @Test
    fun `un nombre con saltos de linea no parte el fichero`() {
        val h = GpsPointFile.Header("p-2", "Estaca\n3\r(sur)", 1L)
        val p = assertNotNull(GpsPointFile.parse(GpsPointFile.header(h)))
        assertEquals("Estaca 3 (sur)", p.header.name)
        assertEquals(0, p.samples.size)
    }

    @Test
    fun `las marcas separan los tramos, y repetirlas no abre uno nuevo`() {
        val a = muestras.map { it.copy(sessionStartMillis = 1000L) }
        val b = muestras.map { it.copy(sessionStartMillis = 9000L, epochMillis = it.epochMillis + 999999) }
        // Dos guardados dentro del MISMO tramo: la marca se escribe dos veces a proposito,
        // porque asi quien escribe no necesita saber que habia ya en el fichero.
        val texto = GpsPointFile.header(cabecera) +
                    GpsPointFile.appendBlock(a.take(2)) +
                    GpsPointFile.appendBlock(a.drop(2)) +
                    GpsPointFile.appendBlock(b)

        val p = assertNotNull(GpsPointFile.parse(texto))
        assertEquals(listOf(1000L, 9000L), p.sessionStarts, "la marca repetida partio el tramo")
        assertEquals(6, p.samples.size)
        assertEquals(0, p.skipped, "las marcas se contaron como lineas rotas")
    }

    @Test
    fun `un fichero sin marcas reparte los tramos por los huecos`() {
        // Ficheros escritos antes de que existieran las marcas. Tratar dos visitas como una
        // sola haria creer que hay mucha mas informacion independiente de la que hay.
        val seguidas = (0 until 5).map {
            GpsSample(1_700_000_000_000L + it * 1000L, -53.1638, -70.9171) }
        val otroDia = (0 until 5).map {
            GpsSample(1_700_500_000_000L + it * 1000L, -53.1638, -70.9171) }
        val texto = GpsPointFile.header(cabecera) +
                    (seguidas + otroDia).joinToString("") { GpsPointFile.sampleLine(it) }

        val p = assertNotNull(GpsPointFile.parse(texto))
        assertEquals(2, p.sessionStarts.size, "no separo las dos visitas")
        assertEquals(1_700_000_000_000L, p.samples.first().sessionStartMillis)
        assertEquals(1_700_500_000_000L, p.samples.last().sessionStartMillis)
    }

    @Test
    fun `un fichero que no lo es se rechaza en vez de dar un punto vacio`() {
        assertNull(GpsPointFile.parse("cualquier cosa"))
        assertNull(GpsPointFile.parse(""))
        // Sin id no hay punto: un punto sin identidad no se puede volver a abrir.
        assertNull(GpsPointFile.parse("name=x\n---\n"))
    }

    @Test
    fun `el punto decimal es el punto, este el telefono donde este`() {
        // Con la configuracion regional del telefono en castellano, "%f" escribe comas y el
        // CSV resultante tiene el doble de columnas de las que dice la cabecera.
        val previo = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-CL"))
            val linea = GpsPointFile.sampleLine(muestras[0])
            assertFalse(linea.contains(","+"53"), "escribio la latitud con coma decimal: $linea")
            assertEquals(GpsPointFile.COLUMNS.split(",").size,
                         linea.trim().split(",").size, "columnas de mas: $linea")
            assertNotNull(GpsPointFile.parse(fichero()))
        } finally {
            java.util.Locale.setDefault(previo)
        }
    }
}
