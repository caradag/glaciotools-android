package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.*
import java.io.File
import kotlin.test.*

/**
 * Prueba de punta a punta contra tools/fake_glaciertemp.py: descarga por LOGB, la
 * decodifica y la compara con lo que la misma placa entrega por LOGC. Corre sin
 * emulador, sin telefono y sin hardware.
 */
class SimulatorE2ETest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    /**
     * Arranca el simulador, espera a que anuncie el puerto en su propia salida y entrega
     * un transporte conectado. Sondear el puerto abriendo y cerrando una conexion
     * consumiria el accept() del simulador y deja una carrera.
     */
    /**
     * Arranca el simulador en modo --stdio y entrega un transporte sobre sus pipes.
     * Se evita TCP a proposito: no hace falta red para probar la cadena completa, y el
     * pipe es determinista -- no hay puerto que colisione ni carrera con el accept().
     */
    private fun <T> withSimulator(vararg extraArgs: String, body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        assertTrue(script.exists(), "no se encontro ${script.path}")
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

    /** Envia un comando y acumula la respuesta hasta que se agota el silencio. */
    private fun Transport.ask(cmd: String, quietMs: Int = 400): ByteArray {
        if (cmd.isNotEmpty()) writeLine(cmd)
        val out = java.io.ByteArrayOutputStream()
        var idle = 0
        while (idle < quietMs) {
            val chunk = read(100)
            if (chunk.isEmpty()) idle += 100 else { out.write(chunk); idle = 0 }
        }
        return out.toByteArray()
    }

    @Test fun `descarga por LOGB y la compara con LOGC`() =
        withSimulator("--log-size", "137", "--mtu", "20") { t ->
            t.ask("")                                     // vaciar el banner de arranque

            val info = String(t.ask("INFO"))
            val sig = Regex("sig=0x([0-9A-Fa-f]+)").find(info)!!.groupValues[1].toInt(16)
            val count = Regex("count=(\\d+)").find(info)!!.groupValues[1].toInt()
            assertEquals(0x100F, sig)
            assertEquals(137, count)

            // LOGB completo, reensamblado desde fragmentos de 20 bytes (MTU de HM-10).
            val raw = t.ask("LOGB", quietMs = 2000)
            val events = LogbReader().feed(raw)
            val header = assertNotNull(
                events.filterIsInstance<LogbEvent.Header>().firstOrNull()).header
            val blocks = events.filterIsInstance<LogbEvent.Block>()
            val (payload, bad) = Logb.assemble(header, blocks)
            assertTrue(bad.isEmpty(), "bloques con CRC malo: $bad")
            assertEquals(count.toLong() * header.recordBytes, header.payloadBytes)

            val mine = CsvExporter.export(LogDecoder.decode(payload, sig), sig).trimEnd('\n')

            // La misma placa por la via ASCII, que es el camino ya probado en campo.
            val theirs = String(t.ask("LOGC", quietMs = 2000))
                .lines().map { it.trim() }.filter { it.contains(",") }
                .joinToString("\n").trimEnd('\n')

            assertEquals(theirs, mine, "LOGB decodificado difiere de LOGC")
        }

    @Test fun `LOGB por rango entrega solo los registros pedidos`() =
        withSimulator("--log-size", "137") { t ->
            t.ask("")
            val raw = t.ask("LOGB=10,19", quietMs = 800)
            val events = LogbReader().feed(raw)
            val header = assertNotNull(
                events.filterIsInstance<LogbEvent.Header>().firstOrNull()).header
            assertEquals(10L, header.from)
            assertEquals(19L, header.to)
            val (payload, bad) = Logb.assemble(header, events.filterIsInstance<LogbEvent.Block>())
            assertTrue(bad.isEmpty())
            assertEquals(10, LogDecoder.decode(payload, header.signature).size)
        }

    @Test fun `las variables se leen y se escriben`() =
        withSimulator("--log-size", "10") { t ->
            t.ask("")
            val int = checkNotNull(Variables.byCode("INT"))
            // Por el valor y no por el texto: el rotulo lo graba el initializer en la EEPROM
            // de cada placa y no es algo sobre lo que la app pueda afirmar nada.
            assertEquals("600", VariableSpec.parseValue(String(t.ask(int.readCommand()))))
            assertEquals("900", VariableSpec.parseValue(String(t.ask(int.writeCommand(900)))))
        }

    @Test fun `la respuesta trae el rotulo del firmware, no el codigo`() =
        // Fija la fidelidad del simulador: cuando respondia "INT: 600" en vez del rotulo,
        // toda la cadena de lectura pasaba en verde mientras la app mostraba "?" en el
        // telefono. Un simulador que no reproduce el formato real aprueba codigo roto.
        withSimulator("--log-size", "10") { t ->
            t.ask("")
            val reply = String(t.ask("INT"))
            assertTrue(reply.contains("Interval between measurements (sec):"),
                       "el simulador deberia imitar a displayVars(): $reply")
        }

    @Test
    fun `reiniciar el contador deja el log a cero y lo dice la cabecera`() =
        withSimulator("--log-size", "137") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val antes = assertNotNull(s.requireInfo())
            assertEquals(137L, antes.recordCount)

            val reply = String(s.exchange(cl.umag.glaciertemp.core.Protocol.RESET_COUNTER,
                                          quietMs = 500))
            assertTrue(reply.contains("Memory reset"), "respuesta inesperada de RC: <$reply>")

            // Lo que importa: la app tiene que VOLVER A LEER la cabecera. Fiarse del valor
            // guardado dejaria la tarjeta anunciando los registros de antes y la descarga
            // ofreciendo un rango que ya no existe.
            val despues = assertNotNull(s.info())
            assertEquals(0L, despues.recordCount,
                         "tras RC la cabecera sigue anunciando registros")
            assertEquals(antes.boardId, despues.boardId, "cambio de placa, no de contador")
        }
}
