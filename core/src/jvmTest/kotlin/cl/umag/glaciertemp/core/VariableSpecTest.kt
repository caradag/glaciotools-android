package cl.umag.glaciertemp.core

import kotlin.test.*

class VariableSpecTest {

    @Test fun `los codigos coinciden con varComm del firmware`() {
        assertEquals("INTLVMTZNADJMSW", Variables.ALL.joinToString("") { it.code })
    }

    @Test fun `las longitudes coinciden con varLengths`() {
        assertEquals(listOf(4, 1, 2, 2, 1), Variables.ALL.map { it.bytes })
    }

    @Test fun `el comando de escritura lleva el signo igual que espera isVarCommand`() {
        val int = checkNotNull(Variables.byCode("INT"))
        assertEquals("INT=600", int.writeCommand(600))
        assertEquals("INT", int.readCommand())
        assertEquals("INT=600\n", Protocol.setVariable(int, 600))
    }

    @Test fun `la validacion rechaza fuera de rango antes de enviar`() {
        val int = checkNotNull(Variables.byCode("INT"))
        assertNotNull(int.validate(0))
        assertNull(int.validate(600))
    }

    @Test fun `LOGB admite descarga completa y por rango`() {
        assertEquals("LOGB\n", Protocol.logBinary())
        assertEquals("LOGB=100,200\n", Protocol.logBinary(100, 200))
    }

    @Test fun `INFO y I son comandos distintos`() {
        // "I" imprime para personas; "INFO" la cabecera de campos fijos que parsea la app.
        assertEquals("I", Protocol.INFO_HUMAN)
        assertEquals("INFO", Protocol.METADATA)
    }

    @Test
    fun `lee el valor aunque la placa responda con el rotulo y no con el codigo`() {
        // Es lo que hace displayVars(): imprime el rotulo grabado en la EEPROM. Buscar
        // "INT:" no encontraba nada y la app mostraba "?" en todos los campos.
        assertEquals("600", VariableSpec.parseValue("Interval between measurements (sec): 600"))
        assertEquals("-3", VariableSpec.parseValue("Time Zone (hours): -3"))
        assertEquals("4", VariableSpec.parseValue("Low voltage interval multiplier: 4"))
    }

    @Test
    fun `un rotulo con dos puntos dentro no confunde la lectura`() {
        assertEquals("7", VariableSpec.parseValue("Ajuste: cada cuanto (dias): 7"))
    }

    @Test
    fun `una linea posterior con hora no se confunde con el valor`() {
        // Respuesta LITERAL de una placa real al comando INT.
        assertEquals("2", VariableSpec.parseValue(
            "Interval between measurements (sec): 2\n" +
            "Next Wakeup: 2026-09-05 00:03:10 (UTC-3)\n" +
            "*********** Waiting commands (for 120 s) ***********"))
        // Y tambien si algun dia esa linea se imprimiera sin la zona horaria.
        assertEquals("2", VariableSpec.parseValue(
            "Interval between measurements (sec): 2\nNext Wakeup: 2026-09-05 00:03:10"))
    }

    @Test
    fun `de una respuesta con eco se queda con la linea util`() {
        // Al escribir, la placa puede devolver mas de una linea; la que vale es la ultima.
        assertEquals("900", VariableSpec.parseValue(
            "INT=900\nInterval between measurements (sec): 900"))
    }

    @Test
    fun `una respuesta sin numero no inventa un valor`() {
        assertNull(VariableSpec.parseValue("Wrong format: INT=abc"))
        assertNull(VariableSpec.parseValue(""))
    }
}
