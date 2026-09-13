package cl.umag.glaciertemp.core.geo

import kotlin.test.*

/**
 * La proyeccion UTM, contrastada contra PROJ.
 *
 * Los valores esperados NO estan escritos a mano ni sacados de esta misma implementacion:
 * salen de `cs2cs` de PROJ 9.4, que es una implementacion independiente y la referencia que
 * usa medio mundo. Una formula copiada y dada por buena es exactamente lo que produce
 * coordenadas plausibles y equivocadas.
 *
 * La tolerancia es de un milimetro. El ruido de un GPS de telefono son metros, asi que
 * sobra por seis ordenes de magnitud; se aprieta tanto porque lo que se comprueba aqui no
 * es la precision util sino que la serie este bien transcrita.
 */
class UtmTest {

    private val mm = 0.001

    private data class Caso(
        val nombre: String, val lat: Double, val lon: Double,
        val zona: Int, val e: Double, val n: Double,
    )

    // cs2cs -f "%.4f" +proj=longlat +datum=WGS84 +to +proj=utm +zone=Z [+south] +ellps=WGS84
    private val referencias = listOf(
        Caso("Punta Arenas",      -53.1638, -70.9171, 19, 371836.8580, 4107791.4675),
        Caso("Glaciar Grey",      -50.9800, -73.2000, 18, 626355.2814, 4350856.9243),
        Caso("Quito",              -0.1807, -78.4678, 17, 781861.4575, 9980007.5669),
        Caso("origen",              0.0,      0.0,    31, 166021.4431,       0.0000),
        Caso("Svalbard",           78.2232,  15.6469, 33, 514738.9337, 8683360.4770),
        Caso("Noruega sur",        61.0,      5.0,    32, 283749.8233, 6769393.4160),
        Caso("borde de zona",     -53.0,    -72.0,    19, 298694.2000, 4123518.8599),
        Caso("McMurdo",           -77.8463, 166.6683, 58, 539204.2773, 1358225.3086),
        Caso("60N 10E",            60.0,     10.0,    32, 555776.2668, 6651832.7354),
    )

    @Test
    fun `coincide con PROJ hasta el milimetro`() {
        referencias.forEach { c ->
            val u = Utm.fromLatLon(c.lat, c.lon)
            assertEquals(c.zona, u.zone, "${c.nombre}: zona")
            assertEquals(c.e, u.easting, mm, "${c.nombre}: easting")
            assertEquals(c.n, u.northing, mm, "${c.nombre}: northing")
            assertEquals(c.lat >= 0, u.north, "${c.nombre}: hemisferio")
        }
    }

    @Test
    fun `la vuelta a grados devuelve el punto de partida`() {
        // La mediana se calcula en easting y northing, que es donde se ve la nube y donde
        // los metros significan metros. Para escribir un GPX hay que volver a grados, y si
        // esa vuelta no es exacta el fichero exportado describe otro sitio que la pantalla.
        // Se mide en METROS y no en grados. Un grado no vale lo mismo en Punta Arenas que
        // en Svalbard, asi que una tolerancia en grados es mas dura en unos sitios que en
        // otros sin que se vea; y lo que hay que exigirle a esto es que el GPX caiga donde
        // cayo la pantalla, que es una distancia.
        referencias.forEach { c ->
            val u = Utm.fromLatLon(c.lat, c.lon)
            val (lat, lon) = Utm.toLatLon(u)
            val vuelta = Utm.fromLatLon(lat, lon, forceZone = u.zone)
            val d = Utm.planarDistance(u, vuelta)
            assertTrue(d < 0.01, "${c.nombre}: la vuelta cae a $d m del punto de partida")
        }
    }

    @Test
    fun `las excepciones de Noruega y Svalbard no se ignoran`() {
        // No son un adorno historico: ignorarlas da coordenadas que no cuadran con ninguna
        // carta de la zona, y el error es de cientos de kilometros.
        assertEquals(32, Utm.zoneFor(61.0, 5.0), "el ensanche de la zona 32 en Noruega")
        assertEquals(31, Utm.zoneFor(61.0, 2.0), "fuera del ensanche manda la regla normal")
        assertEquals(33, Utm.zoneFor(78.2232, 15.6469), "Svalbard")
        assertEquals(31, Utm.zoneFor(78.0, 5.0))
        assertEquals(35, Utm.zoneFor(78.0, 25.0))
        assertEquals(37, Utm.zoneFor(78.0, 35.0))
        // Justo por debajo de la banda de Svalbard vuelve la regla de los seis grados.
        assertEquals(34, Utm.zoneFor(71.0, 21.0))
    }

    @Test
    fun `la banda es la letra con la que se escribe la zona`() {
        assertEquals('F', Utm.bandFor(-53.1638))
        assertEquals('M', Utm.bandFor(-0.1807))
        assertEquals('N', Utm.bandFor(0.0))
        assertEquals('X', Utm.bandFor(78.2232))
        assertEquals("19F 371837 4107791", Utm.fromLatLon(-53.1638, -70.9171).format())
    }

    @Test
    fun `forzar la zona mantiene la nube en un solo marco`() {
        // El caso que lo hace falta: promediando junto a un meridiano de zona, el ruido del
        // GPS manda unas muestras a un lado y otras al otro. Sus eastings difieren en
        // cientos de kilometros, y una nube partida en dos pone la mediana en el oceano.
        // A caballo del meridiano de 72 grados oeste, que separa las zonas 18 y 19. Dos
        // muestras a trece metros una de otra, una a cada lado.
        val izq = Utm.fromLatLon(-53.0, -72.0001)
        val der = Utm.fromLatLon(-53.0, -71.9999)
        assertEquals(18, izq.zone)
        assertEquals(19, der.zone)

        // Dejando flotar la zona, sus eastings no son comparables: restarlos da cientos de
        // kilometros para dos puntos que un cinta metrica mediria en metros.
        assertTrue(kotlin.math.abs(izq.easting - der.easting) > 100_000,
                   "el caso de prueba no esta sobre el meridiano")

        // Forzando la zona de la primera muestra, la nube se queda en un solo marco.
        val forzada = Utm.fromLatLon(-53.0, -72.0001, forceZone = der.zone)
        assertEquals(der.zone, forzada.zone)
        val d = Utm.planarDistance(forzada, der)
        assertTrue(d < 30.0, "forzando la zona quedan juntas, y quedaron a $d m")
    }

    @Test
    fun `medir entre zonas distintas es un error, no un numero raro`() {
        val a = Utm.fromLatLon(-53.0, -75.0)
        val b = Utm.fromLatLon(-53.0, -69.0)
        assertNotEquals(a.zone, b.zone)
        // Restar eastings de zonas distintas da un numero, y ese numero no significa nada.
        // Mejor que falle a que se ensene como una distancia.
        assertFailsWith<IllegalArgumentException> { Utm.planarDistance(a, b) }
    }

    @Test
    fun `la longitud se normaliza en vez de dar una zona imposible`() {
        assertEquals(Utm.zoneFor(-53.0, -70.0), Utm.zoneFor(-53.0, 290.0))
        assertEquals(1, Utm.zoneFor(0.0, -180.0))
        assertEquals(60, Utm.zoneFor(0.0, 179.9))
    }
}
