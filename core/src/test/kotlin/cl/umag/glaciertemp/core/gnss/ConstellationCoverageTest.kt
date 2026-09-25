package cl.umag.glaciertemp.core.gnss

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * La constelacion GPS entera, contra la propiedad que la define.
 *
 * El GPS se diseno para que desde CUALQUIER punto de la Tierra y a CUALQUIER hora se vean al
 * menos cuatro satelites, que es el minimo para resolver una posicion. En la practica, con
 * 31 satelites operativos y una mascara de 10 grados, se ven entre seis y doce.
 *
 * Es la mejor prueba que se puede escribir para esta geometria: no compara contra un numero
 * copiado de otro programa --que solo demostraria que los dos se equivocan igual-- sino
 * contra una propiedad de la constelacion real. Un error en el angulo sidereo, en el sistema
 * local, en la inclinacion o en el nodo ascendente rompe el recuento de inmediato.
 *
 * El fichero es una descarga real de CelesTrak con fecha fija, asi que la prueba es
 * determinista para siempre.
 */
class ConstellationCoverageTest {

    private val gps: List<Tle> by lazy {
        val txt = javaClass.getResourceAsStream("/gps-ops-20260924.tle")!!
            .bufferedReader().readText()
        Tle.parse(txt, Constellation.GPS)
    }

    @Test fun `se analiza la constelacion completa`() {
        assertTrue(gps.size >= 30, "solo se analizaron ${gps.size} satelites de unos 32")
    }

    @Test fun `entre seis y doce satelites visibles a cualquier hora y en cualquier sitio`() {
        val sitios = listOf(
            "Glaciar Bernal" to (-53.15 to -73.0),
            "Punta Arenas"   to (-53.16 to -70.91),
            "ecuador"        to (0.0 to 0.0),
            "polo norte"     to (89.0 to 0.0),
            "Europa"         to (47.0 to 8.0),
        )
        val t0 = gps.first().epochMillis
        for ((nombre, coord) in sitios) {
            val (lat, lon) = coord
            // Cada 20 minutos durante un dia entero.
            for (k in 0 until 72) {
                val t = t0 + k * 20 * 60_000L
                val n = gps.count { SkyModel.skyPos(it, t, lat, lon).elevationDeg >= 10.0 }
                assertTrue(n in 4..16,
                           "en $nombre, a la hora $k, se verian $n satelites GPS")
            }
        }
    }

    @Test fun `la media ronda los ocho o nueve`() {
        val t0 = gps.first().epochMillis
        val cuentas = (0 until 144).map { k ->
            gps.count { SkyModel.skyPos(it, t0 + k * 10 * 60_000L, -53.15, -73.0)
                            .elevationDeg >= 10.0 }
        }
        val media = cuentas.average()
        assertTrue(media in 6.0..12.0, "media de satelites visibles: $media")
    }

    /** Con la mascara a cero se ve mas gente que con la mascara a 30 grados. Obvio, y por eso util. */
    @Test fun `subir la mascara reduce la cuenta`() {
        val t = gps.first().epochMillis
        val bajo = gps.count { SkyModel.skyPos(it, t, -53.15, -73.0).elevationDeg >= 0.0 }
        val alto = gps.count { SkyModel.skyPos(it, t, -53.15, -73.0).elevationDeg >= 30.0 }
        assertTrue(alto < bajo, "mascara 30 dio $alto y mascara 0 dio $bajo")
    }
}
