package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.firmware.Urclock
import java.io.File
import kotlin.test.*

/**
 * El cargador contra tools/fake_urboot.py, un Urboot simulado que arranca con la flash REAL
 * de la placa (tools/urclock-golden/readback.hex). Lo que importa es la flash que queda: la
 * misma que dejo avrdude, o, si algo falla, una placa que sigue arrancando por el bootloader.
 */
class FirmwareUploaderTest {

    private fun root(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_urboot.py").exists()) d = d.parentFile ?: break
        return d
    }

    private fun golden(n: String) = File(root(), "tools/urclock-golden/$n")
    private fun fixture(n: String) = File(root(), "tools/gtfw-fixtures/$n")

    private fun flash(f: File): ByteArray =
        Urclock.parseHex(f.readText(), bootStart = Urclock.FLASH_SIZE).image.data

    /** Corre el cargador contra el simulador y devuelve la flash que quedo (o la excepcion). */
    private fun cargar(hex: File, vararg extra: String): Pair<ByteArray?, Throwable?> {
        val dump = File.createTempFile("urboot", ".hex").apply { delete() }
        val proc = ProcessBuilder(listOf("python3", "-u", File(root(), "tools/fake_urboot.py").path,
            "--image", golden("readback.hex").path, "--dump", dump.path) + extra).start()
        val error = try {
            PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open()
                val img = Urclock.parseHex(hex.readText(), 0x7E80).image
                FirmwareUploader(t).upload(img)
            }
            null
        } catch (e: Throwable) { e } finally {
            proc.outputStream.close(); proc.waitFor(); proc.destroyForcibly()
        }
        return (if (dump.exists()) flash(dump) else null) to error
    }

    @Test fun `subir el mismo firmware deja la flash identica a la de avrdude`() {
        val (fin, error) = cargar(golden("original.hex"))
        assertNull(error, error?.toString())
        assertContentEquals(flash(golden("readback.hex")), assertNotNull(fin))
    }

    @Test fun `subir la 3_12 sobre la 3_11 deja el firmware ajustado`() {
        val hex = fixture("fw312-default.hex")
        val (fin, error) = cargar(hex)
        assertNull(error, error?.toString())
        val placa = flash(golden("readback.hex"))
        val boot = Urclock.bootInfo(placa.copyOfRange(placa.size - 6, placa.size))!!
        val esperado = Urclock.patch(Urclock.parseHex(hex.readText(), 0x7E80).image, boot)
        val f = assertNotNull(fin)
        for (p in Urclock.pages(esperado)) {
            assertContentEquals(Urclock.page(esperado, p), f.copyOfRange(p, p + 128), "pagina 0x${p.toString(16)}")
        }
        // El bootloader no se toca.
        assertContentEquals(placa.copyOfRange(0x7E80, 0x8000), f.copyOfRange(0x7E80, 0x8000))
    }

    @Test fun `un corte a mitad no toca la pagina 0`() {
        val (_, error) = cargar(fixture("fw312-default.hex"), "--fail-after", "40")
        val e = assertIs<FirmwareUploadException>(error)
        assertEquals(BoardState.PARTIAL, e.boardState)
    }

    @Test fun `una pagina que no se relee igual es un error`() {
        val (_, error) = cargar(fixture("fw312-default.hex"), "--corrupt", "0x1000")
        val e = assertIs<FirmwareUploadException>(error)
        assertTrue(e.message!!.contains("0x1000"), e.message)
    }

    @Test fun `sin bootloader no se escribe nada`() {
        val t = object : Transport {
            override val isOpen = true
            override fun open() {}
            override fun close() {}
            override fun write(data: ByteArray) {}
            override fun read(timeoutMs: Int): ByteArray { Thread.sleep(minOf(timeoutMs, 5).toLong()); return ByteArray(0) }
        }
        val img = Urclock.parseHex(golden("original.hex").readText(), 0x7E80).image
        val e = assertFailsWith<FirmwareUploadException> { FirmwareUploader(t).upload(img) }
        assertEquals(BoardState.UNCHANGED, e.boardState)
    }
}
