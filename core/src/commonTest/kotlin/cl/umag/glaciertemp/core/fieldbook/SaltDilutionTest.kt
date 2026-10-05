package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La aritmetica de la dilucion de sal.
 *
 * Como en el aforo por molinete, los casos de referencia tienen la respuesta sabida SIN este
 * codigo: un pulso triangular cuya area se calcula a mano, una calibracion fabricada con una
 * recta exacta, y la formula (3) de la guia de Merz y Doppmann, que es otra cuenta distinta
 * del mismo numero.
 */
class SaltDilutionTest {

    private val T0 = 1_700_000_000_000L
    private fun r(seg: Double, ec: Double) = ConductivityReading(T0 + (seg * 1000).toLong(), ec)

    /** Base 20, sube linealmente a 100 a los 50 s y vuelve a 20 a los 100 s, cada 5 s. */
    private fun triangulo(paso: Int = 5): List<ConductivityReading> =
        (0..140 step paso).map { s ->
            val exceso = when {
                s <= 20 -> 0.0
                s <= 70 -> (s - 20) * 80.0 / 50.0
                s <= 120 -> (120 - s) * 80.0 / 50.0
                else -> 0.0
            }
            r(s.toDouble(), 20.0 + exceso)
        }

    @Test fun `el area de un pulso triangular es base por altura medios`() {
        // Pico de 80 uS/cm sobre la base durante 100 s: 80 * 100 / 2 = 4000 (uS/cm)*s.
        // Los vertices caen en muestras, asi que los trapecios son exactos.
        assertEquals(4000.0, SaltDilutionMath.sigma(triangulo(), 20.0), 1e-9)
    }

    @Test fun `el caudal sale de la ecuacion de la guia`() {
        // M = 1000 g, Cal = 0,5 (mg/L)/(uS/cm), Sigma = 4000 (uS/cm)*s.
        // Q = 1000 g * 1000 mg/g / (0,5 * 4000) = 500 L/s = 0,5 m3/s.
        val q = SaltDilutionMath.dischargeM3s(1000.0, 0.5, 4000.0)
        assertNotNull(q)
        assertEquals(0.5, q, 1e-12)
    }

    @Test fun `con intervalo constante coincide con la formula 3 de Merz`() {
        // (3): Q = S / (Cal * (sum C(t) - N*C0) * T), con S en mg y Q en L/s. Es la suma por
        // rectangulos; con el exceso a cero en las dos puntas, igual a los trapecios.
        val l = triangulo()
        val sumaC = l.sumOf { it.microSiemensPerCm }
        val merzLs = 1000.0 * 1000.0 / (0.5 * (sumaC - l.size * 20.0) * 5.0)
        val q = SaltDilutionMath.dischargeM3s(1000.0, 0.5, SaltDilutionMath.sigma(l, 20.0))!!
        assertEquals(merzLs / 1000.0, q, 1e-12)
    }

    @Test fun `un muestreo irregular no cambia la integral`() {
        // Las mismas rectas leidas a deshoras: lo que la cuenta tiene que respetar son las
        // horas reales, no un intervalo supuesto.
        val horas = listOf(0.0, 3.0, 20.0, 31.0, 44.5, 70.0, 77.0, 99.0, 120.0, 133.0)
        val l = horas.map { s ->
            val e = when {
                s <= 20 -> 0.0; s <= 70 -> (s - 20) * 1.6; s <= 120 -> (120 - s) * 1.6; else -> 0.0
            }
            r(s, 20.0 + e)
        }
        assertEquals(4000.0, SaltDilutionMath.sigma(l, 20.0), 1e-9)
    }

    @Test fun `el orden de llegada no importa`() {
        assertEquals(4000.0, SaltDilutionMath.sigma(triangulo().reversed(), 20.0), 1e-9)
    }

