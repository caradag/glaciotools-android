package cl.umag.glaciertemp.core.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CompassTest {

    @Test fun `el azimut negativo que dan los sensores se lleva a 0-360`() {
        assertEquals(270.0, Compass.normalize(-90.0), 1e-9)
        assertEquals(0.0, Compass.normalize(360.0), 1e-9)
        assertEquals(10.0, Compass.normalize(730.0), 1e-9)
    }

    @Test fun `los cuatro puntos cardinales caen donde deben`() {
        assertEquals("N", Compass.cardinal(0.0))
        assertEquals("E", Compass.cardinal(90.0))
        assertEquals("S", Compass.cardinal(180.0))
        assertEquals("W", Compass.cardinal(270.0))
    }

    @Test fun `el norte envuelve por los dos lados`() {
        // 350 y 5 son los dos N: si la division se hiciera sin el medio sector, 350 caeria
        // en NNW y la brujula "saltaria" justo al cruzar el norte.
        assertEquals("N", Compass.cardinal(350.0))
        assertEquals("N", Compass.cardinal(5.0))
        assertEquals("NNW", Compass.cardinal(340.0))
        assertEquals("NNE", Compass.cardinal(25.0))
    }

    @Test fun `al cruzar el norte se muestra 0 y nunca 360`() {
        // 359,96 pasa la normalizacion pero redondeado da "360", que no es un rumbo.
        assertEquals("0°", Compass.format(359.96))
        assertEquals("0°", Compass.format(0.0))
        assertEquals("359.9°", Compass.format(359.94, 1))
        assertEquals("0.0°", Compass.format(359.99, 1))
    }

    @Test fun `un azimut negativo tambien da su punto`() {
        assertEquals("W", Compass.cardinal(-90.0))
    }
}

class AlbedoTest {

    @Test fun `el albedo es reflejada entre incidente`() {
        assertEquals(0.8, AlbedoRun.albedo(1000.0, 800.0)!!, 1e-9)
    }

    @Test fun `sin luz incidente no hay albedo`() {
        // De noche o con el sensor tapado. Devolver 0 seria inventarse una medida.
        assertNull(AlbedoRun.albedo(0.0, 50.0))
        assertNull(AlbedoRun.albedo(-1.0, 50.0))
    }

    @Test fun `un albedo mayor que uno se avisa en vez de darlo por bueno`() {
        val w = AlbedoRun.warning(500.0, 700.0)
        assertTrue(w != null && w.contains("Repeat"), "debe pedir repetir: $w")
    }

    @Test fun `con poca luz se avisa de que es ruido`() {
        val w = AlbedoRun.warning(20.0, 10.0)
        assertTrue(w != null && w.contains("noise"), "debe hablar de ruido: $w")
    }

    @Test fun `una medida normal sobre nieve no lleva aviso`() {
        assertNull(AlbedoRun.warning(50000.0, 40000.0))
    }

    @Test fun `los tres pitidos se distinguen entre si`() {
        val prep = AlbedoRun.beep(midiendo = false, ultimo = false)
        val hold = AlbedoRun.beep(midiendo = true, ultimo = false)
        val fin = AlbedoRun.beep(midiendo = true, ultimo = true)
        // El de medir es MAS GRAVE que el de la cuenta atras: es lo que dice, sin mirar la
        // pantalla, que ya no hay que moverse.
        assertTrue(hold.hz < prep.hz, "el de medir debe ser mas grave")
        // Y el final, mas largo que los dos.
        assertTrue(fin.ms > prep.ms && fin.ms > hold.ms, "el final debe ser mas largo")
    }
}

class SensorReportTest {

    @Test fun `lo copiado dice que es, cuanto y cuando`() {
        val t = SensorReport.tilt(12.0, -3.5, 0.2, "2026-09-26 10:00")
        assertTrue(t.contains("2026-09-26 10:00"), t)
        assertTrue(t.contains("Yaw"), t)
        assertTrue(t.contains("Pitch"), t)
        assertTrue(t.contains("Roll"), t)
        assertTrue(t.contains("NNE"), "el rumbo en palabras acompana a los grados: $t")
    }

    @Test fun `el informe de albedo lleva las dos medidas y el cociente`() {
        val r = SensorReport.albedo(1000.0, 800.0, "2026-09-26 10:00")
        assertTrue(r.contains("Incident"), r)
        assertTrue(r.contains("Reflected"), r)
        assertTrue(r.contains("0.80"), "debe llevar el albedo: $r")
        // Y la advertencia sobre el instrumento SIEMPRE, no solo cuando algo va mal: quien
        // lea esto pegado en un correo no tiene la pantalla delante para saberlo.
        assertTrue(r.contains("not radiometric"), r)
    }

    @Test fun `un albedo imposible se copia con su aviso`() {
        val r = SensorReport.albedo(500.0, 700.0, "x")
        assertTrue(r.contains("Note:"), r)
    }

    @Test fun `sin luz el albedo se copia como raya y no como cero`() {
        val r = SensorReport.albedo(0.0, 0.0, "x")
        assertTrue(r.contains("Albedo: —"), r)
        assertFalse(r.contains("Albedo: 0.00"), r)
    }
}
