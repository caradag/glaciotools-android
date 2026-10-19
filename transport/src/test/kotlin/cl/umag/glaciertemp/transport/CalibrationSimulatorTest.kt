package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.BoardCalibration
import cl.umag.glaciertemp.core.BoardCalibration.BoardPoint
import java.io.File
import kotlin.test.*

/**
 * La calibracion con el par completo contra tools/fake_glaciertemp.py: los comandos que
 * arma la app, las respuestas que lee, y sobre todo que DETECTA a una placa que guardo otra
 * cuenta. Un firmware anterior al 3.16 acepta "A01=2.000>258" sin error y guarda la cuenta
 * que lee en ese momento; lo unico que lo delata es lo que contesta.
 */
class CalibrationSimulatorTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    private fun <T> withSimulator(vararg extraArgs: String, body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        assertTrue(script.exists(), "no se encontro ${script.path}")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio", "--signature", "0x102F") +
                extraArgs
        ).start()
        try {
            return PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open(); body(t)
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    private fun Transport.ask(cmd: String, quietMs: Int = 400): String {
        if (cmd.isNotEmpty()) writeLine(cmd)
        val out = java.io.ByteArrayOutputStream()
        var idle = 0
        while (idle < quietMs) {
            val chunk = read(100)
            if (chunk.isEmpty()) idle += 100 else { out.write(chunk); idle = 0 }
        }
        return String(out.toByteArray())
    }

    private val a0 = BoardCalibration.analog(0)

    @Test fun `la cuenta se lee de las respuestas de V y de A0`() =
        withSimulator("--battery-raw", "705", "--analog-raw", "96") { t ->
            t.ask("")
            assertEquals(705, BoardCalibration.rawFromReply(t.ask(BoardCalibration.VOLTAGE.readCommand)))
            assertEquals(96, BoardCalibration.rawFromReply(t.ask(a0.readCommand)))
        }

    @Test fun `con protocolo 8 la placa guarda el par mandado`() =
        withSimulator("--protocol", "8", "--analog-raw", "300") { t ->
            t.ask("")
            val p1 = BoardPoint(0.014, 4)
            val p2 = BoardPoint(3.002, 760)
            assertTrue(BoardCalibration.confirms(t.ask(BoardCalibration.command(a0, 1, p1)), p1))
            assertTrue(BoardCalibration.confirms(t.ask(BoardCalibration.command(a0, 2, p2)), p2))
            // Y la placa usa esa recta: 300 cuentas, interpolado entre los dos puntos.
            val esperado = (14 + (3002 - 14) * (300 - 4) / (760 - 4)) / 1000.0
            assertTrue(t.ask("A0").contains(String.format(java.util.Locale.US, "%.3f V", esperado)))
        }

    @Test fun `una placa vieja guarda otra cuenta y la app lo detecta`() =
        withSimulator("--protocol", "7", "--analog-raw", "300") { t ->
            t.ask("")
            val p = BoardPoint(2.0, 258)
            val reply = t.ask(BoardCalibration.command(a0, 1, p))
            assertTrue("-> 300" in reply, "el simulador debe imitar al firmware viejo: $reply")
            assertFalse(BoardCalibration.confirms(reply, p))
        }

    @Test fun `una cuenta imposible no se guarda y no se confirma`() =
        withSimulator("--protocol", "8") { t ->
            t.ask("")
            val p = BoardPoint(2.0, 2000)
            val reply = t.ask(BoardCalibration.command(a0, 1, p))
            assertTrue("Bad count" in reply, reply)
            assertFalse(BoardCalibration.confirms(reply, p))
        }
}
