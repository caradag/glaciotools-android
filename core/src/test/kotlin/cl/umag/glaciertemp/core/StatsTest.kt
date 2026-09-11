package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.test.*

class StatsTest {

    private val t0 = LocalDateTime.of(2026, 1, 1, 0, 0)
    private fun span(minutes: Long) = Stats.formatSpan(t0, t0.plusMinutes(minutes))

    @Test
    fun `la duracion se expresa en la unidad que le toca`() {
        assertEquals("45 min", span(45))
        assertEquals("1 hour", span(60))
        assertEquals("6 hours 30 min", span(6 * 60 + 30))
        assertEquals("36 hours", span(36 * 60))
        // A partir de dos dias deja de contarse en horas.
        assertEquals("2 days", span(48 * 60))
        assertEquals("2 days 6 hours", span(54 * 60))
        assertEquals("45 days", span(45 * 24 * 60))
    }

    @Test
    fun `a partir de dos meses se cuenta en meses y dias`() {
        // Los meses no duran todos lo mismo: se cuentan sobre el calendario, no dividiendo
        // por 30. Del 1 de enero al 15 de marzo son 2 meses y 14 dias, no 73 dias.
        assertEquals("2 months and 14 days",
            Stats.formatSpan(t0, LocalDateTime.of(2026, 3, 15, 0, 0)))
        assertEquals("3 months",
            Stats.formatSpan(t0, LocalDateTime.of(2026, 4, 1, 0, 0)))
        // Mas de un ano se sigue contando en meses: son campanas de terreno, y "13 meses"
        // se compara mejor entre despliegues que "1 ano y 1 mes".
        assertEquals("13 months",
            Stats.formatSpan(t0, LocalDateTime.of(2027, 2, 1, 0, 0)))
    }

    @Test
    fun `el singular no se escribe en plural`() {
        assertEquals("1 hour", span(60))
        assertEquals("2 days 1 hour", span(49 * 60))
        assertEquals("2 months and 1 day",
            Stats.formatSpan(t0, LocalDateTime.of(2026, 3, 2, 0, 0)))
    }

    @Test
    fun `toda la salida esta en ingles`() {
        // Una traduccion a medias no se ve: la rama que quedo en espanol solo aparece con
        // una duracion de cierto tamano, y nadie prueba todas. Se recorre el rango y se
        // exige que no salga ningun caracter fuera del alfabeto ingles y los digitos.
        val permitido = Regex("[a-z0-9 ]+")
        val duraciones = listOf(0L, 1L, 45L, 60L, 90L, 390L, 2160L, 2880L, 3240L,
                                64800L, 86400L, 129600L, 525600L, 1051200L)
        for (m in duraciones) {
            val texto = span(m)
            assertTrue(permitido.matches(texto),
                       "formatSpan($m min) devuelve '$texto', que no esta en ingles")
        }
    }

    @Test
    fun `dos marcas iguales no son una duracion`() {
        assertEquals("instantaneous", Stats.formatSpan(t0, t0))
    }

    private fun records(values: List<Double?>): List<Record> =
        values.mapIndexed { i, v -> Record(t0.plusMinutes(10L * i), listOf(1.5, v, 50.0, 0.0)) }

    @Test
    fun `minimo y maximo traen la fecha en que ocurrieron`() {
        val st = assertNotNull(Stats.channel(
            records(listOf(-2.0, -7.5, 3.0, 0.5)), 0x100F, "Temp"))
        assertEquals(-7.5, st.min)
        assertEquals(t0.plusMinutes(10), st.minAt)
        assertEquals(3.0, st.max)
        assertEquals(t0.plusMinutes(20), st.maxAt)
        assertEquals(4, st.count)
        assertEquals(0, st.missing)
    }

    @Test
    fun `las lecturas fallidas se cuentan y no entran en la media`() {
        val st = assertNotNull(Stats.channel(
            records(listOf(2.0, null, 4.0)), 0x100F, "Temp"))
        assertEquals(2, st.count)
        assertEquals(1, st.missing)
        assertEquals(3.0, st.mean)
        assertEquals(2.0, st.min)
        assertEquals(4.0, st.max)
    }

    @Test
    fun `el periodo lo marcan los registros, no las lecturas validas del canal`() {
        // El logger estuvo funcionando todo el rato aunque este sensor fallara al principio
        // y al final; decir que el periodo es mas corto seria mentir sobre el despliegue.
        val st = assertNotNull(Stats.channel(
            records(listOf(null, 2.0, null)), 0x100F, "Temp"))
        assertEquals(t0, st.first)
        assertEquals(t0.plusMinutes(20), st.last)
    }

    @Test
    fun `un canal sin ninguna lectura valida no tiene estadisticas`() {
        assertNull(Stats.channel(records(listOf(null, null)), 0x100F, "Temp"))
        assertNull(Stats.channel(emptyList(), 0x100F, "Temp"))
    }

    @Test
    fun `los valores se formatean con los decimales del canal`() {
        val st = assertNotNull(Stats.channel(records(listOf(2.0)), 0x100F, "Temp"))
        assertEquals(2, st.decimals)          // Temp lleva dos decimales
        assertEquals("2.00", st.format(st.min))
    }

    @Test
    fun `un canal desconocido es un error de programacion`() {
        assertFailsWith<IllegalArgumentException> {
            Stats.channel(records(listOf(1.0)), 0x100F, "Presion")
        }
    }
}
