package cl.umag.glaciertemp.core.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnglesTest {

    @Test fun `sin cruzar ningun salto da lo mismo que la media de siempre`() {
        assertEquals(30.0, Angles.mean(listOf(20.0, 30.0, 40.0))!!, 1e-6)
        assertEquals(30.0, Angles.median(listOf(20.0, 30.0, 40.0))!!, 1e-6)
    }

    @Test fun `la media de 359 y 1 es el norte, no el sur`() {
        // El fallo que justifica todo este fichero: la media aritmetica daria 180.
        val m = Angles.mean(listOf(359.0, 1.0))!!
        assertEquals(0.0, Angles.wrap(m), 1e-6)
    }

    @Test fun `la mediana tampoco se rompe al cruzar el norte`() {
        // Ordenando a lo bruto, 358,359,0,1,2 da 2. El valor correcto es 0.
        val m = Angles.median(listOf(358.0, 359.0, 0.0, 1.0, 2.0))!!
        assertEquals(0.0, Angles.wrap(m), 1e-6)
    }

    @Test fun `funciona igual con el salto de mas menos 180, que es el telefono boca abajo`() {
        val m = Angles.mean(listOf(179.0, -179.0))!!
        assertEquals(180.0, kotlin.math.abs(m), 1e-6)
        val md = Angles.median(listOf(178.0, 179.0, -180.0, -179.0, -178.0))!!
        assertEquals(180.0, kotlin.math.abs(md), 1e-6)
    }

    @Test fun `la mediana aguanta una muestra disparatada que la media no`() {
        // Cuatro lecturas juntas y un salto: la mediana se queda con el grupo.
        val v = listOf(10.0, 11.0, 12.0, 13.0, 200.0)
        val med = Angles.median(v)!!
        assertTrue(kotlin.math.abs(Angles.wrap(med - 12.0)) < 2.0,
                   "la mediana debe quedarse cerca del grupo, y dio $med")
    }

    @Test fun `con la lista vacia no se inventa un angulo`() {
        assertNull(Angles.mean(emptyList()))
        assertNull(Angles.median(emptyList()))
    }

    @Test fun `con angulos repartidos por igual no hay direccion media`() {
        // Cuatro puntos cardinales: el vector suma es cero. Devolver uno seria elegirlo al
        // azar entre los cuatro, que es peor que decir que no hay.
        assertNull(Angles.mean(listOf(0.0, 90.0, 180.0, 270.0)))
    }

    @Test fun `wrap deja el intervalo en menos 180 abierto y 180 cerrado`() {
        assertEquals(180.0, Angles.wrap(180.0), 1e-9)
        assertEquals(180.0, Angles.wrap(-180.0), 1e-9)
        assertEquals(-90.0, Angles.wrap(270.0), 1e-9)
    }
}

class TiltTest {

    @Test fun `tumbado sobre una pendiente no hay aviso`() {
        assertNull(Tilt.warning(0.0))
        assertNull(Tilt.warning(-25.0))
        assertNull(Tilt.warning(79.0))
    }

    @Test fun `de pie contra una pared si lo hay`() {
        // Es la postura que la propia herramienta sugiere, asi que el aviso tiene que salir.
        assertTrue(Tilt.gimbalLock(-90.0))
        assertTrue(Tilt.gimbalLock(89.0))
        val w = Tilt.warning(-90.0)
        assertTrue(w != null && w.contains("pitch only"), "debe decir que sirve el pitch: $w")
    }

    @Test fun `sin pitch no se avisa de nada`() {
        assertNull(Tilt.warning(null))
        assertFalse(Tilt.gimbalLock(null))
    }
}

class ScalarsTest {

    @Test fun `media y mediana de numeros corrientes`() {
        assertEquals(20.0, Scalars.mean(listOf(10.0, 20.0, 30.0))!!, 1e-9)
        assertEquals(20.0, Scalars.median(listOf(10.0, 20.0, 30.0))!!, 1e-9)
    }

    @Test fun `con un numero par se promedian las dos centrales`() {
        assertEquals(25.0, Scalars.median(listOf(10.0, 20.0, 30.0, 40.0))!!, 1e-9)
    }

    @Test fun `un reflejo se lleva la media pero no la mediana`() {
        // Cuatro segundos de 1000 lx y un destello de 50000: es el caso real que motiva
        // dar las dos cifras.
        val v = listOf(1000.0, 1010.0, 990.0, 1005.0, 50000.0)
        assertTrue(Scalars.mean(v)!! > 10000.0, "la media se va con el destello")
        assertEquals(1005.0, Scalars.median(v)!!, 1e-9)
    }

    @Test fun `sin muestras no hay cifra`() {
        assertNull(Scalars.mean(emptyList()))
        assertNull(Scalars.median(emptyList()))
    }
}