    @Test fun `lo que queda por debajo de la base cuenta como cero`() {
        // Ruido por debajo de la base antes y despues de la nube: si contara negativo, se
        // comeria parte de la sal y el caudal saldria mayor de lo que es.
        val l = triangulo().map { if (it.microSiemensPerCm == 20.0) it.copy(microSiemensPerCm = 17.0) else it }
        assertEquals(4000.0, SaltDilutionMath.sigma(l, 20.0), 1e-9)
    }

    @Test fun `la ventana deja fuera lo que no es la nube`() {
        // Una deriva de +2 uS/cm muy despues del paso de la sal. Sin ventana entra en Sigma.
        val deriva = (150..3600 step 10).map { r(it.toDouble(), 22.0) }
        val todo = triangulo() + deriva
        val s = SaltDilution(readings = todo, baseConductivity = 20.0, saltMassG = 1000.0,
                             calibrationFactor = 0.5)
        assertTrue(SaltDilutionMath.compute(s).sigma > 4000.0 + 6000.0)
        val acotada = s.copy(windowStartMillis = T0, windowEndMillis = T0 + 140_000)
        val res = SaltDilutionMath.compute(acotada)
        assertEquals(4000.0, res.sigma, 1e-9)
        assertEquals(0.5, res.dischargeM3s!!, 1e-12)
        assertEquals(80.0, res.peakExcess!!, 1e-9)
        assertEquals(T0 + 70_000, res.peakAtMillis)
    }

    @Test fun `sin base o sin masa no hay caudal y se dice que falta`() {
        val s = SaltDilution(readings = triangulo())
        assertNull(SaltDilutionMath.compute(s).dischargeM3s)
        val falta = SaltDilutionMath.missing(s)
        assertTrue(falta.any { "mass" in it })
        assertTrue(falta.any { "calibration" in it })
        assertTrue(falta.any { "base" in it })
        assertTrue(SaltDilutionMath.missing(s.copy(saltMassG = 1.0, calibrationFactor = 0.5,
                                                   baseConductivity = 20.0)).isEmpty())
    }

    // ------------------------------------ calibracion ------------------------------------

    @Test fun `la tabla por defecto es la de la guia con el volumen que crece`() {
        val filas = SaltDilutionMath.calibrationRows(SaltCalibration())
        assertEquals(11, filas.size, "diez adiciones mas el agua sola")
        assertEquals((500..510).map { it.toDouble() }, filas.map { it.volumeMl })
        assertEquals(0.0, filas[0].concentrationGL, 0.0)
        // 1 mL de 3 g/L en 501 mL: 3 mg / 0,501 L. La guia redondea a 6 mg/L.
        assertEquals(0.003 / 0.501, filas[1].concentrationGL, 1e-15)
        assertEquals(0.030 / 0.510, filas[10].concentrationGL, 1e-15)
        // La aproximacion de la guia (6 mg/L por mL) se queda un 2 % alta al final.
        assertEquals(0.060, filas[10].concentrationGL * 1.0 / (500.0 / 510.0), 1e-12)
    }

    @Test fun `una recta exacta da el factor de la guia`() {
        // Conductividad = 30 + 2000 * c (c en g/L): 2 uS/cm por mg/L, que es Cal = 0,5.
        val base = SaltCalibration()
        val ec = SaltDilutionMath.calibrationRows(base).map { 30.0 + 2000.0 * it.concentrationGL }
        val ajuste = SaltDilutionMath.fit(SaltDilutionMath.calibrationRows(base.copy(conductivities = ec)))
        assertNotNull(ajuste)
        assertEquals(2000.0, ajuste.slope, 1e-9)
        assertEquals(30.0, ajuste.intercept, 1e-9)
        assertEquals(1.0, ajuste.r2!!, 1e-12)
        assertEquals(11, ajuste.n)
        assertEquals(0.5, ajuste.calibrationFactor!!, 1e-12)
    }

