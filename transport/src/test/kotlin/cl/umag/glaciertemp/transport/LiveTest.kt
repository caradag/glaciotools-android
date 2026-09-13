package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.LiveSample
import java.io.File
import kotlin.test.*

/**
 * El modo en directo contra el simulador.
 *
 * Lo que se fija aqui no es que lleguen numeros --eso se ve a simple vista-- sino las tres
 * cosas que se rompen sin hacer ruido: que parar deja la linea LIMPIA para el comando
 * siguiente, que una muestra incompleta se tira en vez de ensenarse corrida, y que se
 * distingue haber parado de que a la placa se le acabara el plazo.
 */
class LiveTest {

    /**
     * Una placa de guion que NO dice nada hasta que se le manda el comando.
     *
     * Es la diferencia entre un test que prueba algo y uno que se cuelga: el enlace lo lee
     * un hilo propio que arranca con la sesion, asi que un guion que se emite solo se lo
     * traga entero ANTES de que `live` llegue a pedirlo -- y `live` empieza descartando lo
     * pendiente, que es justo lo que tiene que hacer.
     */
    private class Guionizada(private val guion: List<String>) : Transport {
        private val cola = ArrayDeque<String>()
        override var isOpen = true
        override fun open() {}
        override fun close() { isOpen = false }

        @Synchronized
        override fun write(data: ByteArray) {
            if (String(data).trim().startsWith("LIVE", ignoreCase = true)) cola.addAll(guion)
        }

        @Synchronized
        private fun siguiente(): String? = cola.removeFirstOrNull()

        override fun read(timeoutMs: Int): ByteArray {
            val l = siguiente() ?: run {
                Thread.sleep(minOf(timeoutMs, 10).toLong())
                return ByteArray(0)
            }
            return l.toByteArray()
        }
    }

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
    fun `llegan muestras y parar deja la linea limpia`() = withSimulator("--log-size", "20") { t ->
        val s = DeviceSession(t)
        Thread.sleep(300)
        val info = DeviceInfo.parse(String(s.exchange("INFO", quietMs = 600)))!!
        val campos = cl.umag.glaciertemp.core.LogFormat.fields(info.signature).size

        val muestras = java.util.Collections.synchronizedList(ArrayList<LiveSample>())
        val parar = java.util.concurrent.atomic.AtomicBoolean(false)
        // Se pide parar desde FUERA, como hace el boton: el bucle lo consulta entre muestras.
        Thread { Thread.sleep(1200); parar.set(true) }.apply { isDaemon = true }.start()

        val fin = s.live(expectedValues = campos, periodMs = 200,
                         onSample = { muestras.add(it) },
                         isCancelled = { parar.get() })

        assertEquals(LiveOutcome.PARADO, fin, "no reconocio que paro porque se lo pedimos")
        assertTrue(muestras.size >= 3, "solo llegaron ${muestras.size} muestras")
        muestras.forEach {
            assertEquals(campos, it.values.size, "una muestra trae otro numero de columnas")
            assertTrue(it.time.contains(":"), "la hora no parece una hora: ${it.time}")
        }

        // Lo que de verdad se rompe sin avisar: que la cola del directo se cuele en la
        // respuesta del comando siguiente. Es el mismo fallo que tuvo el volcado binario.
        val ver = String(s.exchange("VER", quietMs = 400)).trim()
        assertTrue(ver.startsWith("fw="), "la respuesta de VER trae cola del directo: <$ver>")
        assertFalse(ver.contains("LIVE"), "quedo texto del directo sin recoger: <$ver>")
    }

