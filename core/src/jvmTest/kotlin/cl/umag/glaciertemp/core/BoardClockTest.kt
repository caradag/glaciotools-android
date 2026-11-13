package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.test.*

class BoardClockTest {

    private val phone = LocalDateTime.of(2026, 9, 4, 18, 30, 0)

    @Test
    fun `lee la hora de la respuesta al comando TIME`() {
        // Formato de printRTCTime(): "Time: 2026-09-04 18:30:00 (UTC-3)".
        assertEquals(LocalDateTime.of(2026, 9, 4, 18, 30, 0),
            BoardClock.parse("Time: 2026-09-04 18:30:00 (UTC-3)"))
        // displayDateVec no rellena con ceros los campos de un digito.
        assertEquals(LocalDateTime.of(2026, 9, 4, 8, 5, 3),
            BoardClock.parse("Time: 2026-9-4 8:05:03"))
    }

    @Test
    fun `al ajustar el reloj lee la hora y no la proxima alarma`() {
        // Respuesta real de TIME=: setWakeUp() imprime la alarma ANTES que printRTCTime().
        // Leer la primera fecha daba un "desfase" igual a lo que faltaba para medir.
        val reply = "Next Wakeup:2026-09-28 20:30:00\r\nTime:2026-09-28 20:15:07\r\n"
        assertEquals(LocalDateTime.of(2026, 9, 28, 20, 15, 7), BoardClock.parse(reply))
        // Y sin la hora, la alarma sola no se toma por la hora.
        assertNull(BoardClock.parse("Next Wakeup:2026-09-28 20:30:00"))
        assertNull(BoardClock.parse("Ref. time:2026-09-28 20:30:00"))
    }

    @Test
    fun `una respuesta sin hora no inventa una`() {
        assertNull(BoardClock.parse("Wrong format: TIME=abc"))
        assertNull(BoardClock.parse(""))
    }

    @Test
    fun `lee el huso que la placa imprime junto a la hora`() {
        // displayDateVec(): " (UTC" << '+' << timeZone << ')'. El '+' fuerza el signo en los
        // positivos y en el cero; los negativos salen con su menos.
        assertEquals(-3, BoardClock.offsetHours("Time: 2026-09-04 18:30:00 (UTC-3)"))
        assertEquals(0, BoardClock.offsetHours("Time:2026-9-4 8:05:03 (UTC+0)"))
        assertEquals(5, BoardClock.offsetHours("Time: 2026-09-04 18:30:00 (UTC+5)\r\n"))
        // Al ajustar llegan dos lineas con huso; vale el de la hora, que es la que se lee.
        assertEquals(-3, BoardClock.offsetHours(
            "Next Wakeup:2026-09-28 20:30:00 (UTC-3)\r\nTime:2026-09-28 20:15:07 (UTC-3)\r\n"))
        // Sin huso en la linea de la hora no se inventa uno, ni se toma el de otra linea.
        assertNull(BoardClock.offsetHours("Time: 2026-09-04 18:30:00"))
        assertNull(BoardClock.offsetHours("Next Wakeup:2026-09-28 20:30:00 (UTC-3)"))
        assertNull(BoardClock.offsetHours(""))
    }

    @Test
    fun `una orden de ajuste sin la linea de la hora no es un reloj puesto`() {
        // Placa dormida o enlace caido: no llega nada. Decir "Clock set" ahi era afirmar algo
        // que no se sabe, con la hora que se QUISO poner en la tarjeta del reloj.
        assertEquals(BoardClock.SetResult.UNCONFIRMED, BoardClock.setResult(""))
        // Respuesta cortada antes de la hora: tampoco hay constancia.
        assertEquals(BoardClock.SetResult.UNCONFIRMED,
                     BoardClock.setResult("Next Wakeup:2026-09-28 20:30:00 (UTC-3)\r\n"))
    }

