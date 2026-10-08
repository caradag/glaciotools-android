package cl.umag.glaciertemp.core.firmware

import java.io.File
import kotlin.test.*

/** El descriptor GTDG leido de los .hex reales del diagnostico 1.6 (tools/gtfw-fixtures). */
class DiagnosticDescriptorTest {

    private fun image(name: String): ByteArray {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/gtfw-fixtures").exists()) d = d.parentFile ?: break
        return Urclock.parseHex(File(d, "tools/gtfw-fixtures/$name").readText(), 0x7E80).image.data
    }

    @Test fun `los dos modos del diagnostico`() {
        val s = assertNotNull(DiagnosticDescriptor.find(image("diag16-sensors.hex")))
        assertEquals("GT001", s.hardware); assertEquals("1.6", s.version); assertEquals("SENSORS", s.mode)
        val b = assertNotNull(DiagnosticDescriptor.find(image("diag16-board.hex")))
        assertEquals("BOARD", b.mode)
        // El diagnostico no lleva el descriptor del logger, ni el logger el del diagnostico.
        assertNull(FirmwareDescriptor.find(image("diag16-sensors.hex")))
        assertNull(DiagnosticDescriptor.find(image("fw312-default.hex")))
    }

    @Test fun `el resumen dice que no registra y como volver`() {
        val t = DiagnosticDescriptor.describe(assertNotNull(DiagnosticDescriptor.find(image("diag16-board.hex"))))
        assertTrue(t[0].startsWith("Diagnostics firmware 1.6 (BOARD tests) for GlacierTemp GT001"), t[0])
        assertTrue(t.last().contains("does NOT log") && t.last().contains("Update firmware (USB)"), t.last())
    }

    @Test fun `el hardware del diagnostico se comprueba igual que el del logger`() {
        assertNull(FirmwareDescriptor.hardwareProblemOf("GT001", "GT001"))
        assertNull(FirmwareDescriptor.hardwareProblemOf("GT001", null))
        assertNotNull(FirmwareDescriptor.hardwareProblemOf("GT001", "GT002"))
        assertNotNull(FirmwareDescriptor.hardwareProblemOf(null, "GT001"))
    }
}
