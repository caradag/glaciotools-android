package cl.umag.glaciertemp.core.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReversalTest {

    @Test fun `el buzamiento sale de la media y el sesgo de la semidiferencia`() {
        // Plano real a 20 grados, sensor con medio grado de sesgo: una lectura 20,5 y la
        // otra 19,5 porque al girar el telefono el sesgo entra al reves.
        val r = Reversal.surface(20.5, 19.5)
        assertEquals(20.0, r.value, 1e-9)
        assertEquals(0.5, r.bias, 1e-9)
    }

    @Test fun `el pitch del aparato sale de la semidiferencia, NO de la media`() {
        // Inclinacion real 12 grados, sesgo 0,4. Primera posicion 12,4; girado, -11,6.
        // Promediar a lo bruto daria 0,4 --el sesgo-- en vez de 12.
        val r = Reversal.device(12.4, -11.6)
        assertEquals(12.0, r.value, 1e-9)
        assertEquals(0.4, r.bias, 1e-9)
        // Y la trampa, explicita: la media de las dos lecturas NO es el valor.
        assertTrue(kotlin.math.abs((12.4 + -11.6) / 2.0 - r.value) > 10.0,
                   "promediar a lo bruto tendria que dar algo muy distinto del valor real")
    }

    @Test fun `el rumbo se combina girando la segunda media vuelta`() {
        // Rumbo real 40. Segunda lectura tomada con el telefono girado: 220.
        val r = Reversal.heading(40.0, 220.0)
        assertEquals(40.0, r.value, 1e-6)
        assertEquals(0.0, r.bias, 1e-6)
    }

    @Test fun `el rumbo cancela el hierro duro`() {
        // Sesgo de 6 grados que entra con signo contrario: 46 y 214 (o sea 34 alineado).
        val r = Reversal.heading(46.0, 214.0)
        assertEquals(40.0, r.value, 1e-6)
        assertEquals(6.0, kotlin.math.abs(r.bias), 1e-6)
    }

    @Test fun `el rumbo funciona cruzando el norte`() {
        // Rumbo real 2 grados: la reversion es 182. Es el caso donde una media aritmetica
        // daria 92, o sea el este.
        val r = Reversal.heading(2.0, 182.0)
        assertEquals(2.0, Angles.wrap(r.value), 1e-6)
    }

    @Test fun `un sesgo despreciable no genera aviso`() {
        assertNull(Reversal.warning(listOf(0.2, 0.4, 0.1)))
    }

    @Test fun `un sesgo grande dice que el telefono no se apoyo igual`() {
        val w = Reversal.warning(listOf(0.3, 4.0))
        assertNotNull(w)
        assertTrue(w.contains("Repeat"), w)
        assertTrue(w.contains("8.0°"), "debe decir cuanto discrepan las dos posiciones: $w")
    }

    @Test fun `un sesgo intermedio se informa sin alarmar`() {
        val w = Reversal.warning(listOf(1.5))
        assertNotNull(w)
        assertTrue(w.contains("plausible"), w)
    }

    @Test fun `sin lecturas no hay nada que avisar`() {
        assertNull(Reversal.warning(emptyList()))
    }
}
