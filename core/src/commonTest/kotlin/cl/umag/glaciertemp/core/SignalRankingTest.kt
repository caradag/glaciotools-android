package cl.umag.glaciertemp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalRankingTest {

    @Test fun `un modulo nuevo entra al momento y al final`() {
        val r = SignalRanking()
        assertTrue(r.sighting("A", -80, 0))
        r.reorder(0)
        // B es mas fuerte, pero entra al final hasta la siguiente vuelta.
        assertTrue(r.sighting("B", -40, 100))
        assertEquals(listOf("A", "B"), r.order())
        assertFalse(r.sighting("B", -41, 200))
        r.reorder(2_000)
        assertEquals(listOf("B", "A"), r.order())
    }

    @Test fun `un salto suelto no reordena entre vueltas`() {
        val r = SignalRanking()
        r.sighting("A", -50, 0); r.sighting("B", -70, 0)
        r.reorder(0)
        assertEquals(listOf("A", "B"), r.order())
        // B da un pico por encima de A: sin vuelta, no se mueve nada.
        r.sighting("B", -30, 500)
        assertEquals(listOf("A", "B"), r.order())
    }

    @Test fun `el orden sale de la media de la ventana y no de la ultima lectura`() {
        val r = SignalRanking()
        // A: -50 estable. B: casi siempre -70 con UN pico a -30 al final de la ventana.
        for (t in listOf(100L, 600L, 1100L, 1600L)) r.sighting("A", -50, t)
        for (t in listOf(100L, 600L, 1100L)) r.sighting("B", -70, t)
        r.sighting("B", -30, 1900)
        r.reorder(2_000)
        // Media de B = -60: sigue por debajo de A aunque su ultima lectura sea la mas fuerte.
        assertEquals(listOf("A", "B"), r.order())
        assertEquals(-60, r.shownRssi("B"))
    }

    @Test fun `lo viejo sale de la ventana`() {
        val r = SignalRanking()
        r.sighting("A", -40, 0); r.sighting("B", -60, 0)
        r.reorder(0)
        // A se aleja: sus lecturas nuevas son malas, y las viejas ya no cuentan.
        r.sighting("A", -90, 2_500); r.sighting("B", -60, 2_500)
        r.reorder(4_000)
        assertEquals(listOf("B", "A"), r.order())
        assertEquals(-90, r.shownRssi("A"))
    }

    @Test fun `un modulo callado conserva su media y su sitio`() {
        val r = SignalRanking()
        r.sighting("A", -50, 0); r.sighting("B", -60, 0)
        r.reorder(1_000)
        r.sighting("B", -60, 3_000)
        r.reorder(4_000)
        assertEquals(listOf("A", "B"), r.order())
        assertEquals(-50, r.shownRssi("A"))
    }

    @Test fun `los empates no se intercambian`() {
        val r = SignalRanking()
        r.sighting("A", -60, 0); r.sighting("B", -60, 0); r.sighting("C", -60, 0)
        repeat(5) { r.reorder(it * 2_000L) }
        assertEquals(listOf("A", "B", "C"), r.order())
    }
}
