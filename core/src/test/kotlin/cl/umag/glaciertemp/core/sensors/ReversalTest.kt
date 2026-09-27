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

/**
 * Los dos errores REALES que se cometieron al elegir la combinacion, con el sintoma que
 * daban: un sesgo de casi 90 grados pasara lo que pasara. Van con su sintoma escrito para
 * que la prueba falle de forma reconocible si alguien vuelve a cambiarlas.
 */
class ReversalClasificacionTest {

    @Test fun `el rumbo de la SUPERFICIE no gira con el telefono`() {
        // El strike sale de la normal del plano, que es el eje del giro: no se entera.
        val r = Reversal.surfaceHeading(120.0, 120.0)
        assertEquals(120.0, r.value, 1e-6)
        assertEquals(0.0, r.bias, 1e-6)
    }

    @Test fun `combinar el strike como si girara da casi noventa grados de sesgo`() {
        // EL SINTOMA que se vio en terreno, con dos lecturas reales de un mismo plano y
        // cuatro grados de sesgo. Se deja escrito para reconocerlo si vuelve.
        val malo = Reversal.heading(124.0, 116.0)
        assertTrue(kotlin.math.abs(malo.bias) > 80.0,
                   "usar heading para el strike da un sesgo enorme: ${malo.bias}")
        // Y lo correcto son cuatro grados.
        assertEquals(4.0, Reversal.surfaceHeading(124.0, 116.0).bias, 1e-6)
    }

    @Test fun `el strike cancela su sesgo promediando`() {
        // Cuatro grados de sesgo que entran con signo contrario en las dos posiciones.
        val r = Reversal.surfaceHeading(124.0, 116.0)
        assertEquals(120.0, r.value, 1e-6)
        assertEquals(4.0, r.bias, 1e-6)
    }

    @Test fun `el strike funciona cruzando el norte`() {
        val r = Reversal.surfaceHeading(357.0, 3.0)
        assertEquals(0.0, r.value, 1e-6)
        // wrap(357-3) = -6, y la mitad es -3.
        assertEquals(-3.0, r.bias, 1e-6)
    }

    @Test fun `la elevacion de vista se combina quitando el desplazamiento de 90`() {
        // Pitch de Android -20, o sea elevacion de vista -70. Girado, el pitch cambia de
        // signo y la elevacion pasa a -110. Sin sesgo, el valor tiene que ser -70.
        val r = Reversal.viewElevation(-70.0, -110.0)
        assertEquals(-70.0, r.value, 1e-9)
        assertEquals(0.0, r.bias, 1e-9)
    }

    @Test fun `tratar la elevacion de vista como magnitud del aparato da menos noventa siempre`() {
        // EL OTRO SINTOMA visto en terreno: el sesgo salia -90 con cualquier inclinacion.
        listOf(-90.0 to -90.0, -70.0 to -110.0, -125.0 to -55.0).forEach { (a, b) ->
            assertEquals(-90.0, Reversal.device(a, b).bias, 1e-9,
                         "device sobre la elevacion de vista da -90 de sesgo para $a y $b")
        }
    }

    @Test fun `la elevacion de vista plana sale plana`() {
        // Telefono tumbado: -90 en las dos posiciones, y -90 es la respuesta.
        val r = Reversal.viewElevation(-90.0, -90.0)
        assertEquals(-90.0, r.value, 1e-9)
        assertEquals(0.0, r.bias, 1e-9)
    }

    @Test fun `la elevacion de vista cancela su sesgo`() {
        // Un grado de sesgo: las lecturas se apartan un grado de lo que tocaria.
        val r = Reversal.viewElevation(-69.0, -109.0)
        assertEquals(-70.0, r.value, 1e-9)
        assertEquals(1.0, r.bias, 1e-9)
    }
}
