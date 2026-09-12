package cl.umag.glaciertemp.transport

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/**
 * La escucha continua del enlace, y el riesgo que trae.
 *
 * Existe para que el terminal muestre lo que la placa dice POR SU CUENTA -- pulsar RESET
 * produce todo el bloque de arranque, y esa salida se quedaba en el enlace hasta que alguien
 * escribia un comando, apareciendo entonces delante de su respuesta.
 *
 * El riesgo es que dos lectores sobre el mismo enlace se roben los bytes: el de reposo se
 * queda con el principio de una respuesta y la operacion la da por perdida. Eso es lo que se
 * prueba aqui, porque es un fallo que en terreno aparece una vez de cada tantas y se achaca
 * al cable.
 */
class IdleListenerTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    private fun <T> withSimulator(vararg extraArgs: String, body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + extraArgs
        ).start()
        try {
            return PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open(); body(t)
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `la escucha de reposo recoge lo que la placa dice sin preguntarle`() =
        withSimulator("--log-size", "20") { t ->
            val s = DeviceSession(t)
            // El banner del arranque no se pide: lo emite la placa al conectar. Es la misma
            // situacion que pulsar RESET.
            val visto = StringBuilder()
            repeat(40) {
                visto.append(String(s.leerEnReposo(100), Charsets.ISO_8859_1))
                if (visto.contains("Waiting commands")) return@repeat
            }
            assertTrue(visto.contains("GlacierTemp"),
                       "la escucha de reposo no recogio el banner: <$visto>")
        }

    @Test
    fun `la escucha de reposo no le roba bytes a una operacion`() =
        withSimulator("--log-size", "137") { t ->
            val s = DeviceSession(t)
            s.drainBanner()

            // Un lector de reposo insistiendo en paralelo, como el bucle de la app.
            val parar = AtomicBoolean(false)
            val robados = StringBuilder()
            val hilo = Thread {
                while (!parar.get()) {
                    val d = s.leerEnReposo(50)
                    if (d.isNotEmpty()) robados.append(String(d, Charsets.ISO_8859_1))
                }
            }
            hilo.start()
            try {
                // Y mientras, las operaciones de siempre. Si el lector de reposo pudiera
                // colarse, estas verian respuestas truncadas o vacias.
                repeat(6) {
                    val info = assertNotNull(s.info(), "INFO salio vacio o truncado")
                    assertEquals(137L, info.recordCount)
                    val ver = String(s.exchange(cl.umag.glaciertemp.core.Protocol.VERSION))
                    assertTrue(ver.contains("proto="), "VER truncado: <$ver>")
                }
                // Y una descarga, que lee la linea directamente y no solo por exchange.
                val info = assertNotNull(s.info())
                val payload = s.download(info)
                assertEquals(info.recordCount * info.recordBytes, payload.size.toLong(),
                             "la descarga perdio bytes: se los llevo el lector de reposo")
            } finally {
                parar.set(true); hilo.join(2000)
            }
        }

    @Test
    fun `en reposo con el enlace callado no devuelve nada`() =
        withSimulator("--log-size", "20") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            assertEquals(0, s.leerEnReposo(100).size,
                         "devolvio datos sin que la placa dijera nada")
        }
}
