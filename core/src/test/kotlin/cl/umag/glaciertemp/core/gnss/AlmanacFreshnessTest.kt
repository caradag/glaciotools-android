package cl.umag.glaciertemp.core.gnss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlmanacFreshnessTest {

    private val dia = 86_400_000L
    private val ahora = 1_790_000_000_000L

    @Test fun `sin almanaque`() {
        assertEquals(Freshness.MISSING, AlmanacFreshness.of(null, ahora))
        assertTrue(AlmanacFreshness.shouldDownload(null, ahora))
        assertEquals("never downloaded", AlmanacFreshness.describeAge(null, ahora))
    }

    @Test fun `los tramos`() {
        assertEquals(Freshness.FRESH, AlmanacFreshness.of(ahora - 6 * dia, ahora))
        assertEquals(Freshness.AGING, AlmanacFreshness.of(ahora - 8 * dia, ahora))
        assertEquals(Freshness.AGING, AlmanacFreshness.of(ahora - 59 * dia, ahora))
        assertEquals(Freshness.STALE, AlmanacFreshness.of(ahora - 61 * dia, ahora))
    }

    @Test fun `fresco no se vuelve a descargar aunque haya red`() {
        assertFalse(AlmanacFreshness.shouldDownload(ahora - 2 * dia, ahora))
        assertTrue(AlmanacFreshness.shouldDownload(ahora - 10 * dia, ahora))
    }

    /**
     * Si el reloj del telefono se va hacia atras --cambio de zona, arranque sin red, pila de
     * reloj-- la antiguedad sale negativa. No es motivo para alarmar ni para descargar en
     * bucle: se trata como recien traido.
     */
    @Test fun `un reloj que va hacia atras no dispara descargas en bucle`() {
        assertEquals(Freshness.FRESH, AlmanacFreshness.of(ahora + 5 * dia, ahora))
        assertFalse(AlmanacFreshness.shouldDownload(ahora + 5 * dia, ahora))
        assertEquals("updated just now", AlmanacFreshness.describeAge(ahora + 5 * dia, ahora))
    }

    @Test fun `la antiguedad en palabras`() {
        assertEquals("updated just now", AlmanacFreshness.describeAge(ahora - 600_000, ahora))
        assertEquals("updated 3 hours ago", AlmanacFreshness.describeAge(ahora - 3 * 3_600_000L, ahora))
        assertEquals("updated 1 hour ago", AlmanacFreshness.describeAge(ahora - 3_600_000L, ahora))
        assertEquals("updated 1 day ago", AlmanacFreshness.describeAge(ahora - dia, ahora))
        assertEquals("updated 12 days ago", AlmanacFreshness.describeAge(ahora - 12 * dia, ahora))
        assertEquals("updated 3 months ago", AlmanacFreshness.describeAge(ahora - 95 * dia, ahora))
    }
}
