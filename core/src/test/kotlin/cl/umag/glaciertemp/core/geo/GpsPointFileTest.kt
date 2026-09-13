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
            assertEquals(5, linea.trim().split(",").size, "columnas de mas: $linea")
            assertNotNull(GpsPointFile.parse(fichero()))
        } finally {
            java.util.Locale.setDefault(previo)
        }
    }
}
