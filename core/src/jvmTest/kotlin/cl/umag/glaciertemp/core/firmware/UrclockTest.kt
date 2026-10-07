package cl.umag.glaciertemp.core.firmware

import java.io.File
import kotlin.test.*

/**
 * El ajuste de [Urclock] contra una carga REAL: tools/urclock-golden guarda la flash que
 * dejo `avrdude -c urclock` en la placa (readback.hex) al subir original.hex. La imagen que
 * arma la app tiene que ser identica byte a byte; si no, el firmware quedaria escrito y no
 * arrancaria, o el bootloader dejaria de alcanzarse.
 */
class UrclockTest {

    private fun golden(name: String): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/urclock-golden").exists()) d = d.parentFile ?: break
        return File(d, "tools/urclock-golden/$name")
    }

    /** La flash entera de la placa, 32 KB. */
    private val placa: ByteArray by lazy {
        val p = Urclock.parseHex(golden("readback.hex").readText(), bootStart = Urclock.FLASH_SIZE)
        p.image.data
    }

    private val boot by lazy { assertNotNull(Urclock.bootInfo(placa.copyOfRange(placa.size - 6, placa.size))) }

    @Test fun `la tabla de Urboot de la placa`() {
        assertEquals(3, boot.pages)
        assertEquals(0x7E80, boot.start)
        assertEquals(25, boot.vectorNumber)
        assertEquals("7.7", boot.versionText)
    }

    @Test fun `el ajuste reproduce la flash que dejo avrdude`() {
        val hex = Urclock.parseHex(golden("original.hex").readText(), boot.start)
        assertEquals(0, hex.droppedBootBytes)
        val img = Urclock.patch(hex.image, boot)
        val distintos = (0 until boot.start).filter { img.data[it] != placa[it] }
        assertTrue(distintos.isEmpty(), "difiere en ${distintos.size} bytes, el primero 0x${distintos.firstOrNull()?.toString(16)}")
        // Lo esencial, explicito: rjmp hacia atras al bootloader con la segunda palabra
        // intacta, y el vector 25 al inicio del firmware.
        assertEquals("3fcff605", img.data.copyOfRange(0, 4).toHex())
        assertEquals("0c94f605", img.data.copyOfRange(100, 104).toHex())
        // El original no se toca.
        assertEquals("0c94f605", hex.image.data.copyOfRange(0, 4).toHex())
        assertEquals("0c941e06", hex.image.data.copyOfRange(100, 104).toHex())
    }

    @Test fun `las paginas cubren el firmware y la cero va al final`() {
        val img = Urclock.patch(Urclock.parseHex(golden("original.hex").readText(), boot.start).image, boot)
        val p = Urclock.pages(img)
        assertEquals((30_582 + 127) / 128, p.size)
        assertEquals(0, p.last())
        assertEquals(p.dropLast(1), p.dropLast(1).sorted())
        assertTrue(p.all { it % Urclock.PAGE_SIZE == 0 && it < boot.start })
        // La ultima pagina parcial se completa con 0xFF.
        val ultima = Urclock.page(img, p[p.size - 2])
        assertEquals(0xFF.toByte(), ultima.last())
    }

    @Test fun `el hex con bootloader del IDE se acepta sin el bootloader`() {
        val original = golden("original.hex").readText().trim().lines().dropLast(1)
        // Lo que el IDE pone en .with_bootloader.hex: el firmware y, ademas, el bootloader.
        val boot16 = (boot.start until Urclock.FLASH_SIZE step 16).map { a ->
            record(a, placa.copyOfRange(a, a + 16))
        }
        val conBoot = (original + boot16 + ":00000001FF").joinToString("\n")
        val p = Urclock.parseHex(conBoot, boot.start)
        assertEquals(384, p.droppedBootBytes)
        assertEquals(30_582, p.image.size)
    }

    @Test fun `un hex danado o incompleto se rechaza entero`() {
        val txt = golden("original.hex").readText()
        val lineas = txt.trim().lines()
        val malaSuma = lineas.toMutableList().also { l ->
            val x = l[10]; l[10] = x.dropLast(2) + (if (x.endsWith("00")) "01" else "00")
        }.joinToString("\n")
        assertFailsWith<Urclock.HexException> { Urclock.parseHex(malaSuma, boot.start) }
        assertFailsWith<Urclock.HexException> {
            Urclock.parseHex(lineas.dropLast(1).joinToString("\n"), boot.start)   // sin fin de archivo
        }
        assertFailsWith<Urclock.HexException> { Urclock.parseHex("hola\n:00000001FF", boot.start) }
    }

    @Test fun `un firmware que no cabe o sin tabla de vectores se rechaza`() {
        val hex = Urclock.parseHex(golden("original.hex").readText(), boot.start)
        // Un boot mas grande deja menos sitio que el firmware.
        val grande = boot.copy(pages = 20)   // 2,5 KB: el bootloader empezaria en 30.208 B
        assertFailsWith<Urclock.PatchException> { Urclock.patch(hex.image, grande) }
        // Un vector que no es jmp.
        val roto = hex.image.data.copyOf().also { it[8] = 0; it[9] = 0 }
        assertFailsWith<Urclock.PatchException> {
            Urclock.patch(Urclock.Image(roto, hex.image.defined), boot)
        }
    }

    @Test fun `una tabla que no es de Urboot no se acepta`() {
        assertNull(Urclock.bootInfo(ByteArray(6) { 0xFF.toByte() }))
        assertNull(Urclock.bootInfo(byteArrayOf(0, 25, 0, 0, 0xE7.toByte(), 0x3F)))   // 0 paginas
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun record(addr: Int, data: ByteArray): String {
        val b = byteArrayOf(data.size.toByte(), (addr shr 8).toByte(), addr.toByte(), 0) + data
        val sum = (-b.sumOf { it.toInt() and 0xFF }) and 0xFF
        return ":" + (b + sum.toByte()).joinToString("") { "%02X".format(it) }
    }
}
