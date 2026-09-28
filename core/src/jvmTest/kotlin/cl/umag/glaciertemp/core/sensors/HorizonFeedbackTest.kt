package cl.umag.glaciertemp.core.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HorizonFeedbackTest {

    @Test fun `las tres bandas caen donde se dijo`() {
        assertEquals(HorizonFeedback.Band.FINE, HorizonFeedback.band(0.0))
        assertEquals(HorizonFeedback.Band.FINE, HorizonFeedback.band(1.9))
        assertEquals(HorizonFeedback.Band.OFF, HorizonFeedback.band(2.0))
        assertEquals(HorizonFeedback.Band.OFF, HorizonFeedback.band(4.9))
        assertEquals(HorizonFeedback.Band.WAY_OFF, HorizonFeedback.band(5.0))
        assertEquals(HorizonFeedback.Band.WAY_OFF, HorizonFeedback.band(40.0))
    }

    @Test fun `el signo no cambia la banda, solo la direccion de la flecha`() {
        assertEquals(HorizonFeedback.band(7.0), HorizonFeedback.band(-7.0))
    }

    @Test fun `la flecha crece con la diferencia pero no sin limite`() {
        assertTrue(HorizonFeedback.lengthFactor(1.0) < HorizonFeedback.lengthFactor(4.0))
        assertTrue(HorizonFeedback.lengthFactor(4.0) < HorizonFeedback.lengthFactor(12.0))
        // Con tope: una diferencia enorme no dibuja una flecha fuera de la pantalla.
        assertEquals(HorizonFeedback.lengthFactor(200.0), HorizonFeedback.lengthFactor(60.0))
        // Y con una minima sigue viendose algo.
        assertTrue(HorizonFeedback.lengthFactor(0.1) > 0.3)
    }

    @Test fun `sin huecos no hay nada que rellenar`() {
        assertTrue(HorizonFeedback.gaps(BooleanArray(72) { true }, 5).isEmpty())
    }

    @Test fun `un hueco se devuelve como tramo de azimut`() {
        val c = BooleanArray(72) { true }
        for (b in 29..33) c[b] = false          // 145 a 170 grados
        val g = HorizonFeedback.gaps(c, 5)
        assertEquals(1, g.size)
        assertEquals(145.0, g[0].start, 1e-9)
        assertEquals(170.0, g[0].endInclusive, 1e-9)
    }

    @Test fun `un hueco a caballo del norte sale entero y no partido`() {
        // Es el caso que una implementacion ingenua parte en dos: "355-360" y "0-10".
        val c = BooleanArray(72) { true }
        c[71] = false; c[0] = false; c[1] = false
        val g = HorizonFeedback.gaps(c, 5)
        assertEquals(1, g.size, "el hueco del norte debe salir de una pieza: $g")
        assertEquals(355.0, g[0].start, 1e-9)
        assertEquals(10.0, g[0].endInclusive % 360.0, 1e-9)
    }

    @Test fun `varios huecos separados se cuentan por separado`() {
        val c = BooleanArray(72) { true }
        c[10] = false
        c[40] = false; c[41] = false
        assertEquals(2, HorizonFeedback.gaps(c, 5).size)
    }

    @Test fun `sin medir nada el hueco es la vuelta entera`() {
        val g = HorizonFeedback.gaps(BooleanArray(72) { false }, 5)
        assertEquals(1, g.size)
        assertEquals(0.0, g[0].start, 1e-9)
        assertEquals(360.0, g[0].endInclusive, 1e-9)
    }

    @Test fun `los tramos se escriben para ir a buscarlos`() {
        val c = BooleanArray(72) { true }
        for (b in 29..33) c[b] = false
        assertEquals("145°–170°", HorizonFeedback.describeGaps(HorizonFeedback.gaps(c, 5)))
    }

    @Test fun `girar despacio no dispara el aviso y girar deprisa si`() {
        // A 50 Hz y sectores de 5 grados, el limite esta en 83 grados por segundo.
        assertFalse(HorizonFeedback.tooFast(30.0, 5, 50.0))
        assertFalse(HorizonFeedback.tooFast(80.0, 5, 50.0))
        assertTrue(HorizonFeedback.tooFast(120.0, 5, 50.0))
        assertEquals(83.3, HorizonFeedback.maxComfortableRate(5, 50.0), 0.1)
    }

    @Test fun `quieto no es demasiado rapido`() {
        assertFalse(HorizonFeedback.tooFast(0.0, 5, 50.0))
    }

    @Test fun `con pocas muestras por segundo el limite baja`() {
        // Es el fallo real que se arreglo: binando a la tasa de FOTOGRAMAS en vez de a la
        // del sensor, con la camara encima, bastaba un giro normal para saltarse sectores.
        assertTrue(HorizonFeedback.tooFast(90.0, 5, 15.0),
                   "a 15 Hz un giro de 90 grados por segundo ya se salta sectores")
        assertFalse(HorizonFeedback.tooFast(90.0, 5, 100.0),
                    "a 100 Hz el mismo giro va sobrado")
    }
}
