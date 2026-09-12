package cl.umag.glaciertemp.transport

import java.io.File
import kotlin.test.*

/**
 * Abortar una descarga, y que no quede cola detras.
 *
 * Los dos fallos que cubre se veian solo con la placa delante. El de abortar, porque por
 * CABLE `recordsPerRequest` vale 0: el bucle de troceo --el unico sitio donde se miraba la
 * bandera-- se salta entero y toda la descarga ocurre dentro de una sola llamada. Contra el
 * simulador por pipe pasa exactamente lo mismo, asi que aqui si se reproduce.
 */
class DownloadAbortTest {

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
    fun `abortar corta una descarga sin troceo`() =
        withSimulator("--log-size", "20000") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.requireInfo())
            assertEquals(0, t.recordsPerRequest,
                         "este transporte no trocea: es el caso en que fallaba el aborto")

            // Se pide el corte en cuanto empiezan a llegar datos, desde OTRO hilo, como
            // hace el boton.
            val hilo = Thread { Thread.sleep(50); s.cancelled = true }
            hilo.start()

            val e = assertFailsWith<DownloadCancelled> { s.download(info) }
            hilo.join()
            assertTrue(e.recordsDone < info.recordCount,
                       "se aborto pero dice haber bajado el log entero")
        }

    /**
     * Un transporte con cola YA pendiente cuando se manda el comando, que es la situacion
     * que deja un volcado: el bucle termina al llegar el ultimo bloque y el "LOGB end" se
     * queda en el enlace.
     *
     * Se prueba asi y no contra el simulador porque alli la carrera no es reproducible: por
     * un pipe el cierre suele llegar a tiempo de leerse con el ultimo bloque, y el test
     * pasaria por azar tanto con el arreglo como sin el.
     */
    private class ConCola(cola: String, private val respuesta: String) : Transport {
        private var pendiente = cola.toByteArray().toMutableList()
        private var respondiendo = false
        override var isOpen = true
        override fun open() {}
        override fun write(data: ByteArray) { respondiendo = true }
        override fun read(timeoutMs: Int): ByteArray {
            if (pendiente.isNotEmpty()) {
                val d = pendiente.toByteArray(); pendiente.clear(); return d
            }
            if (respondiendo) {
                respondiendo = false
                return respuesta.toByteArray()
            }
            return ByteArray(0)
        }
        override fun close() { isOpen = false }
    }

    @Test
    fun `un comando no lee como suya la cola de la operacion anterior`() {
        val t = ConCola(cola = "LOGB end\n", respuesta = "fw=3.0 proto=4\n")
        val s = DeviceSession(t)
        val reply = String(s.exchange("VER", quietMs = 200))

        assertFalse(reply.contains("LOGB"),
                    "la respuesta de VER trae la cola del volcado: <$reply>")
        assertTrue(reply.contains("proto=4"), "respuesta inesperada: <$reply>")
    }

    @Test
    fun `una cola que falsea un valor no llega a leerse`() {
        // El caso que hace dano de verdad: si la cola contiene algo con la forma de un valor,
        // el parser se queda con lo primero que encuentra y devuelve el numero equivocado
        // sin que nada avise.
        val t = ConCola(cola = "Time Zone (hours): 99\n",
                        respuesta = "Time Zone (hours): -3\n")
        val s = DeviceSession(t)
        val tz = assertNotNull(cl.umag.glaciertemp.core.Variables.byCode("TZN"))
        val valor = cl.umag.glaciertemp.core.VariableSpec.parseValue(s.readVariable(tz))
        assertEquals("-3", valor, "se leyo el valor que habia quedado en la cola")
    }

}
