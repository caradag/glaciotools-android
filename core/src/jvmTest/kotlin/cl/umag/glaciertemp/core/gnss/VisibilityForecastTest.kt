package cl.umag.glaciertemp.core.gnss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisibilityForecastTest {

    private val gps: List<Tle> by lazy {
        Tle.parse(javaClass.getResourceAsStream("/gps-ops-20260924.tle")!!
                      .bufferedReader().readText(), Constellation.GPS)
    }

    @Test fun `una constelacion sola no dibuja tambien un total redundante`() {
        val f = VisibilityForecast.compute(gps, -53.15, -73.0, gps.first().epochMillis,
                                           enabled = setOf(Constellation.GPS))
        assertEquals(1, f.series.size)
        assertEquals(Constellation.GPS, f.series.single().constellation)
    }

    @Test fun `deshabilitar una constelacion la saca del resultado`() {
        val f = VisibilityForecast.compute(gps, -53.15, -73.0, gps.first().epochMillis,
                                           enabled = setOf(Constellation.GALILEO))
        assertEquals(1, f.series.size)
        assertEquals(Constellation.GALILEO, f.series.single().constellation)
        // No hay TLE de Galileo cargados, asi que la cuenta es cero en todo el dia.
        assertTrue(f.series.single().counts.all { it == 0 })
    }

    @Test fun `la cuenta a lo largo del dia es la del GPS real`() {
        val f = VisibilityForecast.compute(gps, -53.15, -73.0, gps.first().epochMillis,
                                           enabled = setOf(Constellation.GPS))
        val c = f.series.single().counts
        assertEquals(96, c.size)
        assertTrue(c.min() >= 4, "en algun momento solo se verian ${c.min()} satelites")
        assertTrue(c.max() <= 16, "en algun momento se verian ${c.max()}")
    }

    /** Subir la mascara no puede aumentar la cuenta en ningun instante. */
    @Test fun `una mascara mas alta nunca ve mas`() {
        val t = gps.first().epochMillis
        val baja = VisibilityForecast.compute(gps, -53.15, -73.0, t, maskDeg = 5.0,
                                              enabled = setOf(Constellation.GPS))
        val alta = VisibilityForecast.compute(gps, -53.15, -73.0, t, maskDeg = 25.0,
                                              enabled = setOf(Constellation.GPS))
        for (i in 0 until baja.steps) {
            assertTrue(alta.series.single().counts[i] <= baja.series.single().counts[i],
                       "en el paso $i la mascara alta vio mas")
        }
    }

    @Test fun `el mejor momento sale del total`() {
        val f = VisibilityForecast.compute(gps, -53.15, -73.0, gps.first().epochMillis,
                                           enabled = setOf(Constellation.GPS, Constellation.GALILEO))
        val total = f.series.first { it.constellation == null }.counts
        assertEquals(total.indexOf(total.max()), f.bestIndex())
    }
}

class ExcludeMismatchedTest {

    private val gps: List<Tle> by lazy {
        Tle.parse(javaClass.getResourceAsStream("/gps-ops-20260924.tle")!!
                      .bufferedReader().readText(), Constellation.GPS)
    }

    @Test fun `sin descartados no se toca nada`() {
        assertEquals(gps.size, VisibilityForecast.withoutMismatched(gps, emptySet()).size)
    }

    @Test fun `se quita el que se nombra, y solo ese`() {
        val fuera = setOf(VisibilityForecast.key(Constellation.GPS, 22))
        val r = VisibilityForecast.withoutMismatched(gps, fuera)
        assertEquals(gps.size - 1, r.size)
        assertTrue(r.none { it.svid == 22 })
        assertTrue(r.any { it.svid == 21 })
    }

    /** La clave lleva la constelacion: el 22 de GPS y el 22 de BeiDou no son el mismo. */
    @Test fun `la clave distingue constelaciones`() {
        val fuera = setOf(VisibilityForecast.key(Constellation.BEIDOU, 22))
        assertEquals(gps.size, VisibilityForecast.withoutMismatched(gps, fuera).size)
    }

    /** Y quitar uno baja el recuento, que es el objetivo. */
    @Test fun `quitar un satelite baja la cuenta del dia`() {
        val t = gps.first().epochMillis
        val con = VisibilityForecast.compute(gps, -47.79, -73.53, t,
                                             enabled = setOf(Constellation.GPS))
        val sin = VisibilityForecast.compute(
            VisibilityForecast.withoutMismatched(
                gps, setOf(VisibilityForecast.key(Constellation.GPS, 22))),
            -47.79, -73.53, t, enabled = setOf(Constellation.GPS))
        val a = con.series.single().counts.sum()
        val b = sin.series.single().counts.sum()
        assertTrue(b < a, "quitando un satelite la suma paso de $a a $b")
    }
}
