package cl.umag.glaciertemp.core.firmware

/**
 * El descriptor ("GTDG") del firmware de DIAGNOSTICO de la GlacierTemp, desde su version 1.6.
 *
 * Es otro bloque que el del logger ([FirmwareDescriptor], "GTFW") a proposito: el diagnostico
 * no registra nada, asi que canales, pines de alimentacion y CONT no le aplican, y reutilizar
 * aquel formato haria decir a la app cosas falsas ("deja de registrar A0"). Este solo dice
 * para que hardware es, que version y que grupo de pruebas trae.
 *
 * Formato de la version 1, sin relleno: "GTDG", version, hardware\0, version\0, fecha\0,
 * modo\0 ("BOARD" o "SENSORS").
 */
data class DiagnosticDescriptor(
    val layout: Int,
    val hardware: String,
    val version: String,
    val buildDate: String,
    val mode: String,
) {
    companion object {
        const val MAGIC = "GTDG"
        const val LAYOUT = 1

        fun parse(b: ByteArray, at: Int): DiagnosticDescriptor? {
            if (at < 0 || at + 5 > b.size) return null
            if ((0 until 4).any { b[at + it].toInt().toChar() != MAGIC[it] }) return null
            if ((b[at + 4].toInt() and 0xFF) != LAYOUT) return null
            var p = at + 5
            fun str(max: Int): String? {
                val sb = StringBuilder()
                while (true) {
                    if (p >= b.size || sb.length > max) return null
                    val c = b[p++].toInt() and 0xFF
                    if (c == 0) return sb.toString()
                    if (c < 0x20 || c > 0x7E) return null
                    sb.append(c.toChar())
                }
            }
            val hw = str(16) ?: return null
            val ver = str(16) ?: return null
            val date = str(16) ?: return null
            val mode = str(16) ?: return null
            if (mode !in setOf("BOARD", "SENSORS")) return null
            return DiagnosticDescriptor(LAYOUT, hw, ver, date, mode)
        }

        /** Busca el descriptor en una imagen de flash (de un .hex). */
        fun find(flash: ByteArray): DiagnosticDescriptor? {
            val m = MAGIC.map { it.code.toByte() }
            for (i in 0..flash.size - 4) {
                if (flash[i] == m[0] && flash[i + 1] == m[1] && flash[i + 2] == m[2] && flash[i + 3] == m[3]) {
                    parse(flash, i)?.let { return it }
                }
            }
            return null
        }

        /** Lo que hay que saber antes de subirlo: que es, que prueba y que la placa deja de medir. */
        fun describe(d: DiagnosticDescriptor): List<String> = listOf(
            "Diagnostics firmware ${d.version} (${d.mode} tests) for GlacierTemp ${d.hardware}, " +
                "built ${d.buildDate.replace("  ", " ")}.",
            if (d.mode == "BOARD")
                "Tests the EEPROM, clock, flash memory, 1-Wire bus, sleep current, pins, LEDs and Bluetooth."
            else
                "Tests the HDC1080 and TMP119 sensors, the A0..A3 inputs and the settling time of " +
                "switched sensors (SETTLE, STEP and PWR drive A1..A3 as power only when you run them).",
            "It does NOT log: the board stops measuring until a logger firmware is uploaded again. " +
                "The app cannot talk to it; use a serial terminal at 115200 baud. To go back, use " +
                "\"Update firmware (USB)\" on the connect screen.",
        )
    }
}
