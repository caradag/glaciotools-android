package cl.umag.glaciertemp.core.geo

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class GpsAveragingTest {

    private val lat0 = -53.1638
    private val lon0 = -70.9171

    /** Muestras alrededor de un punto, con ruido gaussiano de [sigma] metros. */
    private fun nube(n: Int, sigma: Double, semilla: Int = 7,
                     lat: Double = lat0, lon: Double = lon0): List<GpsSample> {
        val r = Random(semilla)
        fun gauss(): Double {
            // Box-Muller: kotlin.random no trae normales.
            val u1 = r.nextDouble().coerceAtLeast(1e-12)
            val u2 = r.nextDouble()
            return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
        }
        // Un grado de latitud son ~111320 m; de longitud, eso por el coseno de la latitud.
        val mLat = 1.0 / 111_320.0
        val mLon = mLat / kotlin.math.cos(Math.toRadians(lat))
        return (0 until n).map { i ->
            GpsSample(
                epochMillis = 1_700_000_000_000L + i * 1000L,
                latitude = lat + gauss() * sigma * mLat,
                longitude = lon + gauss() * sigma * mLon,
                altitudeMetres = 120.0 + gauss() * sigma * 1.5,
                accuracyMetres = sigma,
            )
        }
    }

    @Test
    fun `la mediana cae donde esta el punto`() {
        val a = GpsAverager()
        a.addAll(nube(400, sigma = 4.0))
        val s = assertNotNull(a.stats())
        val verdadero = Utm.fromLatLon(lat0, lon0)

        assertEquals(400, s.samples)
        // Con cuatrocientas muestras y cuatro metros de ruido, la mediana tiene que quedarse
        // muy dentro del metro. Se compara contra el punto de verdad, no contra si misma.
        assertTrue(Utm.planarDistance(s.estimateUtm, verdadero) < 1.0,
                   "la mediana quedo a ${Utm.planarDistance(s.estimateUtm, verdadero)} m")
    }

    @Test
    fun `una muestra disparatada se descarta antes de ponderar`() {
        // La media ponderada no aguanta sola un arreglo a cientos de metros -- y menos aun
        // si el receptor lo declara preciso, porque entonces le daria MAS peso. De ahi el
        // cribado por MAD antes de ponderar nada.
        val a = GpsAverager(); a.addAll(nube(200, sigma = 3.0))
        val antes = assertNotNull(a.stats()).easting.estimate

        a.add(GpsSample(1_700_000_999_000L, lat0 + 0.005, lon0 + 0.005, 120.0, 0.5))
        val despues = assertNotNull(a.stats())

        assertEquals(1, despues.rejected, "no se descarto el disparate")
        assertTrue(abs(despues.easting.estimate - antes) < 0.5,
                   "el disparate movio la estimacion ${abs(despues.easting.estimate - antes)} m")
    }

    @Test
    fun `mas muestras independientes reducen la incertidumbre de la estimacion`() {
        // Lo que baja al seguir midiendo es la incertidumbre de la estimacion, no la
        // dispersion de las muestras: son dos cosas y responden a preguntas distintas.
        val corta = GpsAverager().apply { addAll(nube(25, sigma = 5.0)) }.stats()!!
        val larga = GpsAverager().apply { addAll(nube(1600, sigma = 5.0)) }.stats()!!

        assertTrue(larga.horizontalStandardError < corta.horizontalStandardError / 4,
                   "la incertidumbre no bajo: ${corta.horizontalStandardError} -> " +
                   "${larga.horizontalStandardError}")
    }

    @Test
    fun `la dispersion mide el ruido del receptor y puede subir o bajar`() {
        // La dispersion NO esta atada al numero de muestras: describe como de ruidoso esta
        // el receptor. Si el receptor mejora a mitad de sesion --que es lo normal, los
        // primeros arreglos son los peores-- la dispersion baja; si empeora, sube.
        val malas = nube(200, sigma = 10.0, semilla = 1)
        val buenas = nube(200, sigma = 2.0, semilla = 2)

        val empeorando = GpsAverager().apply { addAll(buenas); addAll(malas) }.stats()!!
        val mejorando = GpsAverager().apply { addAll(malas); addAll(buenas) }.stats()!!
        val soloBuenas = GpsAverager().apply { addAll(buenas) }.stats()!!

        assertTrue(soloBuenas.horizontalSd < mejorando.horizontalSd,
                   "un tramo ruidoso al principio tiene que dejarse notar en la dispersion")
        // Y no depende del ORDEN: la dispersion es una propiedad del conjunto.
        assertEquals(mejorando.horizontalSd, empeorando.horizontalSd, 1e-9)
    }

    @Test
    fun `un arreglo preciso pesa mas que uno impreciso`() {
        // Dos tandas contradictorias; la que el receptor declara buena manda.
        val a = GpsAverager()
        repeat(50) { i ->
            a.add(GpsSample(1_700_000_000_000L + i * 1000L, lat0, lon0, 120.0, 20.0))
        }
        repeat(50) { i ->
            a.add(GpsSample(1_700_000_100_000L + i * 1000L,
                            lat0 + 0.0001, lon0, 120.0, 1.0))
        }
        val s = assertNotNull(a.stats())
        val flojo = Utm.fromLatLon(lat0, lon0, forceZone = s.zone)
        val bueno = Utm.fromLatLon(lat0 + 0.0001, lon0, forceZone = s.zone)

        assertTrue(Utm.planarDistance(s.estimateUtm, bueno) <
                   Utm.planarDistance(s.estimateUtm, flojo),
                   "la estimacion no se fue hacia los arreglos precisos")
    }

    @Test
    fun `la desviacion tipica mide el ruido que se metio`() {
        val s = GpsAverager().apply { addAll(nube(2000, sigma = 6.0)) }.stats()!!
        // Seis metros por eje entraron; entre cinco y siete tienen que salir.
        assertTrue(s.easting.sd in 5.0..7.0, "easting sd = ${s.easting.sd}")
        assertTrue(s.northing.sd in 5.0..7.0, "northing sd = ${s.northing.sd}")
    }

    @Test
    fun `pausar y reanudar conserva lo medido y abre un tramo nuevo`() {
        // El fallo que esto fija: reanudar rehacia el promediador desde cero y recargaba de
        // disco, con lo que todo lo que no estuviera guardado se perdia -- justo lo contrario
        // de lo que hace una pausa.
        val a = GpsAverager()
        a.startSession(1_700_000_000_000L)
        a.addAll(nube(40, sigma = 4.0, semilla = 1))

        // Pausa. Nada se toca. Reanudar solo abre tramo.
        a.startSession(1_700_000_300_000L)
        a.addAll(nube(40, sigma = 4.0, semilla = 2))

        val s = assertNotNull(a.stats())
        assertEquals(80, a.size, "reanudar se llevo por delante lo de antes")
        assertEquals(80, s.samples)
        // Y los dos lados de la pausa no son el mismo tramo: entre uno y otro pasa tiempo, y
        // ese tiempo decorrelaciona. Contarlos como uno prometeria menos error del que hay.
        assertEquals(2, s.sessions)
    }

    @Test
    fun `dos visitas cuentan como dos tramos y no como uno`() {
        val a = GpsAverager()
        a.startSession(1_700_000_000_000L)
        a.addAll(nube(100, sigma = 4.0, semilla = 1))
        a.startSession(1_700_600_000_000L)     // una semana despues
        a.addAll(nube(100, sigma = 4.0, semilla = 2).map {
            it.copy(epochMillis = it.epochMillis + 600_000_000L) })

        val s = assertNotNull(a.stats())
        assertEquals(2, s.sessions)
        assertEquals(200, s.samples)
    }

    @Test
    fun `junto a un meridiano de zona la nube no se parte en dos`() {
        // El fallo que esto evita es espectacular: media nube con easting 700.000 y la otra
        // media con 300.000, y la mediana en medio del oceano a cientos de kilometros.
        val a = GpsAverager()
        a.addAll(nube(200, sigma = 8.0, lat = -53.0, lon = -72.0))
        val s = assertNotNull(a.stats())
        val verdadero = Utm.fromLatLon(-53.0, -72.0, forceZone = s.zone)

        assertTrue(Utm.planarDistance(s.estimateUtm, verdadero) < 2.0,
                   "la mediana quedo a ${Utm.planarDistance(s.estimateUtm, verdadero)} m")
        assertTrue(s.easting.sd < 20.0, "la nube se partio: sd = ${s.easting.sd} m")
        // Y se dice cuantas cayeron al otro lado, en vez de callarlo.
        assertTrue(a.outOfZone > 0, "el caso de prueba no esta sobre el meridiano")
    }

    @Test
    fun `sin altitud no se inventa una`() {
        // Un arreglo bidimensional no trae altura, y Android devuelve 0.0 en ese caso, que a
        // nivel del mar es indistinguible de una medida buena.
        val a = GpsAverager()
        repeat(10) { i ->
            a.add(GpsSample(1_700_000_000_000L + i * 1000L, lat0, lon0, altitudeMetres = null))
        }
        assertNull(assertNotNull(a.stats()).altitude)
    }

    @Test
    fun `con una sola muestra la incertidumbre es la que declara el receptor`() {
        val a = GpsAverager(GpsSample(1_700_000_000_000L, lat0, lon0, 120.0, 8.0))
        val s = assertNotNull(a.stats())
        assertEquals(1, s.samples)
        // Cero dispersion es correcto: no hay dos valores que comparar. Pero la
        // incertidumbre NO puede salir cero, que se leeria como una medida perfecta; es la
        // precision que el propio receptor declaro.
        assertEquals(0.0, s.easting.sd)
        assertEquals(8.0, s.easting.standardError, 0.01)
        assertEquals(1, s.easting.n)
    }

    @Test
    fun `sin muestras no hay estadistica que dar`() {
        assertNull(GpsAverager().stats())
    }
}
