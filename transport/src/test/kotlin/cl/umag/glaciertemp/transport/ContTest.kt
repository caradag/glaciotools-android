package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.ContCapture
import cl.umag.glaciertemp.core.LiveSample
import cl.umag.glaciertemp.core.LogDecoder
import cl.umag.glaciertemp.core.LogFormat
import cl.umag.glaciertemp.core.Protocol
import java.io.File
import kotlin.test.*

/**
 * La captura continua contra el simulador, de punta a punta: arrancar, recibir estado,
 * parar con su resumen, y descargar por LOGB un log de version 2 cuyos registros llevan
 * milisegundos crecientes. Mas lo que la placa exige: el log vacio, y que fuera de una
 * captura CONT? conteste "idle".
 */
class ContTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    private fun <T> withSimulator(vararg extra: String, body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + extra).start()
        try {
            return PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open(); body(t)
            }
        } finally { proc.destroyForcibly() }
    }

    @Test
    fun `captura, resumen y descarga con milisegundos`() =
        withSimulator("--log-size", "0", "--cont-period", "0.05", "--cont-status", "0.3") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            assertTrue(ContCapture.isIdle(String(s.exchange(ContCapture.QUERY))))

            val begin = ContCapture.parseBegin(s.contStart(heater = true))
            assertNotNull(begin)
            assertTrue(begin.heater)

            val estados = java.util.Collections.synchronizedList(ArrayList<ContCapture.Status>())
            val muestras = java.util.Collections.synchronizedList(ArrayList<LiveSample>())
            val parar = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread { Thread.sleep(1_200); parar.set(true) }.apply { isDaemon = true }.start()
            val fin = s.contMonitor(
                expectedValues = ContCapture.valuesPerRecord(begin.recordBytes),
                onStatus = { estados.add(it) }, onSample = { muestras.add(it) },
                isCancelled = { parar.get() })

            assertNotNull(fin, "la placa tiene que confirmar el fin con su resumen")
            assertEquals("stop", fin.reason)
            assertTrue(fin.heater)
            assertTrue(fin.records > 5, "registros: ${fin.records}")
            assertTrue(estados.isNotEmpty(), "llegaron lineas de estado")
            assertTrue(muestras.isNotEmpty(), "las lineas de estado traen su muestra")

            // La linea queda limpia: lo siguiente es INFO, con la firma de version 2.
            val info = assertNotNull(s.info())
            assertEquals(fin.records, info.recordCount)
            assertTrue(LogFormat.hasMillis(info.signature))
            assertEquals(LogFormat.recordBytes(info.signature), info.recordBytes)

            val recs = LogDecoder.decode(s.download(info), info.signature)
            assertEquals(fin.records.toInt(), recs.size)
            assertTrue(recs.zipWithNext().all { (a, b) -> b.time > a.time },
                       "con milisegundos, ninguna marca se repite")
            assertTrue(recs.any { it.time.nano != 0 }, "las marcas llevan fraccion de segundo")
        }

    @Test
    fun `con datos en el log la placa no arranca`() =
        withSimulator("--log-size", "5") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val r = s.contStart(heater = false)
            assertTrue(ContCapture.needsEmptyLog(r), r)
            assertNull(ContCapture.parseBegin(r))
        }

    @Test
    fun `el ida y vuelta se mide con CONT`() =
        withSimulator("--log-size", "0") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val rt = assertNotNull(s.roundTripMs(ContCapture.QUERY, samples = 2))
            assertTrue(rt in 0..1_000, "ida y vuelta: $rt ms")
        }

    /**
     * Lo que hace la app al reconectar despues de que el Bluetooth se cayera en vuelo: la
     * placa sigue capturando, INFO no contesta, CONT? si, y desde ahi se puede parar.
     */
    @Test
    fun `reconectar a una placa que ya captura y pararla`() =
        withSimulator("--log-size", "0", "--cont-period", "0.05", "--cont-status", "0.3") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            // Otro telefono, o esta misma app antes de perder el enlace, la arranco.
            t.writeLine(ContCapture.ON)
            Thread.sleep(500)

            assertNull(s.info(), "en captura la placa no atiende INFO")
            val q = String(s.exchange(ContCapture.QUERY, quietMs = 800))
            assertNotNull(ContCapture.parseStatus(q), "CONT? delata la captura: $q")

            val parar = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread { Thread.sleep(600); parar.set(true) }.apply { isDaemon = true }.start()
            val fin = s.contMonitor(expectedValues = null, onStatus = {}, onSample = {},
                                    isCancelled = { parar.get() })
            assertEquals("stop", assertNotNull(fin).reason)
            // Parada, vuelve a ser una placa normal.
            assertEquals(fin.records, assertNotNull(s.info()).recordCount)
        }
}
