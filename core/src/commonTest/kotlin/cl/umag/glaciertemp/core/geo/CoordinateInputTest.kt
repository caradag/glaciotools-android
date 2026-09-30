package cl.umag.glaciertemp.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinateInputTest {
    private fun lat(s: String) = CoordinateInput.latitude(s)
    private fun lon(s: String) = CoordinateInput.longitude(s)

    @Test
    fun formasHabituales() {
        assertEquals(-53.16, lat("-53.16")!!, 1e-12)
        assertEquals(-53.16, lat("53.16 S")!!, 1e-12)
        assertEquals(-53.16, lat("53,16S")!!, 1e-12)
        assertEquals(-53.16, lat("53°9.6'S")!!, 1e-12)
        assertEquals(-53.16, lat("S 53 09.600")!!, 1e-12)
        assertEquals(-53.16, lat("53 9 36 S")!!, 1e-12)
        assertEquals(-70.91, lon("70°54.6' W")!!, 1e-12)
        assertEquals(-70.91, lon("70 54.6 O")!!, 1e-12)
        assertEquals(12.5, lon("12.5 E")!!, 1e-12)
        assertEquals(12.5, lon("12.5")!!, 1e-12)
    }

    @Test
    fun rechazaLoAmbiguoOImposible() {
        assertNull(lat("-53 N"))       // signo y hemisferio contradictorios
        assertNull(lat("-53 S"))       // redundante: no se adivina la intencion
        assertNull(lat("53 E"))        // letra de otro eje
        assertNull(lat("91"))
        assertNull(lon("181 W"))
        assertNull(lat("53 61 S"))     // minutos imposibles
        assertNull(lat("53.5 30 S"))   // decimales antes del ultimo componente
        assertNull(lat(""))
        assertNull(lat("abc"))
        assertNull(lat("53 N S"))
    }
}
