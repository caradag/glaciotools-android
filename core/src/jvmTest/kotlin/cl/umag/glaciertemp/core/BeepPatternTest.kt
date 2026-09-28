package cl.umag.glaciertemp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeepPatternTest {

    @Test fun `calla fuera de los ultimos diez segundos`() {
        for (s in 1..49) assertNull(BeepPattern.at(s), "el segundo $s no deberia sonar")
    }

    @Test fun `suenan los diez ultimos y el cambio de minuto`() {
        for (s in 50..59) assertNotNull(BeepPattern.at(s), "el segundo $s deberia sonar")
        assertNotNull(BeepPattern.at(0))
    }

    @Test fun `el tono sube segundo a segundo`() {
        val tonos = (50..59).map { BeepPattern.at(it)!!.hz }
        assertEquals(tonos.sorted(), tonos, "la cuenta atras tiene que subir, no bajar")
        assertTrue(tonos.zipWithNext().all { (a, b) -> b > a * 1.02 },
                   "dos segundos seguidos tienen que sonar claramente distintos")
    }

    @Test fun `los dos ultimos son dobles y el resto no`() {
        for (s in 50..57) assertEquals(1, BeepPattern.at(s)!!.repeticiones, "segundo $s")
        assertEquals(2, BeepPattern.at(58)!!.repeticiones)
        assertEquals(2, BeepPattern.at(59)!!.repeticiones)
    }

    /**
     * La marca tiene que distinguirse de lo que la anuncia. Si el cambio de minuto sonara
     * igual que la cuenta atras, no serviria de marca: es el unico pitido que importa.
     */
    @Test fun `el cambio de minuto es mas largo y mas grave que la cuenta atras`() {
        val minuto = BeepPattern.at(0)!!
        val ultimo = BeepPattern.at(59)!!
        assertTrue(minuto.ms > ultimo.ms * 3, "el del minuto tiene que ser claramente largo")
        assertTrue(minuto.hz < BeepPattern.at(50)!!.hz, "y mas grave que toda la cuenta atras")
    }
}
