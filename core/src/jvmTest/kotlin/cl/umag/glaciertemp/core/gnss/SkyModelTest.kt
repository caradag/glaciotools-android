package cl.umag.glaciertemp.core.gnss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El modelo del cielo, comprobado contra hechos conocidos de la constelacion GPS.
 *
 * No hay "valor esperado" que copiar de ningun sitio: lo que se comprueba son propiedades
 * que tienen que cumplirse SIEMPRE --la altura de la orbita, el periodo, cuantos satelites
 * se ven a la vez-- y que se romperian a la primera si la geometria estuviera mal.
 */
class SkyModelTest {

    // Tres GPS reales, descargados de CelesTrak. Sirven de patron fijo.
    private val gpsTexto = """
GPS BIIR-5  (PRN 22)
1 26407U 00040A   26267.25377767 -.00000003  00000+0  00000+0 0  9992
2 26407  54.8367 211.2307 0117723 304.0216 229.1692  2.00558441191930
GPS BIIR-8  (PRN 16)
1 27663U 03005A   26266.62814447 -.00000006  00000+0  00000+0 0  9995
2 27663  54.8665 211.0610 0150830  53.7449 163.6662  2.00555241173285
GPS BIIR-11 (PRN 19)
1 28190U 04009A   26266.69427441  .00000019  00000+0  00000+0 0  9990
2 28190  54.7547 271.6983 0118182 176.7729 242.3586  2.00573383164923
""".trimIndent()

    private val gps = Tle.parse(gpsTexto, Constellation.GPS)

    @Test fun `se analizan los tres`() {
        assertEquals(3, gps.size)
        assertEquals(Constellation.GPS, gps[0].constellation)
        assertTrue(gps[0].name.contains("PRN 22"))
    }

    @Test fun `los elementos salen de las columnas correctas`() {
        val t = gps[0]
        assertEquals(54.8367, t.inclinationRad * 180.0 / PI, 1e-4)
        assertEquals(211.2307, t.raanRad * 180.0 / PI, 1e-4)
        assertEquals(0.0117723, t.eccentricity, 1e-9)
        assertEquals(2.00558441, t.meanMotionRevPerDay, 1e-8)
    }

    /** Un GPS da dos vueltas por dia sidereo: periodo 11 h 58 m, no 12 h exactas. */
    @Test fun `el periodo es el de la orbita GPS`() {
        val horas = 24.0 / gps[0].meanMotionRevPerDay
        assertTrue(abs(horas - 11.967) < 0.02, "periodo calculado: $horas h")
    }

    /** Semieje 26 560 km, altura 20 200 km. Si la tercera ley esta mal, esto se va por miles. */
    @Test fun `la orbita esta a la altura que le toca`() {
        assertEquals(26_560.0, gps[0].semiMajorAxisKm, 60.0)
    }

    /** Y la posicion propagada tiene que caer a esa distancia del centro de la Tierra. */
    @Test fun `la posicion propagada mantiene el radio orbital`() {
        val t0 = gps[0].epochMillis
        for (h in 0..24 step 3) {
            val (x, y, z) = SkyModel.ecef(gps[0], t0 + h * 3_600_000L)
            val r = sqrt(x * x + y * y + z * z)
            // Excentricidad 0.0118 -> el radio oscila +-313 km alrededor del semieje.
            assertTrue(abs(r - 26_560.0) < 600.0, "a las $h h el radio sale $r km")
        }
    }

    /** GMST en J2000.0 (2000-01-01 12:00 UT) vale 280.46 grados. Es la constante de la formula. */
    @Test fun `el tiempo sidereo esta anclado en J2000`() {
        val j2000 = java.time.Instant.parse("2000-01-01T12:00:00Z").toEpochMilli()
        val deg = SkyModel.gmstRad(j2000) * 180.0 / PI
        assertEquals(280.46, deg, 0.01)
    }

    /**
     * LA PRUEBA QUE IMPORTA. Un satelite GPS no puede estar siempre visible ni siempre
     * oculto: en doce horas tiene que salir y ponerse visto desde cualquier sitio. Si la
     * conversion a coordenadas fijas a la Tierra estuviera mal --por ejemplo sin girar el
     * angulo sidereo-- la elevacion saldria casi constante, y esto lo detecta.
     */
    @Test fun `un satelite sale y se pone a lo largo del dia`() {
        val t0 = gps[0].epochMillis
        val elevaciones = (0 until 24 * 6).map { k ->
            SkyModel.skyPos(gps[0], t0 + k * 600_000L, -53.15, -73.0).elevationDeg
        }
        assertTrue(elevaciones.any { it > 10.0 }, "nunca se ve: max ${elevaciones.max()}")
        assertTrue(elevaciones.any { it < 0.0 }, "nunca se pone: min ${elevaciones.min()}")
    }

    /** Y el azimut tiene que recorrer el circulo, no quedarse clavado. */
    @Test fun `el azimut se mueve`() {
        val t0 = gps[0].epochMillis
        val az = (0 until 12).map {
            SkyModel.skyPos(gps[0], t0 + it * 3_600_000L, -53.15, -73.0).azimuthDeg
        }
        assertTrue(az.toSet().size > 8, "el azimut apenas cambia: $az")
        assertTrue(az.all { it in 0.0..360.0 }, "azimut fuera de rango: $az")
    }
}
