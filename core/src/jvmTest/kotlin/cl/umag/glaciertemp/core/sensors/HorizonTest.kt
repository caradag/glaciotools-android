package cl.umag.glaciertemp.core.sensors

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Punta Arenas. Es donde se usa la app, y su latitud alta hace los efectos grandes. */
private const val LAT = -53.16
private const val LON = -70.91
private val ZONA = ZoneId.of("America/Santiago")

class ViewDirectionTest {

    /** R lleva del aparato al mundo (Este, Norte, Arriba), en orden por filas. */
    private fun matriz(vararg v: Double) = FloatArray(9) { v[it].toFloat() }

    @Test fun `telefono vertical mirando al norte`() {
        // Aparato: X derecha, Y arriba de la pantalla, Z sale de la pantalla hacia el usuario.
        // De pie mirando al norte: la camara (-Z) apunta al norte, +X al este, +Y arriba.
        //        este        norte      arriba
        val R = matriz(1.0, 0.0, 0.0,    // fila Este:   e = X
                       0.0, 0.0, -1.0,   // fila Norte:  n = -Z
                       0.0, 1.0, 0.0)    // fila Arriba: u = Y
        val (az, el) = ViewDirection.of(R)
        assertEquals(0.0, az, 1e-6)
        assertEquals(0.0, el, 1e-6)
        assertEquals(0.0, ViewDirection.roll(R), 1e-6)
    }

    @Test fun `telefono tumbado con la camara al suelo`() {
        // Boca arriba sobre una mesa: la camara trasera mira ABAJO.
        val R = matriz(1.0, 0.0, 0.0,
                       0.0, 1.0, 0.0,
                       0.0, 0.0, 1.0)
        val (_, el) = ViewDirection.of(R)
        assertEquals(-90.0, el, 1e-6)
    }

    @Test fun `la elevacion no depende del giro sobre el eje de la vista`() {
        // ESTE ES EL PUNTO de usar la matriz y no los angulos de Euler: de pie, que es donde
        // yaw y roll se confunden, la direccion de la camara sigue saliendo exacta.
        val R = matriz(0.0, 1.0, 0.0,    // ladeado 90 grados
                       0.0, 0.0, -1.0,
                       -1.0, 0.0, 0.0)
        val (az, el) = ViewDirection.of(R)
        assertEquals(0.0, az, 1e-6)
        assertEquals(0.0, el, 1e-6)
        assertEquals(90.0, abs(ViewDirection.roll(R)), 1e-6)
    }
}

class HorizonBinsTest {

    @Test fun `promedia dentro de cada sector`() {
        val b = HorizonBins(5)
        b.add(1.0, 10.0); b.add(2.0, 20.0)   // los dos en el sector 0
        b.add(7.0, 30.0)                      // sector 1
        assertEquals(15.0, b.mean(0)!!, 1e-9)
        assertEquals(30.0, b.mean(1)!!, 1e-9)
        assertEquals(2, b.samples(0))
    }

    @Test fun `el azimut da la vuelta sin salirse`() {
        val b = HorizonBins(5)
        b.add(359.9, 5.0)
        b.add(-0.1, 7.0)     // lo mismo por el otro lado
        assertEquals(71, b.binOf(359.9))
        assertEquals(71, b.binOf(-0.1))
        assertEquals(6.0, b.mean(71)!!, 1e-9)
    }

    @Test fun `un sector nunca visitado no inventa una media`() {
        val b = HorizonBins(5)
        b.add(0.0, 10.0)
        assertNull(b.mean(10))
        assertEquals(1, b.covered())
    }

    @Test fun `los huecos se rellenan interpolando entre vecinos`() {
        val b = HorizonBins(90)               // cuatro sectores, mas facil de comprobar
        b.add(45.0, 0.0)                      // sector 0
        b.add(225.0, 20.0)                    // sector 2
        val p = b.profile()!!
        // El sector 1 esta a medio camino de los dos: 10 grados.
        assertEquals(10.0, p.elevations[1], 1e-6)
        // Y el 3 tambien, dando la vuelta por el otro lado.
        assertEquals(10.0, p.elevations[3], 1e-6)
    }