    @Test
    fun `una hora rechazada por la placa no es un reloj puesto`() {
        // manualClockAdjust() protesta y el despachador imprime igual el reloj, SIN tocar:
        // la linea "Time:" esta, pero trae la hora de antes.
        val reply = "Wrong format:TIME=2026-11-13 12:3\r\nTime:2026-11-13 09:00:41 (UTC-3)\r\n"
        assertEquals(BoardClock.SetResult.REJECTED, BoardClock.setResult(reply))
    }

    @Test
    fun `la respuesta normal a un ajuste es un reloj puesto`() {
        val reply = "Next Wakeup:2026-09-28 20:30:00 (UTC-3)\r\nTime:2026-09-28 20:15:07 (UTC-3)\r\n"
        assertEquals(BoardClock.SetResult.SET, BoardClock.setResult(reply))
    }

    @Test
    fun `una placa en hora no sale atrasada por medir tarde o truncar`() {
        val b = LocalDateTime.of(2026, 9, 28, 20, 15, 7)
        // La placa leyo 20:15:07 en algun punto de ese segundo; la respuesta llega al
        // telefono entre 0 y ~0,9 s despues del inicio del segundo: en hora, desfase cero.
        for (ms in listOf(0L, 100L, 450L, 900L))
            assertEquals(0L, BoardClock.driftAtArrival(b, b.plusNanos(ms * 1_000_000)),
                         "llegada a +$ms ms")
        // El metodo viejo: telefono leido tras 800 ms de silencio, con Duration.seconds.
        assertEquals(-2L, BoardClock.driftSeconds(b, b.plusNanos(1_700_000_000)))
    }

    @Test
    fun `el desfase al llegar conserva signo y magnitud`() {
        val b = LocalDateTime.of(2026, 9, 28, 20, 15, 7)
        assertEquals(125L, BoardClock.driftAtArrival(b, b.minusSeconds(125).plusNanos(300_000_000)))
        assertEquals(-3L, BoardClock.driftAtArrival(b, b.plusSeconds(3).plusNanos(300_000_000)))
    }

    @Test
    fun `el desfase lleva signo segun quien va adelantado`() {
        assertEquals(45L, BoardClock.driftSeconds(phone.plusSeconds(45), phone))
        assertEquals(-45L, BoardClock.driftSeconds(phone.minusSeconds(45), phone))
    }

    @Test
    fun `un desfase pequeno no molesta al usuario`() {
        // La deriva normal de un DS3231 no merece un aviso en cada conexion.
        // Solo un desfase de CERO pasa sin aviso. Con el umbral en 5 s, un desfase real de
        // 3 s medido contra una placa no producia ningun mensaje -- y era justo el que hacia
        // falta ver antes de sincronizar, porque sincronizar lo borra.
        assertNull(BoardClock.warning(0))
        assertNotNull(BoardClock.warning(1))
        assertNotNull(BoardClock.warning(-1))
        assertNotNull(BoardClock.warning(3))
        assertNotNull(BoardClock.warning(30))
    }

    @Test
    fun `el aviso dice cuanto y hacia que lado`() {
        assertTrue(BoardClock.warning(45)!!.contains("45 seconds ahead of"))
        assertTrue(BoardClock.warning(3)!!.contains("BEFORE synchronizing"),
                   "el aviso tiene que decir que descargar antes de sincronizar")
        assertTrue(BoardClock.warning(-45)!!.contains("45 seconds behind"))
        assertTrue(BoardClock.warning(4023)!!.contains("1 hour 7 minutes"))
    }

    @Test
    fun `el desfase se expresa en la unidad que le toca`() {
        // "4023 seconds" no dice nada; "1 hour 7 minutes" si.
        assertEquals("1 second", BoardClock.format(1))
        assertEquals("45 seconds", BoardClock.format(45))
        assertEquals("2 minutes", BoardClock.format(120))
        assertEquals("2 minutes 5 seconds", BoardClock.format(125))
        assertEquals("1 hour", BoardClock.format(3600))
        assertEquals("1 hour 7 minutes", BoardClock.format(4023))
    }
}
