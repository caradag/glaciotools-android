package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.Test
import kotlin.test.assertEquals

class LengthUnitTest {
    @Test
    fun idaYVueltaSinRuido() {
        val cm = LengthUnit.CENTIMETRE
        for (m in listOf(0.35, 0.07, 1.25, 12.5, 0.001, 3.14159, 0.1 + 0.2)) {
            // lo que se teclea en cm vuelve exactamente a lo que se mostro
            val mostrado = cm.fromMetres(m)
            assertEquals(mostrado, cm.fromMetres(cm.toMetres(mostrado)), "m=$m")
        }
        assertEquals(35.0, cm.fromMetres(0.35))
        assertEquals(0.35, cm.toMetres(35.0))
        assertEquals(0.07, cm.toMetres(7.0))
        assertEquals(0.3, cm.toMetres(cm.fromMetres(0.1 + 0.2)))   // 0.30000000000000004 -> 0.3
        assertEquals(1.25, LengthUnit.METRE.toMetres(1.25))
    }

    @Test
    fun formatoConLaMismaResolucion() {
        assertEquals("12.5", LengthUnit.METRE.format(12.5, 3))
        assertEquals("1250", LengthUnit.CENTIMETRE.format(12.5, 3))
        assertEquals("0.735", LengthUnit.METRE.format(0.735, 3))
        assertEquals("73.5", LengthUnit.CENTIMETRE.format(0.735, 3))
        assertEquals("0.44", LengthUnit.METRE.format(0.44, 2, fixed = true))
        assertEquals("44", LengthUnit.CENTIMETRE.format(0.44, 2, fixed = true))
        assertEquals(LengthUnit.METRE, LengthUnit.fromName(null))
        assertEquals(LengthUnit.CENTIMETRE, LengthUnit.fromName("CENTIMETRE"))
    }
}