    @Test fun `sin ninguna medida no hay perfil`() {
        assertNull(HorizonBins().profile())
    }
}

class HorizonProfileTest {

    private fun plano(el: Double) = HorizonProfile(DoubleArray(72) { el }, 5)

    @Test fun `un horizonte a cero deja el hemisferio entero a la vista`() {
        assertEquals(1.0, plano(0.0).skyFraction(), 1e-9)
    }

    @Test fun `un horizonte a 30 grados tapa la mitad del cielo`() {
        // 1 - sin(30) = 0,5. Es la comprobacion que se puede hacer a mano.
        assertEquals(0.5, plano(30.0).skyFraction(), 1e-9)
    }

    @Test fun `un horizonte a 90 grados no deja nada`() {
        assertEquals(0.0, plano(90.0).skyFraction(), 1e-9)
    }

    @Test fun `un horizonte por debajo de la horizontal no da mas del cien por cien`() {
        // Desde una cumbre se ve suelo lejano, no mas cielo.
        assertEquals(1.0, plano(-20.0).skyFraction(), 1e-9)
    }

    @Test fun `senala de donde viene la sombra mas alta`() {
        val v = DoubleArray(72)
        v[18] = 40.0                          // sector centrado en 92,5 grados: el este
        val p = HorizonProfile(v, 5)
        assertEquals(92.5, p.highestAzimuth(), 1e-9)
        assertEquals(40.0, p.maxElevation(), 1e-9)
    }
}

class SolarTest {

    private fun mediodiaSolar(fecha: LocalDate): Solar.Position =
        Solar.dayTrack(LAT, LON, fecha, ZONA, 1).maxBy { it.elevation }

    @Test fun `en el solsticio de invierno el sol apenas sube en Punta Arenas`() {
        // 90 - 53,16 - 23,44 = 13,4 grados. Si los signos estuvieran cambiados saldria 60.
        val p = mediodiaSolar(LocalDate.of(2026, 6, 21))
        assertEquals(13.4, p.elevation, 0.5)
    }

    @Test fun `en el de verano sube a sesenta`() {
        val p = mediodiaSolar(LocalDate.of(2026, 12, 21))
        assertEquals(60.3, p.elevation, 0.5)
    }

    @Test fun `al mediodia el sol esta al NORTE en el hemisferio sur`() {
        // El error de signo clasico pondria el sol al sur. Aqui se ve enseguida.
        listOf(LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21)).forEach { d ->
            val p = mediodiaSolar(d)
            val desviacion = abs(Angles.wrap(p.azimuth - 0.0))
            assertTrue(desviacion < 15.0,
                       "el ${d}: el sol al mediodia estaba en azimut ${p.azimuth}")
        }
    }

    @Test fun `en el ecuador y en el equinoccio el sol pasa por el cenit`() {
        val p = Solar.dayTrack(0.0, 0.0, LocalDate.of(2026, 3, 20), ZoneId.of("UTC"), 1)
            .maxBy { it.elevation }
        assertEquals(90.0, p.elevation, 1.0)
    }

    @Test fun `el dia dura mas en verano que en invierno`() {
        fun horas(d: LocalDate) =
            Solar.dayTrack(LAT, LON, d, ZONA, 5).count { it.elevation > 0 } * 5 / 60.0
        val invierno = horas(LocalDate.of(2026, 6, 21))
        val verano = horas(LocalDate.of(2026, 12, 21))
        assertTrue(invierno < 8.0, "invierno duro $invierno h")
        assertTrue(verano > 16.0, "verano duro $verano h")
    }

    @Test fun `los solsticios se nombran segun el hemisferio`() {
        val (inviernoSur, veranoSur) = Solar.solstices(2026, LAT)
        assertEquals(6, inviernoSur.date.monthValue)
        assertEquals(12, veranoSur.date.monthValue)
        // En el norte, al reves. Una tabla escrita alli pondria el peor dia donde se espera
        // el mejor.
        val (inviernoNorte, veranoNorte) = Solar.solstices(2026, 60.0)
        assertEquals(12, inviernoNorte.date.monthValue)
        assertEquals(6, veranoNorte.date.monthValue)
    }

    @Test fun `la masa de aire castiga al sol rasante`() {
        assertTrue(Solar.beamFraction(90.0) > 0.65)
        assertTrue(Solar.beamFraction(10.0) < Solar.beamFraction(40.0))
        assertEquals(0.0, Solar.beamFraction(0.0), 1e-12)
        assertEquals(0.0, Solar.beamFraction(-5.0), 1e-12)
    }
}

