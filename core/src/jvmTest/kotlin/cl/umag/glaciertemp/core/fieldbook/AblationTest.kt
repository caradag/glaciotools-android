package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.*

class AblationTest {

    private val T = 1_700_000_000_000L
    private val DIA = 86_400_000L

    private fun m(dias: Long, alturaCm: Double?) =
        StakeMeasurement(T + dias * DIA, "Ana", alturaCm)

    /** El ejemplo de la especificacion: de 80 a 95 cm en 3 dias son 5 cm/dia. */
    @Test
    fun `el caso del enunciado da cinco centimetros por dia`() {
        assertEquals(5.0, Ablation.ratePerDay(m(0, 80.0), m(3, 95.0))!!, 1e-9)
    }

    @Test
    fun `acumulacion da tasa negativa`() {
        assertEquals(-2.0, Ablation.ratePerDay(m(0, 90.0), m(5, 80.0))!!, 1e-9)
    }

    @Test
    fun `una fraccion de dia se maneja igual que un dia entero`() {
        val a = StakeMeasurement(T, "Ana", 50.0)
        val b = StakeMeasurement(T + 6 * 3_600_000L, "Ana", 51.0)  // 6 h
        assertEquals(4.0, Ablation.ratePerDay(a, b)!!, 1e-9)
    }

    /**
     * Null y no cero. Un cero seria una tasa MEDIDA de cero, que afirma algo sobre el
     * glaciar; lo que hay es que no se puede calcular.
     */
    @Test
    fun `sin altura o sin tiempo transcurrido no hay tasa`() {
        assertNull(Ablation.ratePerDay(m(0, null), m(3, 95.0)))
        assertNull(Ablation.ratePerDay(m(0, 80.0), m(3, null)))
        assertNull(Ablation.ratePerDay(m(0, 80.0), m(0, 95.0)), "mismo instante")
        assertNull(Ablation.ratePerDay(m(3, 80.0), m(0, 95.0)), "la segunda es anterior")
    }

    @Test
    fun `la primera medicion de la serie no tiene tasa`() {
        val r = Ablation.rates(listOf(m(0, 80.0), m(3, 95.0), m(5, 101.0)))
        assertEquals(3, r.size)
        assertNull(r[0])
        assertEquals(5.0, r[1]!!, 1e-9)
        assertEquals(3.0, r[2]!!, 1e-9)
    }

    /**
     * Lo que pasa al corregir una hora a mano: una medicion anotada despues queda fechada
     * antes. Si la tasa se calculara en el orden de insercion saldria negativa por el orden
     * y no por lo que hizo el glaciar.
     */
    @Test
    fun `una medicion fechada hacia atras entra en su sitio`() {
        val r = Ablation.rates(listOf(m(5, 101.0), m(0, 80.0), m(3, 95.0)))
        assertNull(r[0])
        assertEquals(5.0, r[1]!!, 1e-9)
        assertEquals(3.0, r[2]!!, 1e-9)
    }

    /**
     * Una medicion sin altura corta la serie en vez de saltarsela.
     *
     * Podria calcularse contra la ultima que SI tenia altura, pero eso convertiria un hueco
     * en un intervalo largo sin decirlo, y una tasa de seis dias mostrada como si fuera de
     * tres es peor que una celda vacia. La especificacion habla de mediciones consecutivas y
     * eso es lo que se hace.
     */
    @Test
    fun `una medicion sin altura corta la serie en vez de saltarsela`() {
        val r = Ablation.rates(listOf(m(0, 80.0), m(3, null), m(6, 98.0)))
        assertNull(r[1], "no se puede calcular contra una altura ausente")
        assertNull(r[2], "tampoco desde ella")
    }

    @Test
    fun `una serie vacia o de una sola medicion no revienta`() {
        assertEquals(emptyList(), Ablation.rates(emptyList()))
        assertEquals(listOf(null), Ablation.rates(listOf(m(0, 80.0))))
    }
}
