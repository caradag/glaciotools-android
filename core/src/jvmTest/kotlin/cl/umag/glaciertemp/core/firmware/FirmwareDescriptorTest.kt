package cl.umag.glaciertemp.core.firmware

import java.io.File
import kotlin.test.*

/**
 * El descriptor GTFW leido de dos .hex REALES del firmware 3.12 (tools/gtfw-fixtures):
 * la configuracion por defecto y la de la placa del sensor de presion (TMP119 + A0
 * "Depth [m]" alimentado desde A1+A2+A3). Lo que se comprueba es lo que vera quien sube un
 * firmware: que hardware exige, que mide y que pines pasan a ser salidas.
 */
class FirmwareDescriptorTest {

    private fun fixture(name: String): String {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/gtfw-fixtures").exists()) d = d.parentFile ?: break
        return File(d, "tools/gtfw-fixtures/$name").readText()
    }

    private fun desc(name: String): FirmwareDescriptor {
        val img = Urclock.parseHex(fixture(name), bootStart = 0x7E80).image
        return assertNotNull(FirmwareDescriptor.find(img.data), "sin descriptor en $name")
    }

    @Test fun `el descriptor por defecto`() {
        val d = desc("fw312-default.hex")
        assertEquals("GT001", d.hardware)
        assertEquals("3.12", d.firmware)
        assertEquals(0x100F, d.signature)
        assertEquals(8, d.tmp119Averages)
        assertTrue(d.cont)
        assertEquals(listOf(0, 0, 0, 0), d.power)
        assertEquals(listOf("A0", "A1", "A2", "A3"), d.names)
        assertEquals(0, d.powerPins)
    }

    @Test fun `el descriptor de la placa de presion`() {
        val d = desc("fw312-depth-a0.hex")
        assertEquals(0x102F, d.signature)
        assertTrue(d.logs(0)); assertFalse(d.logs(1))
        assertEquals(0x0E, d.power[0])
        assertEquals(250, d.settleMs[0])
        assertEquals("Depth [m]", d.names[0])
        assertFalse(d.cont)
    }

    @Test fun `el hex con bootloader trae el mismo descriptor`() {
        val a = desc("fw312-depth-a0.hex")
        val b = desc("fw312-depth-a0.with_bootloader.hex")
        assertEquals(a, b)
    }

    @Test fun `el resumen dice primero los pines de alimentacion y luego lo que mide`() {
        val t = FirmwareDescriptor.describe(desc("fw312-depth-a0.hex"))
        assertTrue(t[0].startsWith("Firmware 3.12 for GlacierTemp GT001"), t[0])
        assertTrue(t[1].startsWith("A1, A2 and A3 become POWER OUTPUTS for the sensor on A0"), t[1])
        assertTrue(t[1].contains("250 ms"), t[1])
        val registra = t.first { it.startsWith("Records:") }
        assertTrue(registra.contains("analog input A0 \"Depth [m]\""), registra)
        assertTrue(registra.contains("TMP119, 8 averages"), registra)
        assertTrue(t.last().contains("not included"), t.last())
        // Sin pines de alimentacion no se menciona ninguno.
        assertTrue(FirmwareDescriptor.describe(desc("fw312-default.hex")).none { it.contains("POWER OUTPUTS") })
    }

    @Test fun `de la placa por defecto a la de presion`() {
        val placa = desc("fw312-default.hex")
        val archivo = desc("fw312-depth-a0.hex")
        val c = FirmwareDescriptor.changes(placa, null, boardRecords = 2005, file = archivo)
        assertTrue("Power for A0: none → A1, A2 and A3." in c, c.toString())
        assertTrue("Starts recording A0." in c, c.toString())
        assertTrue("Removes continuous capture." in c, c.toString())
        assertTrue(c.any { it.contains("2005 records") && it.contains("(RC)") }, c.toString())
        // Mismo firmware: nada que decir.
        assertTrue(FirmwareDescriptor.changes(archivo, null, 2005, archivo).isEmpty())
    }

    @Test fun `una placa sin descriptor se compara solo por la firma de INFO`() {
        val c = FirmwareDescriptor.changes(null, boardSignature = 0x100F, boardRecords = 0,
                                           file = desc("fw312-depth-a0.hex"))
        assertTrue("Starts recording A0." in c, c.toString())
        assertTrue(c.last().contains("without build descriptor"), c.toString())
    }

    @Test fun `solo bloquea un hardware que no corresponde`() {
        val d = desc("fw312-default.hex")
        assertNull(FirmwareDescriptor.hardwareProblem(d, FirmwareDescriptor.hardwareOf("GT001-1B4237")))
        assertNull(FirmwareDescriptor.hardwareProblem(d, null))
        assertNotNull(FirmwareDescriptor.hardwareProblem(d, "GT002"))
        assertNotNull(FirmwareDescriptor.hardwareProblem(d.copy(hardware = "XX001"), null))
        // Un .hex sin descriptor (firmware 3.11, el de la referencia de oro) no se puede comprobar.
        val viejo = Urclock.parseHex(goldenOriginal(), 0x7E80).image
        assertNull(FirmwareDescriptor.find(viejo.data))
        assertNotNull(FirmwareDescriptor.hardwareProblem(null, "GT001"))
    }

    @Test fun `la respuesta de CFG se lee igual que el hex`() {
        val img = Urclock.parseHex(fixture("fw312-depth-a0.hex"), 0x7E80).image.data
        val i = (0..img.size - 4).first { String(img, it, 4, Charsets.ISO_8859_1) == "GTFW" }
        val bytes = img.copyOfRange(i, i + 64)
        val linea = "CFG " + bytes.joinToString("") { "%02X".format(it) } + "\r\n"
        assertEquals(desc("fw312-depth-a0.hex"), FirmwareDescriptor.fromCfgLine("Waiting...\n$linea"))
        assertNull(FirmwareDescriptor.fromCfgLine("Unrecognized command:CFG"))
    }

    private fun goldenOriginal(): String {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/urclock-golden").exists()) d = d.parentFile ?: break
        return File(d, "tools/urclock-golden/original.hex").readText()
    }
}