class SolarPanelTest {

    private val verano = LocalDate.of(2026, 12, 21)
    private fun track(d: LocalDate) = Solar.dayTrack(LAT, LON, d, ZONA, 5)

    @Test fun `sin horizonte el panel mira al norte en el hemisferio sur`() {
        val b = SolarPanel.best(track(verano), null)!!
        val desviacion = abs(Angles.wrap(b.azimuthDeg.toDouble()))
        assertTrue(desviacion <= 20.0, "el panel miraba a ${b.azimuthDeg}")
    }

    @Test fun `en invierno se inclina mas que en verano`() {
        // El sol esta mas bajo, asi que la cara del panel tiene que levantarse para mirarlo.
        val inv = SolarPanel.best(track(LocalDate.of(2026, 6, 21)), null)!!
        val ver = SolarPanel.best(track(verano), null)!!
        assertTrue(inv.tiltDeg > ver.tiltDeg,
                   "invierno ${inv.tiltDeg} vs verano ${ver.tiltDeg}")
    }

    @Test fun `un muro al norte empuja el panel a mirar a otro lado`() {
        // Es la razon de ser de medir el horizonte: sin obstruccion la respuesta esta en una
        // tabla; con un cerro delante, la tabla se equivoca.
        val v = DoubleArray(72)
        for (b in 0 until 72) {
            val az = (b + 0.5) * 5.0
            if (abs(Angles.wrap(az)) < 60.0) v[b] = 70.0   // pared al norte
        }
        val tapado = SolarPanel.best(track(verano), HorizonProfile(v, 5))!!
        val libre = SolarPanel.best(track(verano), null)!!
        assertTrue(abs(Angles.wrap(tapado.azimuthDeg.toDouble())) > 60.0,
                   "con el norte tapado seguia mirando a ${tapado.azimuthDeg}")
        assertTrue(tapado.energy < libre.energy)
        assertTrue(tapado.shadingLoss() > 0.2, "perdida ${tapado.shadingLoss()}")
    }

    @Test fun `sin obstruccion no se pierde nada por sombra`() {
        val b = SolarPanel.best(track(verano), null)!!
        assertEquals(0.0, b.shadingLoss(), 1e-9)
    }

    @Test fun `las horas de sol se reparten entre vistas y tapadas`() {
        val v = DoubleArray(72) { 20.0 }
        val (visibles, tapadas) = SolarPanel.sunHours(track(verano), HorizonProfile(v, 5), 5)
        assertTrue(tapadas > 0.0, "un horizonte a 20 grados tiene que tapar algo")
        val (todas, nada) = SolarPanel.sunHours(track(verano), null, 5)
        assertEquals(0.0, nada, 1e-9)
        assertTrue(todas > visibles)
    }

    @Test fun `de noche no hay optimo que calcular`() {
        val nocturno = track(verano).filter { it.elevation < -10 }
        assertNull(SolarPanel.best(nocturno, null))
    }
}
