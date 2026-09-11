package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.*
import java.io.File
import kotlin.test.*

/**
 * Descarga completa a traves de [BufferedTransport], que es la capa por la que pasara el
 * enlace BLE real.
 *
 * `SimulatorE2ETest` prueba el protocolo sobre un pipe con lectura bloqueante; esto prueba
 * la MISMA descarga cuando los datos llegan por callback en fragmentos, que es como los
 * entrega Android. El troceado de las escrituras, el vaciado del buffer en cada lectura y
 * el reensamblado de LOGB solo se ejercitan juntos aqui.
 *
 * Lo unico que sigue sin cubrirse es la radio: la API de Android y el modulo fisico.
 */
class BufferedSimulatorE2ETest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    /**
     * Transporte que imita a un puente BLE: escribe en trozos de 20 bytes y entrega lo que
     * llega por [onReceived] desde un hilo aparte, como hace `onCharacteristicChanged`.
     */
    /**
     * Como [FragmentedLink] pero ademas sabe cambiar de velocidad, y anota cada cambio junto
     * con cuantos bytes se habian entregado ya. Eso permite comprobar QUE se transmitio a
     * QUE velocidad, que es lo unico que importa del volcado rapido y lo que no se ve
     * mirando los datos.
     */
    /** Como el BLE real: pide el log por tramos en vez de todo de una vez. */
    private class ChunkedLink(
        input: java.io.InputStream, output: java.io.OutputStream, fragment: Int,
        private val perRequest: Int,
    ) : FragmentedLink(input, output, fragment) {
        override val recordsPerRequest: Int get() = perRequest
    }

    private class SwitchableLink(
        input: java.io.InputStream, output: java.io.OutputStream, fragment: Int,
    ) : FragmentedLink(input, output, fragment), BaudSwitchable {
        val bauds = mutableListOf<Int>()
        override fun setBaudRate(baud: Int) { bauds += baud }
    }

    private open class FragmentedLink(
        private val input: java.io.InputStream,
        private val output: java.io.OutputStream,
        private val fragment: Int,
    ) : BufferedTransport(writeChunkSize = 20) {

        private var reader: Thread? = null
        override var isOpen = false

        override fun open() {
            isOpen = true
            reader = Thread {
                val buf = ByteArray(fragment)
                try {
                    while (isOpen) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) onReceived(buf.copyOf(n))
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
        }

        override fun writeChunk(chunk: ByteArray) {
            output.write(chunk); output.flush()
        }

        override fun close() { isOpen = false; reader?.interrupt() }
    }

    private fun <T> withSwitchableSimulator(vararg extraArgs: String,
                                            body: (SwitchableLink) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + extraArgs
        ).start()
        try {
            return SwitchableLink(proc.inputStream, proc.outputStream, 64).use { t ->
                t.open(); body(t)
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    private fun <T> withSimulator(vararg extraArgs: String, body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        assertTrue(script.exists(), "no se encontro ${script.path}")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + extraArgs
        ).start()
        try {
            return FragmentedLink(proc.inputStream, proc.outputStream, 20).use { t ->
                t.open(); body(t)
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `descarga por LOGB en fragmentos de 20 bytes y coincide con LOGC`() =
        withSimulator("--log-size", "137", "--mtu", "20") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info(), "el simulador no respondio a INFO")

            var lastFraction = 0f
            val payload = s.download(info) { p ->
                assertTrue(p.fraction >= lastFraction, "el progreso retrocedio")
                lastFraction = p.fraction
            }
            assertEquals(info.recordCount * info.recordBytes, payload.size.toLong())

            val binary = CsvExporter
                .export(LogDecoder.decode(payload, info.signature), info.signature)
                .trimEnd('\n').lines()
            val csv = String(s.exchange(Protocol.LOG_CSV, quietMs = 1500))
                .lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && it.first().isDigit() }.toList()

            // La cabecera del CSV la pone el exportador; LOGC entrega solo las filas.
            val rows = binary.filter { it.isNotEmpty() && it.first().isDigit() }
            assertEquals(csv.size, rows.size, "distinto numero de registros")
            assertEquals(csv, rows, "el CSV binario no coincide con el de la placa")
        }

    @Test
    fun `un rango se descarga igual que el tramo correspondiente del log entero`() =
        withSimulator("--log-size", "200", "--mtu", "20") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            val whole = s.download(info)
            val part = s.download(info, from = 50, to = 99)
            assertEquals(50 * info.recordBytes, part.size)
            assertContentEquals(
                whole.copyOfRange(50 * info.recordBytes, 100 * info.recordBytes), part)
        }

    @Test
    fun `el volcado rapido sube antes de los bloques y vuelve al terminar`() =
        withSwitchableSimulator("--log-size", "137") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            assertTrue(info.supportsFastDump, "el simulador deberia ofrecer fastbaud")
            // El corto viene de la placa; la app no lo recalcula ni lo reinterpreta. Desde
            // el protocolo 3 tiene la forma GT001-XXXXXX: tipo de hardware, revision de
            // hardware y los seis ultimos digitos del numero de serie de fabrica.
            assertTrue(Regex("[A-Z]{2}\\d{3}-[0-9A-F]{6}").matches(info.shortId),
                       "sid con formato inesperado: '${info.shortId}'")
            assertTrue(info.boardId.endsWith(info.shortId.takeLast(6)),
                       "los seis digitos del corto tienen que ser los ultimos del completo")
            assertEquals(info.shortId, info.displayId)
            assertEquals(115200, info.baud)
            assertEquals(230400, info.fastBaud)

            val payload = s.download(info)
            assertEquals(info.recordCount * info.recordBytes, payload.size.toLong())
            // Sube una vez y baja una vez, y acaba en la velocidad de la consola: si
            // terminara en la rapida, el siguiente comando de texto seria ilegible.
            assertEquals(listOf(230400, 115200), t.bauds)
        }

    @Test
    fun `un enlace que no sabe cambiar de velocidad descarga igual`() =
        // Es el caso del Bluetooth: la placa ofrece fastbaud, pero el enlace no puede
        // seguirla, asi que ni se le pide.
        withSimulator("--log-size", "137") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            assertTrue(info.supportsFastDump)
            assertEquals(info.recordCount * info.recordBytes, s.download(info).size.toLong())
        }

    @Test
    fun `un fragmento mas pequeno que la cabecera de bloque no rompe el reensamblado`() =
        // El lector debe tolerar que AA 55, el indice y la longitud lleguen partidos entre
        // varias entregas: es exactamente lo que hace una radio con MTU pequeno.
        withSimulator("--log-size", "64", "--mtu", "3") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            assertEquals(64L * info.recordBytes, s.download(info).size.toLong())
        }

    private fun <T> withLink(vararg extraArgs: String, perRequest: Int = 0,
                             body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + extraArgs).start()
        try {
            return ChunkedLink(proc.inputStream, proc.outputStream, 20, perRequest).use { t ->
                t.open(); body(t)
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `un log largo se descarga por tramos y sale completo`() =
        // 3.238 registros es el caso que fallaba en el telefono: de una sola peticion son
        // 152 bloques seguidos, mas de lo que un puente BLE puede reemitir mientras los
        // recibe.
        withLink("--log-size", "3238", perRequest = 128) { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            var maxFraction = 0f
            var ultimo: DownloadProgress? = null
            val payload = s.download(info, onProgress = {
                maxFraction = maxOf(maxFraction, it.fraction); ultimo = it
            })
            assertEquals(3238L * info.recordBytes, payload.size.toLong())
            assertEquals(3238, LogDecoder.decode(payload, info.signature).size)
            // El progreso es GLOBAL y no se reinicia en cada tramo, que si no la barra
            // volveria a cero veinticinco veces.
            assertTrue(maxFraction > 0.99f, "el progreso se quedo en $maxFraction")
            // El avance se cuenta en REGISTROS y sobre el total; antes contaba bloques del
            // tramo en curso y con tramos cortos decia "1/1" de principio a fin.
            val p = assertNotNull(ultimo)
            assertEquals(3238L, p.recordsTotal)
            assertTrue(p.recordsDone > 3200, "solo llego a ${p.recordsDone} registros")
            assertTrue(p.recordsPerSecond > 0, "el ritmo salio en ${p.recordsPerSecond}")
            assertTrue(p.elapsedSeconds > 0)
        }

    // El caso "enlace que descarta datos" ya no vive aqui.
    //
    // Con el ajuste automatico del tamano de tramo, un enlace con poco buffer deja de ser un
    // fallo: la app baja el tramo hasta dar con uno que pasa, que es justamente la mejora.
    // Construir un escenario que AUN falle exige un buffer tan pequeno que ni la cabecera
    // INFO cabe, con lo que se estaria probando otra cosa.
    //
    // La propiedad que importa --rendirse pronto y decir por que cuando ningun tamano
    // sirve-- la cubre AdaptiveChunkTest de forma determinista y en milisegundos.


}
