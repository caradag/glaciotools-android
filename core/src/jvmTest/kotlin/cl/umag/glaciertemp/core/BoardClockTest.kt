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
    fun `una respuesta sin hora no inventa una`() {
        assertNull(BoardClock.parse("Wrong format: TIME=abc"))
        assertNull(BoardClock.parse(""))
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
