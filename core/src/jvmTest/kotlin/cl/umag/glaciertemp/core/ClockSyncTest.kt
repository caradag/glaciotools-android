package cl.umag.glaciertemp.core

import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.*

/**
 * La conversion al sincronizar el reloj.
 *
 * Este fichero existe por UNA asercion: que "solo la hora" no manda la hora local del
 * telefono cuando los husos difieren. Es un error que no se ve ni leyendo el codigo ni
 * mirando el resultado, porque produce un reloj que parece correcto y esta corrido en una
 * cantidad constante.
 */
class ClockSyncTest {

    // 15:00 en UTC-3, es decir 18:00 UTC.
    private val ahora = ZonedDateTime.of(2026, 3, 14, 15, 0, 0, 0, ZoneOffset.ofHours(-3))

    @Test
    fun `solo la hora respeta el huso de la placa`() {
        val stamp = ClockSync.stampFor(ahora, ClockSyncMode.TIME_ONLY, boardTz = 0, phoneTz = -3)
        // El mismo instante visto desde UTC+0 son las 18:00, no las 15:00 del telefono.
        assertEquals(18, stamp.hour,
                     "mandar la hora local del telefono dejaria el reloj corrido 3 horas")
        assertEquals(0, stamp.minute)
    }

    @Test
    fun `la diferencia entre los dos modos es exactamente la diferencia de husos`() {
        for ((board, phone) in listOf(0 to -3, -3 to 0, 5 to -8, 14 to -12, -3 to -3)) {
            val ambos = ClockSync.stampFor(ahora, ClockSyncMode.BOTH, board, phone)
            val solo = ClockSync.stampFor(ahora, ClockSyncMode.TIME_ONLY, board, phone)
            val horas = Duration.between(ambos, solo).toHours()
            assertEquals((board - phone).toLong(), horas,
                         "husos placa=$board telefono=$phone")
        }
    }

    @Test
    fun `cambiar ambos manda la hora local del telefono`() {
        val stamp = ClockSync.stampFor(ahora, ClockSyncMode.BOTH, boardTz = 0, phoneTz = -3)
        assertEquals(15, stamp.hour)
    }

    @Test
    fun `con husos iguales los dos modos coinciden`() {
        assertEquals(ClockSync.stampFor(ahora, ClockSyncMode.BOTH, -3, -3),
                     ClockSync.stampFor(ahora, ClockSyncMode.TIME_ONLY, -3, -3))
    }

    @Test
    fun `sin huso de placa legible se cae al del telefono`() {
        // Es lo unico que se sabe, y es lo que la app hacia antes de que existiera la opcion.
        assertEquals(ClockSync.stampFor(ahora, ClockSyncMode.BOTH, null, -3),
                     ClockSync.stampFor(ahora, ClockSyncMode.TIME_ONLY, null, -3))
    }

    @Test
    fun `la marca no lleva fracciones de segundo`() {
        val conNanos = ahora.withNano(123_456_789)
        assertEquals(0, ClockSync.stampFor(conNanos, ClockSyncMode.BOTH, 0, -3).nano)
    }
}
