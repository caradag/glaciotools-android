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
        assertTrue(Utm.planarDistance(s.medianUtm, verdadero) < 1.0,
                   "la mediana quedo a ${Utm.planarDistance(s.medianUtm, verdadero)} m")
    }

    @Test
    fun `una muestra disparatada no arrastra la mediana, pero si la media`() {
        // Es la razon entera de usar mediana: un GPS suelta de vez en cuando una posicion a
        // cientos de metros, y con la media basta UNA para estropear media hora de trabajo.
        val buenas = nube(200, sigma = 3.0).toMutableList()
        val a = GpsAverager(); a.addAll(buenas)
        val medianaAntes = assertNotNull(a.stats()).easting.median
        val mediaAntes = a.stats()!!.easting.mean

        a.add(GpsSample(1_700_000_999_000L, lat0 + 0.005, lon0 + 0.005, 120.0, 50.0))
        val despues = assertNotNull(a.stats())

        assertTrue(abs(despues.easting.median - medianaAntes) < 1.0,
                   "la mediana se movio ${abs(despues.easting.median - medianaAntes)} m")
        assertTrue(abs(despues.easting.mean - mediaAntes) > 1.0,
                   "la media no se movio, asi que el caso de prueba no prueba nada")
    }

    @Test
    fun `la incertidumbre baja al seguir midiendo y la dispersion no`() {
        // La distincion que hace util la pantalla. Si solo se ensenara la desviacion tipica,
        // uno mira diez minutos, ve que el numero no baja y concluye que promediar no sirve.
        val corta = GpsAverager().apply { addAll(nube(25, sigma = 5.0)) }.stats()!!
        val larga = GpsAverager().apply { addAll(nube(1600, sigma = 5.0)) }.stats()!!

        assertTrue(larga.horizontalStandardError < corta.horizontalStandardError / 4,
                   "la incertidumbre no bajo: ${corta.horizontalStandardError} -> " +
                   "${larga.horizontalStandardError}")
        // La dispersion describe al receptor y al sitio: con mas muestras se estima mejor,
        // pero no se hace pequena.
        assertTrue(abs(larga.horizontalSd - corta.horizontalSd) < corta.horizontalSd * 0.5,
                   "la dispersion cambio de orden: ${corta.horizontalSd} -> ${larga.horizontalSd}")
    }

    @Test
    fun `la desviacion tipica mide el ruido que se metio`() {
        val s = GpsAverager().apply { addAll(nube(2000, sigma = 6.0)) }.stats()!!
        // Seis metros por eje entraron; entre cinco y siete tienen que salir.
        assertTrue(s.easting.sd in 5.0..7.0, "easting sd = ${s.easting.sd}")
        assertTrue(s.northing.sd in 5.0..7.0, "northing sd = ${s.northing.sd}")
    }

    @Test
    fun `junto a un meridiano de zona la nube no se parte en dos`() {
        // El fallo que esto evita es espectacular: media nube con easting 700.000 y la otra
        // media con 300.000, y la mediana en medio del oceano a cientos de kilometros.
        val a = GpsAverager()
        a.addAll(nube(200, sigma = 8.0, lat = -53.0, lon = -72.0))
        val s = assertNotNull(a.stats())
        val verdadero = Utm.fromLatLon(-53.0, -72.0, forceZone = s.zone)

        assertTrue(Utm.planarDistance(s.medianUtm, verdadero) < 2.0,
                   "la mediana quedo a ${Utm.planarDistance(s.medianUtm, verdadero)} m")
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
    fun `con una sola muestra no se finge precision`() {
        val a = GpsAverager(GpsSample(1_700_000_000_000L, lat0, lon0, 120.0, 8.0))
        val s = assertNotNull(a.stats())
        assertEquals(1, s.samples)
        // Cero dispersion es correcto --no hay dos valores que comparar-- pero la
        // incertidumbre NO puede salir cero: eso se leeria como una medida perfecta.
        assertEquals(0.0, s.easting.sd)
        assertEquals(0.0, s.horizontalStandardError)
        assertEquals(1, s.easting.n)
    }

    @Test
    fun `sin muestras no hay estadistica que dar`() {
        assertNull(GpsAverager().stats())
        assertEquals(0, Dispersion.of(emptyList()).n)
    }

    @Test
    fun `la mediana de un numero par de valores es el promedio de los dos de en medio`() {
        val d = Dispersion.of(listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(2.5, d.median)
        assertEquals(2.5, d.mean)
        val impar = Dispersion.of(listOf(1.0, 2.0, 100.0))
        assertEquals(2.0, impar.median, "la mediana ignora el valor disparatado")
    }
}