    @Test fun `el ajuste usa solo las filas medidas`() {
        val base = SaltCalibration()
        val ec = SaltDilutionMath.calibrationRows(base).map { 30.0 + 2000.0 * it.concentrationGL }
        val aMedias: List<Double?> = ec.mapIndexed { i, v -> if (i % 3 == 0) v else null }
        val ajuste = SaltDilutionMath.fit(SaltDilutionMath.calibrationRows(base.copy(conductivities = aMedias)))!!
        assertEquals(4, ajuste.n)
        assertEquals(0.5, ajuste.calibrationFactor!!, 1e-12)
    }

    @Test fun `con menos de dos puntos no hay recta`() {
        val c = SaltCalibration(conductivities = listOf(30.0))
        assertNull(SaltDilutionMath.fit(SaltDilutionMath.calibrationRows(c)))
    }

    @Test fun `una pendiente que no sube no da factor`() {
        val c = SaltCalibration(points = 2, conductivities = listOf(30.0, 29.0, 28.0))
        val f = SaltDilutionMath.fit(SaltDilutionMath.calibrationRows(c))!!
        assertNull(f.calibrationFactor)
    }

    @Test fun `una lectura a la misma hora reemplaza a la anterior`() {
        val l = SaltDilutionMath.addReading(listOf(r(0.0, 20.0), r(10.0, 30.0)), r(10.0, 35.0))
        assertEquals(listOf(20.0, 35.0), l.map { it.microSiemensPerCm })
        val m = SaltDilutionMath.addReading(l, r(5.0, 25.0))
        assertEquals(listOf(0L, 5000L, 10000L), m.map { it.atEpochMillis - T0 })
    }

    // ---------------------------------- medicion nueva ----------------------------------

    @Test fun `una medicion nueva hereda el sitio y nada de lo medido`() {
        val pos = FieldPosition(-53.1, -70.9, 10.0)
        val iny = FieldPosition(-53.2, -70.8)
        val src = FieldEntry(
            id = "a", type = EntryType.GAUGING, createdEpochMillis = 1L, person = "X",
            position = pos, profileName = "P1", photos = listOf("f.jpg"),
            gauging = StreamGauging(
                widthM = 2.0, intervalM = 0.5, depthFromBed = true,
                bins = List(4) { GaugingBin(depthM = 0.3, velocityMps = 1.0) },
                comments = "c", method = GaugingMethod.SALT_DILUTION, locked = true,
                salt = SaltDilution(
                    saltMassG = 500.0, injectionPosition = iny, injectionEpochMillis = 2L,
                    injectionDistanceM = 60.0, injectionNotes = "rock",
                    calibrationFactor = 0.52,
                    calibration = SaltCalibration(waterVolumeMl = 250.0, conductivities = listOf(30.0, 40.0)),
                    readings = triangulo(), baseConductivity = 20.0, calculated = true)))
        val base = FieldEntry(id = "b", type = EntryType.GAUGING, createdEpochMillis = 99L, person = "Y")
        val n = Gauging.newMeasurementFrom(src, base)
        val g = n.gauging!!
        assertEquals("b", n.id); assertEquals("Y", n.person); assertEquals(99L, n.createdEpochMillis)
        assertEquals("P1", n.profileName)
        assertEquals(pos, n.position)
        assertEquals(2.0, g.widthM); assertEquals(0.5, g.intervalM); assertTrue(g.depthFromBed)
        assertEquals(4, g.bins.size); assertTrue(g.bins.all { it.isEmpty })
        assertEquals("", g.comments); assertTrue(n.photos.isEmpty())
        assertEquals(false, g.locked)
        val s = g.salt!!
        assertEquals(iny, s.injectionPosition)
        assertEquals(0.52, s.calibrationFactor)
        assertEquals(60.0, s.injectionDistanceM)
        assertEquals(250.0, s.calibration!!.waterVolumeMl)
        assertTrue(s.calibration!!.conductivities.isEmpty())
        assertNull(s.saltMassG); assertNull(s.baseConductivity)
        assertTrue(s.readings.isEmpty()); assertEquals("", s.injectionNotes)
        assertEquals(99L, s.injectionEpochMillis)
        assertEquals(false, s.calculated)
    }
}
