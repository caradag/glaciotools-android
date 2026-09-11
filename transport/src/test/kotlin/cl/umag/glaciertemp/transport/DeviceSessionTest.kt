package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.*
import java.io.File
import kotlin.test.*

/** Cubre DeviceSession contra el simulador: es el camino que usa la app de verdad. */
class DeviceSessionTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    private fun <T> withSession(vararg args: String, body: (DeviceSession) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + args).start()
        try {
            return PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open()
                val s = DeviceSession(t)
                s.drainBanner()
                body(s)
            }
        } finally { proc.destroyForcibly() }
    }

    @Test fun `info parsea la cabecera de metadatos`() =
        withSession("--log-size", "240") { s ->
            val i = assertNotNull(s.info(), "INFO no se parseo")
            assertEquals("E5A1B2C3D4E5F607", i.boardId)
            assertEquals(240L, i.recordCount)
            assertEquals(0x100F, i.signature)
            assertEquals(12, i.recordBytes)
        }

    @Test fun `download completo devuelve todos los registros`() =
        withSession("--log-size", "60", "--mtu", "20") { s ->
            val i = assertNotNull(s.info())
            val payload = s.download(i)
            assertEquals(60, LogDecoder.decode(payload, i.signature).size)
        }

    @Test fun `download por rango devuelve solo ese tramo`() =
        withSession("--log-size", "240") { s ->
            val i = assertNotNull(s.info())
            var last: DownloadProgress? = null
            val payload = s.download(i, from = 10, to = 29) { last = it }
            assertEquals(20, LogDecoder.decode(payload, i.signature).size)
            assertEquals(20L * i.recordBytes, assertNotNull(last).bytesTotal)
        }

    @Test fun `escribir una variable devuelve el valor nuevo`() =
        withSession("--log-size", "10") { s ->
            val int = checkNotNull(Variables.byCode("INT"))
            // Se comprueba el VALOR leido, no el texto de la respuesta: la placa imprime el
            // rotulo que el initializer grabo en su EEPROM, no el codigo del comando.
            // Afirmar sobre "INT: 900" pasaba contra el simulador y fallaba contra la placa.
            assertEquals("900", VariableSpec.parseValue(s.writeVariable(int, 900)))
            assertEquals("900", VariableSpec.parseValue(s.readVariable(int)))
        }
}
