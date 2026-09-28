package cl.umag.glaciertemp.core.geo

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val T0 = 1790262000000L

/**
 * El tramo de cada muestra: no es una etiqueta, es lo que le dice al estimador cuantas
 * medidas INDEPENDIENTES hay. Contarlas mal no solo escribe mal el resumen, hace que el
 * error declarado salga demasiado bueno.
 */
class SessionStampTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "tramos-" + System.nanoTime())
    private val store = GpsPointStore(dir)

    @AfterTest fun limpia() { dir.deleteRecursively() }

    private fun cruda(i: Int) = GpsSample(T0 + i * 1000L, -51.0 + i * 1e-6, -73.0)

    @Test fun `add devuelve la muestra ya sellada`() {
        val a = GpsAverager()
        a.startSession(T0)
        val sellada = a.add(cruda(0))
        assertEquals(T0, sellada.sessionStartMillis,
                     "sin esto, quien la guarda escribe una muestra sin tramo")
    }

    @Test fun `una sesion continua cuenta como UNA`() {
        val a = GpsAverager()
        a.startSession(T0)
        repeat(50) { a.add(cruda(it)) }
        assertEquals(1, a.stats()!!.sessions)
        assertEquals(50, a.stats()!!.samples)
    }

    @Test fun `guardar las selladas y recargar conserva UNA sesion`() {
        // EL FALLO REAL: se guardaba la cruda, con tramo 0. Al recargar, el promediador no
        // podia distinguir "tramo 0" de "sin tramo" y le daba a cada muestra el suyo, asi
        // que 487 fijaciones salian como 487 sesiones.
        val a = GpsAverager()
        a.startSession(T0)
        val selladas = (0 until 40).map { a.add(cruda(it)) }
        val id = store.create("P")
        store.append(id, selladas)

        val recargado = GpsAverager().apply { addAll(store.load(id)!!.samples) }
        assertEquals(1, recargado.stats()!!.sessions,
                     "una visita continua tiene que seguir siendo una sesion")
        assertEquals(40, recargado.stats()!!.samples)
    }

    @Test fun `dos visitas separadas siguen contando como dos`() {
        val a = GpsAverager()
        a.startSession(T0)
        repeat(20) { a.add(cruda(it)) }
        // Otra visita una hora despues.
        a.startSession(T0 + 3_600_000L)
        repeat(20) { a.add(GpsSample(T0 + 3_600_000L + it * 1000L, -51.0, -73.0)) }
        assertEquals(2, a.stats()!!.sessions)
    }

    @Test fun `un fichero antiguo, con todo a tramo cero, se repara al leerlo`() {
        // Los puntos ya medidos llevan la marca de tramo 0 para todo. Tomarla por buena
        // dejaria el mismo fallo; se cae a la inferencia por huecos, que para una visita
        // continua da una sesion.
        val id = store.create("Viejo")
        store.append(id, (0 until 30).map { cruda(it) })   // crudas: tramo 0
        val recargado = GpsAverager().apply { addAll(store.load(id)!!.samples) }
        assertEquals(1, recargado.stats()!!.sessions)
    }

    @Test fun `contar mal los tramos hace que el error declarado salga demasiado bueno`() {
        // La razon por la que esto no era un fallo cosmetico.
        val unaSesion = GpsAverager().apply {
            startSession(T0); repeat(60) { add(cruda(it)) }
        }.stats()!!
        val cadaUnaSuya = GpsAverager().apply {
            repeat(60) { add(cruda(it)) }      // sin abrir tramo: cada una el suyo
        }.stats()!!
        assertEquals(1, unaSesion.sessions)
        assertEquals(60, cadaUnaSuya.sessions)
        assertTrue(cadaUnaSuya.horizontalStandardError < unaSesion.horizontalStandardError,
                   "con tramos inflados el error sale menor: " +
                   "${cadaUnaSuya.horizontalStandardError} contra " +
                   "${unaSesion.horizontalStandardError}")
    }
}

class ClipboardTextTest {

    private fun stats(): GpsPointStats {
        val a = GpsAverager()
        a.startSession(T0)
        repeat(12) { a.add(GpsSample(T0 + it * 1000L, -53.16, -70.91, altitudeMetres = 412.0,
                                     accuracyMetres = 5.0)) }
        return a.stats()!!
    }

    @Test fun `lleva las coordenadas geograficas`() {
        val t = GpsExport.clipboardText(stats(), "P1")
        assertTrue(t.contains("-53.16"), t)
        assertTrue(t.contains("-70.91"), t)
    }

    @Test fun `lleva tambien la UTM, la altitud y la incertidumbre`() {
        val t = GpsExport.clipboardText(stats(), "P1")
        assertTrue(t.contains("412.0 m"), t)
        assertTrue(t.contains("± "), t)
        assertTrue(Regex("\\d+[A-Z] \\d+ \\d+").containsMatchIn(t), "falta la UTM: $t")
    }

    @Test fun `el plural de sesion se escribe bien`() {
        // EL SINTOMA: decia "12 fixes in 12 session(s)".
        val t = GpsExport.clipboardText(stats(), "P1")
        assertTrue(t.contains("12 fixes in 1 session"), t)
        assertTrue(!t.contains("session(s)"), "nada de parentesis: $t")
    }

    @Test fun `una sola fijacion se escribe en singular`() {
        val a = GpsAverager()
        a.startSession(T0)
        a.add(GpsSample(T0, -53.0, -70.0, accuracyMetres = 5.0))
        val t = GpsExport.clipboardText(a.stats()!!, "")
        assertTrue(t.contains("1 fix in 1 session"), t)
    }

    @Test fun `sin nombre se pone uno generico`() {
        assertTrue(GpsExport.clipboardText(stats(), "  ").contains("GNSS point"))
    }
}
