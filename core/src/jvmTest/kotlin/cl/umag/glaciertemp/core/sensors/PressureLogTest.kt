package cl.umag.glaciertemp.core.sensors

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AHORA = 1790262000000L
private const val HORA = 3_600_000L

private fun m(horasAtras: Double, hPa: Double) =
    PressureSample((AHORA - (horasAtras * HORA).toLong()), hPa)

class PressureTrendTest {

    @Test fun `con una sola lectura no hay tendencia`() {
        assertNull(PressureTrend.change(listOf(m(0.0, 1000.0)), 3.0, AHORA))
        assertNull(PressureTrend.describe(listOf(m(0.0, 1000.0)), AHORA))
    }

    @Test fun `el cambio se mide contra la mas antigua dentro de la ventana`() {
        val s = listOf(m(6.0, 1010.0), m(3.0, 1006.0), m(1.0, 1004.0), m(0.0, 1003.0))
        // En tres horas: de 1006 a 1003.
        assertEquals(-3.0, PressureTrend.change(s, 3.0, AHORA)!!, 1e-9)
        // En seis: de 1010 a 1003.
        assertEquals(-7.0, PressureTrend.change(s, 6.0, AHORA)!!, 1e-9)
    }

    @Test fun `con lecturas espaciadas no se calla, usa la ultima que haya`() {
        // Registro tomado a mano: dos lecturas separadas dos dias. Pedir tres horas no puede
        // devolver null, o la tendencia no saldria nunca en un uso real.
        val s = listOf(m(48.0, 1015.0), m(0.0, 1002.0))
        assertEquals(-13.0, PressureTrend.change(s, 3.0, AHORA)!!, 1e-9)
    }

    @Test fun `dice cuanto tiempo cubre el cambio`() {
        // "Bajando 4" no es lo mismo en tres horas que en tres dias.
        val d = PressureTrend.describe(listOf(m(3.0, 1006.0), m(0.0, 1002.0)), AHORA)
        assertNotNull(d)
        assertTrue(d.contains("Falling"), d)
        assertTrue(d.contains("4.0"), d)
        assertTrue(d.contains("3.0 h"), d)
    }

    @Test fun `un cambio despreciable se llama estable`() {
        val d = PressureTrend.describe(listOf(m(2.0, 1002.1), m(0.0, 1002.0)), AHORA)
        assertNotNull(d)
        assertTrue(d.startsWith("Steady"), d)
    }

    @Test fun `una caida marcada en tres horas se avisa`() {
        val w = PressureTrend.warning(listOf(m(3.0, 1008.0), m(0.0, 1003.0)), AHORA)
        assertNotNull(w)
        assertTrue(w.contains("front"), w)
    }

    @Test fun `una subida marcada tambien se nombra`() {
        val w = PressureTrend.warning(listOf(m(3.0, 1000.0), m(0.0, 1005.0)), AHORA)
        assertNotNull(w)
        assertTrue(w.contains("Rising"), w)
    }

    @Test fun `un cambio normal no dispara aviso`() {
        assertNull(PressureTrend.warning(listOf(m(3.0, 1002.0), m(0.0, 1001.0)), AHORA))
    }
}

class PressureStoreTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "presion-" + System.nanoTime())
    private val store = PressureStore(dir)

    @AfterTest fun limpia() { dir.deleteRecursively() }

    @Test fun `un lugar guarda sus lecturas en orden`() {
        val id = store.create("Campamento")
        store.append(id, PressureSample(AHORA - 2 * HORA, 1004.5))
        store.append(id, PressureSample(AHORA, 1002.25))
        val p = store.load(id)!!
        assertEquals("Campamento", p.name)
        assertEquals(2, p.samples.size)
        assertEquals(1004.5, p.samples.first().hPa, 1e-6)
        assertEquals(1002.25, p.samples.last().hPa, 1e-6)
    }

    @Test fun `la posicion viaja con la lectura`() {
        // Sin ella no se puede avisar de que la muestra se tomo cien metros mas arriba.
        val id = store.create("Base")
        store.append(id, PressureSample(AHORA, 1000.0, -51.5, -73.25, 412.0))
        val s = store.load(id)!!.samples.single()
        assertEquals(-51.5, s.latitude!!, 1e-6)
        assertEquals(-73.25, s.longitude!!, 1e-6)
        assertEquals(412.0, s.altitudeMetres!!, 1e-3)
    }

    @Test fun `una lectura sin posicion se guarda igual`() {
        val id = store.create("Sin GPS")
        store.append(id, PressureSample(AHORA, 998.0))
        val s = store.load(id)!!.samples.single()
        assertNull(s.latitude)
        assertEquals(998.0, s.hPa, 1e-6)
    }

    @Test fun `renombrar no pierde las lecturas`() {
        val id = store.create("Viejo")
        repeat(3) { store.append(id, PressureSample(AHORA + it * HORA, 1000.0 + it)) }
        assertTrue(store.rename(id, "Campamento base"))
        val p = store.load(id)!!
        assertEquals("Campamento base", p.name)
        assertEquals(3, p.samples.size)
    }

    @Test fun `los lugares se listan por lectura mas reciente`() {
        val viejo = store.create("Viejo")
        val nuevo = store.create("Nuevo")
        store.append(viejo, PressureSample(AHORA - 10 * HORA, 1000.0))
        store.append(nuevo, PressureSample(AHORA, 1000.0))
        assertEquals(listOf("Nuevo", "Viejo"), store.list().map { it.name })
    }

    @Test fun `un fichero ilegible se salta y no tumba la lista`() {
        val id = store.create("Bueno")
        store.append(id, PressureSample(AHORA, 1000.0))
        File(dir, "roto${PressureStore.EXTENSION}").writeText("basura sin separador")
        assertEquals(listOf("Bueno"), store.list().map { it.name })
    }

    @Test fun `borrar un lugar se lo lleva entero`() {
        val id = store.create("X")
        store.delete(id)
        assertNull(store.load(id))
    }
}

class GreatCircleTest {

    @Test fun `la misma posicion da cero`() {
        assertEquals(0.0, GreatCircle.metres(-51.0, -73.0, -51.0, -73.0), 1e-6)
    }

    @Test fun `un grado de latitud son unos 111 kilometros`() {
        assertEquals(111_195.0, GreatCircle.metres(0.0, 0.0, 1.0, 0.0), 500.0)
    }

    @Test fun `cien metros se miden como cien metros`() {
        // Es la escala que importa: distinguir "estoy en el mismo sitio" de "subi la cuesta".
        val d = GreatCircle.metres(-51.0, -73.0, -51.0 + 0.0008993, -73.0)
        assertEquals(100.0, d, 2.0)
    }
}

class PressureReportTest {

    private fun lugar() = PressurePlace("p1", "Campamento", listOf(
        PressureSample(AHORA - 3 * HORA, 1008.25, -51.5, -73.25, 412.0),
        PressureSample(AHORA, 1003.5)))

    @Test fun `se copia como tabla, una fila por lectura`() {
        val t = PressureReport.clipboardText(lugar(), java.time.ZoneId.of("UTC"))
        assertTrue(t.contains("timestamp\thPa"), t)
        assertEquals(2, t.lines().count { it.contains("\t") && !it.startsWith("timestamp") },
                     "una fila por lectura: $t")
        assertTrue(t.contains("1008.25"), t)
        assertTrue(t.contains("1003.50"), t)
    }

    @Test fun `lleva el nombre del lugar y la tendencia`() {
        val t = PressureReport.clipboardText(lugar(), java.time.ZoneId.of("UTC"))
        assertTrue(t.contains("Campamento"), t)
        assertTrue(t.contains("Falling"), t)
    }

    @Test fun `una lectura sin posicion deja las columnas vacias, no ceros`() {
        // Un cero en latitud es una coordenada en el golfo de Guinea, no "no se sabe".
        val t = PressureReport.clipboardText(lugar(), java.time.ZoneId.of("UTC"))
        val ultima = t.lines().first { it.contains("1003.50") }
        assertTrue(ultima.endsWith("\t\t\t"), "las columnas vacias van vacias: '$ultima'")
    }

    @Test fun `se dice que solo el cambio significa algo`() {
        assertTrue(PressureReport.clipboardText(lugar()).contains("same spot"))
    }
}
