package cl.umag.glaciertemp.core.geo.geoid

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las grillas contra GeoidEval de GeographicLib, que es el ORACULO: la interpolacion es la
 * suya portada, asi que tienen que coincidir al decimo de milimetro (GeoidEval imprime
 * cuatro decimales). Las referencias las genera tools/geoid/make_tiles.py.
 */
class GeoidGridTest {

    private fun recurso(n: String) = File(javaClass.classLoader.getResource("geoid/$n")!!.toURI())

    private fun referencia(n: String) = recurso(n).readLines().drop(1).map { l ->
        val p = l.split(','); Triple(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
    }

    private val egm96 by lazy { GeoidGrid.parse(File("../app/src/main/assets/geoid/egm96-15m.gtg").readBytes()) }

    @Test
    fun egm96GlobalCoincideConGeoidEval() {
        assertEquals("egm96", egm96.model)
        assertEquals(15.0, egm96.stepArcMinutes, 1e-9)
        val pts = referencia("egm96-15-reference.csv")
        assertEquals(1010, pts.size)
        for ((lat, lon, n) in pts) {
            val v = assertNotNull(egm96.undulation(lat, lon), "sin valor en $lat $lon")
            assertTrue(abs(v - n) <= 6e-5, "EGM96 en $lat $lon: $v vs $n")
        }
    }

    @Test
    fun egm2008TeselaCoincideConGeoidEval() {
        val t = GeoidGrid.parse(GZIPInputStream(recurso("egm2008-1m-S60W080.gtg.gz").inputStream()).readBytes())
        assertEquals(1.0, t.stepArcMinutes, 1e-9)
        for ((lat, lon, n) in referencia("egm2008-S60W080-reference.csv")) {
            val v = assertNotNull(t.undulation(lat, lon), "la tesela no cubre $lat $lon")
            assertTrue(abs(v - n) <= 6e-5, "EGM2008 en $lat $lon: $v vs $n")
        }
        // Punta Arenas (GeoidEval: 10,1511) y los bordes de la tesela
        assertEquals(10.1511, t.undulation(-53.16, -70.91)!!, 6e-5)
        assertNotNull(t.undulation(-60.0, -80.0))
        assertNotNull(t.undulation(-50.0, -70.0))
        // fuera de la tesela no inventa nada
        assertNull(t.undulation(-49.9, -75.0))
        assertNull(t.undulation(-55.0, -69.9))
        assertNull(t.undulation(10.0, 10.0))
    }

    @Test
    fun formatoRechazaBasura() {
        val r = runCatching { GeoidGrid.parse("hola".encodeToByteArray()) }
        assertTrue(r.isFailure)
    }
}