    @Test
    fun `los valores se mueven, que es para lo que sirve mirarlos`() =
        withSimulator("--log-size", "20") { t ->
            val s = DeviceSession(t)
            Thread.sleep(300)
            val info = DeviceInfo.parse(String(s.exchange("INFO", quietMs = 600)))!!
            val campos = cl.umag.glaciertemp.core.LogFormat.fields(info.signature).size
            val muestras = java.util.Collections.synchronizedList(ArrayList<LiveSample>())
            val parar = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread { Thread.sleep(1500); parar.set(true) }.apply { isDaemon = true }.start()

            s.live(expectedValues = campos, periodMs = 200,
                   onSample = { muestras.add(it) }, isCancelled = { parar.get() })

            // Un valor clavado en todas las muestras significa que se esta ensenando la
            // primera lectura una y otra vez, que es como se ve un bucle que no refresca.
            val primera = muestras.map { it.values.first() }.toSet()
            assertTrue(primera.size > 1,
                       "todas las muestras traen el mismo valor: $primera")
        }

    @Test
    fun `una muestra incompleta se descarta en vez de ensenarse corrida`() {
        // Sin simulador: se le da a la sesion exactamente la linea rota que produce una
        // radio que pierde bytes, que es donde esto pasa de verdad.
        val guion = listOf(
            "LIVE begin every 200 ms\n",
            "Time,Volt,Temp,RH\n",
            "LIVE 2026-09-13 10:00:01,4.12,18.50,52.0\n",
            "LIVE 2026-09-13 10:00:02,4.12,18.51\n",       // cortada
            "LIVE 2026-09-13 10:00:03,4.13,18.52,52.1\n",
            "LIVE end\n",
        )
        val t = Guionizada(guion)
        val s = DeviceSession(t)
        val muestras = ArrayList<LiveSample>()
        val notas = ArrayList<String>()
        val fin = s.live(expectedValues = 3, onSample = { muestras.add(it) },
                         onDiagnostic = { notas.add(it) })

        assertEquals(2, muestras.size, "no se descarto la muestra cortada: $muestras")
        assertEquals(listOf("4.12", "18.50", "52.0"), muestras[0].values)
        assertEquals(listOf("4.13", "18.52", "52.1"), muestras[1].values)
        assertTrue(notas.any { it.contains("incomplete") },
                   "descartar en silencio es peor que descartar: $notas")
        assertEquals(LiveOutcome.PARADO, fin, "LIVE end es haber parado")
    }

    @Test
    fun `si la placa no confirma que paro, no se espera el plazo entero`() {
        // Quien pulsa Parar espera que la pantalla responda. Quedarse aqui los quince
        // minutos del tope esperando un cierre que no llega dejaria la app ocupada sin
        // decir por que, que es indistinguible de un cuelgue.
        val t = Guionizada(listOf("LIVE begin every 1000 ms\n",
                                  "LIVE 2026-09-13 10:00:01,4.12\n"))   // y nunca cierra
        val s = DeviceSession(t)
        val notas = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        val fin = s.live(expectedValues = 1, onSample = {},
                         onDiagnostic = { notas.add(it) }, isCancelled = { true })
        val tardo = System.currentTimeMillis() - t0

        assertEquals(LiveOutcome.NO_CONFIRMADO, fin)
        assertTrue(tardo < 15_000, "tardo ${tardo} ms en rendirse")
        assertTrue(notas.any { it.contains("never confirmed") },
                   "se rindio en silencio: $notas")
    }

    @Test
    fun `se distingue parar de que se acabe el plazo`() {
        // Importa porque solo uno de los dos significa que hay que volver a pedirlo: si se
        // confunden, la pantalla se queda con numeros viejos que parecen de ahora.
        fun sesionQueTermina(cierre: String): LiveOutcome {
            val guion = listOf(
                "LIVE begin every 1000 ms\n",
                "LIVE 2026-09-13 10:00:01,4.12\n",
                "$cierre\n")
            return DeviceSession(Guionizada(guion)).live(expectedValues = 1, onSample = {})
        }
        assertEquals(LiveOutcome.PARADO, sesionQueTermina("LIVE end"),
                     "LIVE end es haber parado")
        assertEquals(LiveOutcome.PLAZO_DE_LA_PLACA, sesionQueTermina("LIVE timeout"),
                     "LIVE timeout es que se le acabo el plazo a la placa, no que paráramos")
    }
}
