package cl.umag.glaciertemp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockOffsetTest {

    @Test fun `cero no se muestra como un signo sobre cero`() {
        // El defecto original: "-0 ms", que no dice nada y ademas sugiere una direccion.
        assertEquals("in sync", ClockOffset.describe(0))
        assertEquals("in sync", ClockOffset.describe(-10))
        assertEquals("in sync", ClockOffset.describe(49))
    }

    @Test fun `dice hacia donde, no un signo que haya que interpretar`() {
        assertTrue(ClockOffset.describe(3_200).endsWith("behind GPS"),
                   "si el GPS va por delante, el telefono atrasa")
        assertTrue(ClockOffset.describe(-3_200).endsWith("ahead of GPS"))
    }

    @Test fun `escalas`() {
        assertEquals("200 ms behind GPS", ClockOffset.describe(200))
        assertEquals("3.2 s behind GPS", ClockOffset.describe(3_200))
        assertEquals("2 min 05 s ahead of GPS", ClockOffset.describe(-125_000))
    }

    /** Ninguna descripcion debe contener un signo suelto que se preste a leerse al reves. */
    @Test fun `sin signos`() {
        for (ms in listOf(0L, 100L, -100L, 5_000L, -5_000L, 200_000L, -200_000L)) {
            val d = ClockOffset.describe(ms)
            assertTrue(!d.startsWith("-") && !d.startsWith("+"), "no deberia llevar signo: $d")
        }
    }
}
