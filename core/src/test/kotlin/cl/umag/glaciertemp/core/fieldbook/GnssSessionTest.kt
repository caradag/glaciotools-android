package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.*

/**
 * El contrato del que dependen el cronometro, la alarma y el servicio en primer plano.
 *
 * Se prueba aqui y con cuidado porque el sitio donde estas reglas se ven fallar --un telefono
 * en el bolsillo durante dos horas en un glaciar-- es el peor sitio posible para descubrir
 * que alguna no se cumple.
 */
class GnssSessionTest {

    private val T = 1_700_000_000_000L

    @Test
    fun `una sesion vacia no se guarda`() {
        assertTrue(GnssSession().isEmpty)
        assertFalse(GnssSession(receiver = "Emlid RS2").isEmpty)
        assertFalse(GnssSession(startEpochMillis = T).isEmpty)
    }

    @Test
    fun `esta en marcha entre el inicio y el termino, y solo ahi`() {
        assertFalse(GnssSession().running, "sin empezar")
        assertTrue(GnssSession(startEpochMillis = T).running)
        assertFalse(GnssSession(startEpochMillis = T, endEpochMillis = T + 1000).running)
    }

    @Test
    fun `la duracion se calcula y no se guarda, asi que sigue a las marcas`() {
        val s = GnssSession(startEpochMillis = T, endEpochMillis = T + 1_800_000L)
        assertEquals(1_800_000L, s.durationMillis)

        // Corregir a mano la hora de inicio recalcula sola la duracion. Con la duracion
        // guardada aparte, esta linea seguiria diciendo media hora.
        assertEquals(3_600_000L, s.copy(startEpochMillis = T - 1_800_000L).durationMillis)
    }

    @Test
    fun `sin uno de los dos extremos no hay duracion`() {
        assertNull(GnssSession(startEpochMillis = T).durationMillis)
        assertNull(GnssSession(endEpochMillis = T).durationMillis)
    }

    /**
     * Un termino anterior al inicio no es una duracion negativa: es un dato incoherente,
     * normalmente por haber corregido una marca a mano. Devolver el numero negativo lo
     * pintaria como "-15 min", que se lee como si significara algo.
     */
    @Test
    fun `un termino anterior al inicio no da duracion`() {
        assertNull(GnssSession(startEpochMillis = T, endEpochMillis = T - 1000).durationMillis)
    }

    @Test
    fun `el vencimiento programado sale del inicio mas los minutos elegidos`() {
        assertEquals(T + 30 * 60_000L,
                     GnssSession(startEpochMillis = T, plannedMinutes = 30).plannedEndEpochMillis)
    }

    /**
     * Sin duracion programada NO hay instante de vencimiento, y por tanto no hay alarma que
     * programar. Devolver el inicio, o cero, haria que el servicio programase una alarma para
     * un instante ya pasado y sonara nada mas empezar a medir.
     */
    @Test
    fun `sin duracion programada no hay vencimiento`() {
        assertNull(GnssSession(startEpochMillis = T).plannedEndEpochMillis)
        assertNull(GnssSession(plannedMinutes = 30).plannedEndEpochMillis, "ni sin inicio")
    }

    /**
     * Cambiar la duracion a mitad mueve el vencimiento, y lo mueve respecto al INICIO REAL y
     * no respecto al momento en que se cambio. Si no, elegir "30 min" a los veinte minutos de
     * empezar daria cincuenta.
     */
    @Test
    fun `cambiar la duracion a mitad la cuenta desde el inicio`() {
        val s = GnssSession(startEpochMillis = T, plannedMinutes = 15)
        assertEquals(T + 45 * 60_000L, s.copy(plannedMinutes = 45).plannedEndEpochMillis)
    }

    /**
     * La alarma no termina la medicion: pasado el vencimiento la sesion SIGUE en marcha, para
     * que el termino sea el momento real en que se levanto el receptor y no la hora a la que
     * venia programado levantarlo.
     */
    @Test
    fun `pasado el vencimiento la medicion sigue en marcha`() {
        val s = GnssSession(startEpochMillis = T, plannedMinutes = 5)
        val muchoDespues = T + 60 * 60_000L
        assertTrue(s.running)
        assertTrue(s.plannedEndEpochMillis!! < muchoDespues, "el plazo ya vencio")
        assertNull(s.durationMillis, "y aun asi no hay duracion: no ha terminado")
    }

    /** Las duraciones que ofrece el desplegable, en el orden de la especificacion. */
    @Test
    fun `una sesion completa vuelve del disco con sus cuatro campos`() {
        val e = FieldEntry(
            id = "e1", type = EntryType.GNSS, createdEpochMillis = T,
            pointName = "BASE1",
            gnss = GnssSession("Emlid RS2", T, T + 5_400_000L, 90))
        val leida = assertNotNull(FieldbookFile.parse(FieldbookFile.write(e))).gnss
        assertEquals("Emlid RS2", leida?.receiver)
        assertEquals(90, leida?.plannedMinutes)
        assertEquals(5_400_000L, leida?.durationMillis)
    }
}
