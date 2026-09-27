package cl.umag.glaciertemp.core.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HorizonPointsTest {

    @Test fun `el formato copiado es el de la calculadora, dos lineas`() {
        // El ejemplo de la propia pagina de ICE-D.
        val p = listOf(0.0 to 3.0, 55.0 to 0.0, 115.0 to 5.0, 235.0 to 0.0, 310.0 to 0.0)
            .map { HorizonPoints.Point(it.first, it.second) }
        assertEquals("0 55 115 235 310\n3 0 5 0 0", HorizonPoints.toClipboard(p))
    }

    @Test fun `se copia ordenado por azimut aunque se midiera a saltos`() {
        val p = listOf(HorizonPoints.Point(200.0, 4.0), HorizonPoints.Point(10.0, 9.0))
        assertEquals("10 200\n9 4", HorizonPoints.toClipboard(p))
    }

    @Test fun `lee espacios, comas y saltos de linea por igual`() {
        // Lo pegado viene de una hoja de calculo, de un cuaderno o de la propia pagina.
        listOf("0 90 180 270", "0,90,180,270", "0\n90\n180\n270", "0; 90 ,180\t270").forEach {
            val r = HorizonPoints.parse(it, "1 2 3 4")
            assertNull(r.error, "no deberia fallar con '$it': ${r.error}")
            assertEquals(4, r.points.size)
        }
    }

    @Test fun `si no emparejan se dice cuantos hay de cada`() {
        val r = HorizonPoints.parse("0 90 180", "1 2")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("3 azimuths"), r.error!!)
        assertTrue(r.error!!.contains("2 elevations"), r.error!!)
    }

    @Test fun `un azimut fuera de rango se rechaza nombrandolo`() {
        val r = HorizonPoints.parse("0 400 180", "1 2 3")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("400"), r.error!!)
    }

    @Test fun `una elevacion imposible se rechaza`() {
        assertNotNull(HorizonPoints.parse("0 90 180", "1 2 120").error)
    }

    @Test fun `con menos de tres puntos no hay horizonte que cerrar`() {
        val r = HorizonPoints.parse("0 180", "5 5")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("three"), r.error!!)
    }

    @Test fun `dos campos vacios no son un error, solo no hay datos`() {
        val r = HorizonPoints.parse("", "")
        assertNull(r.error)
        assertTrue(r.points.isEmpty())
    }

    @Test fun `un perfil por sectores se convierte en puntos en el centro de cada uno`() {
        val p = HorizonPoints.fromProfile(HorizonProfile(DoubleArray(72) { 10.0 }, 5))
        assertEquals(72, p.size)
        assertEquals(2.5, p[0].azimuth, 1e-9)
        assertEquals(357.5, p[71].azimuth, 1e-9)
    }

    @Test fun `los puntos a mano dan el mismo apantallamiento que el perfil equivalente`() {
        // Es lo que permite comparar los dos metodos: si el telefono y la brujula miden lo
        // mismo, el factor tiene que salir igual por los dos caminos.
        val perfil = HorizonProfile(DoubleArray(72) { 25.0 }, 5)
        val porPerfil = Shielding.compute(perfil).factor
        val porPuntos = Shielding.factorOf(
            HorizonPoints.toShieldingHorizon(HorizonPoints.fromProfile(perfil)))
        assertEquals(porPerfil, porPuntos, 1e-6)
    }
}
