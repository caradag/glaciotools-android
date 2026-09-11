package cl.umag.glaciertemp.transport

import java.io.File
import kotlin.test.*

/**
 * El volcado crudo contra el simulador, que es donde se puede provocar lo que en terreno
 * solo pasa una vez: un registro con el checksum roto y un enlace que no da abasto.
 */
class RawHexDownloadTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    /** El simulador sobre sus propios pipes: sin red, sin puertos y sin carreras. */
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

    @Test
    fun `un registro con el checksum roto se detecta`() {
        // Vector valido tomado de la especificacion de Intel HEX.
        assertTrue(RawHexDownload.isValidRecord(":10010000214601360121470136007EFE09D2190140"))
        assertTrue(RawHexDownload.isValidRecord(":00000001FF"))
        // Un solo digito cambiado invalida la suma, que es justo para lo que sirve.
        assertFalse(RawHexDownload.isValidRecord(":10010000214601360121470136007EFE09D2190141"))
        // Longitud declarada que no cuadra con los bytes que vienen detras.
        assertFalse(RawHexDownload.isValidRecord(":10010000214601FF"))
        assertFalse(RawHexDownload.isValidRecord("no es un registro"))
        assertFalse(RawHexDownload.isValidRecord(":ZZ010000214601360121470136007EFE09D2190140"))
    }

    @Test
    fun `el volcado completo llega entero y valido`() =
        withSimulator("--log-size", "120", "--flash-size", "4096") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.requireInfo())

            val out = java.io.ByteArrayOutputStream()
            val diag = ArrayList<String>()
            val r = RawHexDownload.download(s, t, info.flashBytes, { out.write(it) },
                                            onDiagnostic = { diag.add(it) })

            assertTrue(r.sawEndOfFile, "falta el registro de fin de fichero: $diag")
            assertEquals(0L, r.recordsRejected, "no deberia descartarse nada: $diag")
            assertTrue(r.recordsWritten > 0)

            val text = String(out.toByteArray())
            assertTrue(text.startsWith(":"), "el preambulo de texto tiene que quedar fuera")
            assertTrue(text.trimEnd().endsWith(":00000001FF"))
            // Todas las lineas escritas son registros validos: el filtro no deja pasar nada.
            for (l in text.lineSequence().filter { it.isNotBlank() }) {
                assertTrue(RawHexDownload.isValidRecord(l.trim()), "linea invalida: $l")
            }
        }

    @Test
    fun `los datos del volcado crudo coinciden con los de LOGB`() =
        withSimulator("--log-size", "60", "--flash-size", "4096") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.requireInfo())
            val porLogb = s.download(info)

            val out = java.io.ByteArrayOutputStream()
            RawHexDownload.download(s, t, info.flashBytes, { out.write(it) })
            val crudo = intelHexToBytes(String(out.toByteArray()))

            // El volcado crudo trae la memoria ENTERA --incluido lo que nunca se escribio,
            // que lee 0xFF-- mientras que LOGB solo entrega los registros del contador.
            assertEquals(4096, crudo.size,
                         "LOGH tiene que volcar la flash completa, no solo lo grabado")
            assertTrue(crudo.size > porLogb.size)
            assertContentEquals(porLogb, crudo.copyOfRange(0, porLogb.size),
                                "las dos vias tienen que entregar los mismos bytes")
        }

    /** Decodificador minimo, solo para la comprobacion cruzada de arriba. */
    private fun intelHexToBytes(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith(":")) continue
            val b = t.substring(1).chunked(2).map { it.toInt(16) }
            if (b[3] != 0x00) continue                 // solo registros de datos
            for (i in 0 until b[0]) out.write(b[4 + i])
        }
        return out.toByteArray()
    }

    @Test
    fun `el volcado rapido sube tras la cabecera y vuelve con el fin de fichero`() =
        withSimulator("--log-size", "40", "--flash-size", "4096") { t ->
            // Un transporte que SI sabe cambiar de velocidad, envuelto sobre el simulador.
            val cambios = mutableListOf<Int>()
            val switchable = object : Transport by t, BaudSwitchable {
                override fun setBaudRate(baud: Int) { cambios += baud }
            }
            val s = DeviceSession(switchable)
            s.drainBanner()
            val info = assertNotNull(s.requireInfo())

            val out = java.io.ByteArrayOutputStream()
            val r = RawHexDownload.download(s, switchable, info.flashBytes, { out.write(it) },
                                            fastBaud = 230400, normalBaud = 115200)

            assertTrue(r.sawEndOfFile)
            assertEquals(listOf(230400, 115200), cambios,
                         "tiene que subir una vez y volver una vez")
            // Y lo descargado sigue siendo valido: el cambio no puede perder ni un registro.
            for (l in String(out.toByteArray()).lineSequence().filter { it.isNotBlank() }) {
                assertTrue(RawHexDownload.isValidRecord(l.trim()), "linea invalida: $l")
            }
        }

    @Test
    fun `sobre un enlace que no sabe cambiar de velocidad no se pide`() =
        withSimulator("--log-size", "20", "--flash-size", "2048") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.requireInfo())
            val out = java.io.ByteArrayOutputStream()
            // Se pide la rapida, pero el transporte no la implementa: tiene que ignorarse
            // en vez de dejar a la placa emitiendo a una velocidad que nadie va a leer.
            val r = RawHexDownload.download(s, t, info.flashBytes, { out.write(it) },
                                            fastBaud = 230400)
            assertTrue(r.sawEndOfFile, "el volcado tiene que completarse igual")
            assertEquals(0L, r.recordsRejected)
        }
}
