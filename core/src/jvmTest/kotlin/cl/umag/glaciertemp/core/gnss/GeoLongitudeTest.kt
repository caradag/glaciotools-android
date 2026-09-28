package cl.umag.glaciertemp.core.gnss

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test

/**
 * REFERENCIA ABSOLUTA para el marco de coordenadas.
 *
 * Las pruebas de cobertura no pueden detectar un desplazamiento constante de longitud: el
 * GPS cubre el globo de forma casi uniforme, asi que girar la Tierra entera bajo la
 * constelacion deja el RECUENTO de satelites practicamente igual. Y la latitud subsatelite
 * tampoco se entera, porque una rotacion sobre el eje Z no la cambia.
 *
 * Los geoestacionarios si: cada uno esta sobre una longitud PUBLICADA y no se mueve. Si el
 * angulo sidereo o el giro a coordenadas fijas estan mal, aqui salta.
 */
class GeoLongitudeTest {

    @Test fun `longitud subsatelite de los geoestacionarios de BeiDou`() {
        val txt = javaClass.getResourceAsStream("/beidou-20260924.tle")!!.bufferedReader().readText()
        val geos = Tle.parse(txt, Constellation.BEIDOU)
            .filter { it.meanMotionRevPerDay in 0.99..1.01 }
        println("geoestacionarios encontrados: ${geos.size}")
        val t = geos.first().epochMillis
        for (g in geos) {
            val (x, y, _) = SkyModel.ecef(g, t)
            val lon = atan2(y, x) * 180.0 / PI
            println("%-28s lon subsatelite = %8.2f".format(g.name.trim(), lon))
        }
    }
}
