package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.firmware.Urclock
import java.io.File
import kotlin.test.*

/**
 * El cargador contra la PLACA REAL, desde el PC, por tools/serial_bridge.py. Solo corre con
 * GT_REAL_BOARD=/dev/ttyUSB0 (y opcionalmente GT_REAL_HEX=<archivo>; por defecto sube
 * tools/urclock-golden/original.hex, el 3.11 que ya tiene la placa de referencia, con lo que
 * la placa queda igual). Nunca corre solo: escribe en un chip.
 */
class RealBoardUploadTest {

    @Test fun `subir a la placa real`() {
        val port = System.getenv("GT_REAL_BOARD") ?: return
        var root = File(System.getProperty("user.dir"))
        while (!File(root, "tools/serial_bridge.py").exists()) root = root.parentFile
        val hex = System.getenv("GT_REAL_HEX")?.let { File(it) } ?: File(root, "tools/urclock-golden/original.hex")
        val proc = ProcessBuilder("python3", "-u", File(root, "tools/serial_bridge.py").path, port).start()
        try {
            PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open()
                val img = Urclock.parseHex(hex.readText(), 0x7E80).image
                val log = ArrayList<String>()
                val t0 = System.currentTimeMillis()
                val boot = FirmwareUploader(t) { log += it; println("[uploader] $it") }.upload(img)
                println("[uploader] ${Urclock.pages(Urclock.patch(img, boot)).size} pages in " +
                        "${System.currentTimeMillis() - t0} ms")
                assertEquals(0x7E80, boot.start)
            }
        } finally {
            proc.outputStream.close(); proc.waitFor(); proc.destroyForcibly()
        }
    }
}
