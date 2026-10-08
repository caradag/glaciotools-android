package cl.umag.glaciertemp.core.firmware

import cl.umag.glaciertemp.core.LogFormat

/**
 * El descriptor de compilacion ("GTFW") que lleva el firmware desde la 3.12.
 *
 * La version dice QUE codigo es; esto dice COMO se compilo: para que hardware, que canales
 * registra y que pines pone como salidas de alimentacion. Nada de eso se puede deducir del
 * codigo maquina, por eso el firmware lo deja escrito en la flash (`GtfwDescriptor` en el
 * sketch). La app lo lee de dos sitios con el mismo formato:
 *  - del .hex, buscando la marca "GTFW" ([find]), para decir antes de subirlo que trae;
 *  - de la placa, con el comando CFG ([fromCfgLine]), para decir que cambia.
 *
 * Formato de la version 1, sin relleno: "GTFW", version, hardware\0, firmware\0, fecha\0,
 * u16 firma del log, u16 configuracion de la TMP119, u8 CONT, u8 x4 alimentacion de A0..A3,
 * u16 x4 espera de A0..A3 en ms, nombre de A0..A3 cada uno con \0. Little-endian.
 */
data class FirmwareDescriptor(
    val layout: Int,
    /** Tipo y revision de hardware: "GT001". Es lo unico que decide si se puede subir. */
    val hardware: String,
    val firmware: String,
    /** __DATE__ del compilador: "Oct  7 2026". */
    val buildDate: String,
    val signature: Int,
    val tmp119Config: Int,
    val cont: Boolean,
    /** Por canal A0..A3: mascara de pines que lo alimentan (bit 0 = A0 .. bit 3 = A3). */
    val power: List<Int>,
    val settleMs: List<Int>,
    val names: List<String>,
) {
    /** Si el canal An se registra, segun la firma del log. */
    fun logs(channel: Int): Boolean = signature and (0x0020 shl channel) != 0

    /** Pines que esta compilacion pone como salidas de alimentacion. */
    val powerPins: Int get() = power.fold(0) { a, b -> a or b }

    /** Promedios de la TMP119 segun los bits AVG del registro de configuracion. */
    val tmp119Averages: Int get() = when ((tmp119Config shr 5) and 3) { 0 -> 1; 1 -> 8; 2 -> 32; else -> 64 }

    companion object {
        const val MAGIC = "GTFW"
        const val LAYOUT = 1

        /** Hardware para el que esta app sabe subir firmware. */
        val SUPPORTED_HARDWARE = setOf("GT001")

        /**
         * Lee un descriptor que empieza en [at]. Null si no es uno valido: la marca podria
         * aparecer por casualidad en otro sitio del binario, asi que se exige que todo calce
         * (version conocida, textos imprimibles y terminados, firma con version de log valida).
         */
        fun parse(b: ByteArray, at: Int = 0): FirmwareDescriptor? = runCatching {
            if (at < 0 || at + 5 > b.size) return null
            if ((0 until 4).any { b[at + it].toInt().toChar() != MAGIC[it] }) return null
            val layout = b[at + 4].toInt() and 0xFF
            if (layout != LAYOUT) return null
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
            fun u8(): Int = b[p++].toInt() and 0xFF
            fun u16(): Int { val v = u8(); return v or (u8() shl 8) }
            val hw = str(16) ?: return null
            val fw = str(16) ?: return null
            val date = str(16) ?: return null
            val sig = u16()
            val tmp = u16()
            val cont = u8()
            val power = List(4) { u8() }
            val settle = List(4) { u16() }
            val names = List(4) { str(40) ?: return null }
            if (!LogFormat.isSupported(sig) || cont > 1 || power.any { it > 0x0F }) return null
            FirmwareDescriptor(layout, hw, fw, date, sig, tmp, cont == 1, power, settle, names)
        }.getOrNull()

        /** Busca el descriptor en una imagen de flash (de un .hex). Null si no lo trae. */
        fun find(flash: ByteArray): FirmwareDescriptor? {
            var i = 0
            while (true) {
                i = indexOf(flash, i) ?: return null
                parse(flash, i)?.let { return it }
                i++
            }
        }

        /** Lee la respuesta de CFG: "CFG <hex>". Null si no hay o no es valida. */
        fun fromCfgLine(text: String): FirmwareDescriptor? {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("CFG ") }
                ?: return null
            val hex = line.removePrefix("CFG ").trim()
            if (hex.length % 2 != 0) return null
            val bytes = ByteArray(hex.length / 2) {
                (hex.substring(2 * it, 2 * it + 2).toIntOrNull(16) ?: return null).toByte()
            }
            return parse(bytes, 0)
        }

        private fun indexOf(b: ByteArray, from: Int): Int? {
            val m = MAGIC.map { it.code.toByte() }
            for (i in from..b.size - m.size) {
                if (b[i] == m[0] && b[i + 1] == m[1] && b[i + 2] == m[2] && b[i + 3] == m[3]) return i
            }
            return null
        }

        /**
         * Por que NO se puede subir [file] a una placa de hardware [boardHardware], o null si se
         * puede. Es la unica regla que bloquea: todo lo demas --otros canales, otros pines-- se
         * permite y se explica con [describe] y [changes]. [boardHardware] sale del
         * identificador corto de INFO ("GT001-1B4237" -> "GT001"); null si no se conoce.
         */
        fun hardwareProblem(file: FirmwareDescriptor?, boardHardware: String?): String? =
            hardwareProblemOf(file?.hardware, boardHardware)

        /**
         * Lo mismo a partir del hardware que declara el archivo, venga del descriptor del logger
         * (GTFW) o del de diagnostico ([DiagnosticDescriptor], GTDG). [fileHardware] null: el
         * archivo no trae ninguno de los dos.
         */
        fun hardwareProblemOf(fileHardware: String?, boardHardware: String?): String? = when {
            fileHardware == null ->
                "This file has no GlacierTemp build descriptor (logger 3.12 or diagnostics 1.6 and " +
                "later), so the app cannot check what hardware it is for. Upload it with the " +
                "Arduino IDE instead."
            fileHardware !in SUPPORTED_HARDWARE ->
                "This firmware is for hardware $fileHardware, not a GlacierTemp this app supports."
            boardHardware != null && boardHardware != fileHardware ->
                "This firmware is for $fileHardware, but the board is $boardHardware."
            else -> null
        }

        /** "GT001" de un identificador corto "GT001-1B4237". */
        fun hardwareOf(shortId: String?): String? = shortId?.substringBefore('-')?.takeIf { it.isNotEmpty() }

        /** "A1, A2 and A3" para una mascara de pines. */
        fun pinList(mask: Int): String {
            val p = (0..3).filter { mask and (1 shl it) != 0 }.map { "A$it" }
            return when (p.size) {
                0 -> "none"
                1 -> p[0]
                else -> p.dropLast(1).joinToString(", ") + " and " + p.last()
            }
        }

        /**
         * Lo que el firmware va a hacer, en frases para quien decide subirlo. Va primero lo que
         * puede danar algo: los pines que pasan a ser salidas de alimentacion.
         */
        fun describe(d: FirmwareDescriptor): List<String> = buildList {
            add("Firmware ${d.firmware} for GlacierTemp ${d.hardware}, built ${d.buildDate.replace("  ", " ")}.")
            for (ch in 0..3) {
                val pins = d.power[ch]
                if (pins == 0) continue
                add("${pinList(pins)} become POWER OUTPUTS for the sensor on A$ch " +
                    "(read ${d.settleMs[ch]} ms after switching it on). Do not connect anything " +
                    "else to ${if (pins.countOneBits() == 1) "that pin" else "those pins"}.")
            }
            val canales = LogFormat.fields(d.signature).map { f ->
                when {
                    f.name == "HAtemp" -> "high-accuracy temperature (TMP119, ${d.tmp119Averages} averages)"
                    f.name.length == 2 && f.name[0] == 'A' && f.name[1] in '0'..'3' -> {
                        val ch = f.name[1] - '0'
                        val nombre = d.names[ch]
                        if (nombre == f.name) "analog input ${f.name}" else "analog input ${f.name} \"$nombre\""
                    }
                    else -> LogFormat.channelDescription(f.name).replaceFirstChar { it.lowercase() }
                }
            }
            add("Records: ${canales.joinToString(", ")} (${LogFormat.recordBytes(d.signature)} bytes per record).")
            add(if (d.cont) "Continuous capture (CONT) is included."
                else "Continuous capture (CONT) is not included in this build.")
        }

        /**
         * Lo que cambia respecto de la placa. [board] null cuando la placa no tiene descriptor
         * (firmware anterior a 3.12): entonces solo se puede comparar la firma del log, que
         * viene en INFO ([boardSignature]). [boardRecords] decide si el cambio de canales
         * deja algo sin descargar.
         */
        fun changes(board: FirmwareDescriptor?, boardSignature: Int?, boardRecords: Long,
                    file: FirmwareDescriptor): List<String> = buildList {
            if (board != null) {
                if (board.firmware != file.firmware) add("Firmware ${board.firmware} → ${file.firmware}.")
                for (ch in 0..3) {
                    if (board.power[ch] != file.power[ch]) {
                        add("Power for A$ch: ${pinList(board.power[ch])} → ${pinList(file.power[ch])}.")
                    }
                    if (board.logs(ch) && file.logs(ch) && board.names[ch] != file.names[ch]) {
                        add("A$ch renamed: \"${board.names[ch]}\" → \"${file.names[ch]}\".")
                    }
                }
                if (board.cont != file.cont) add(if (file.cont) "Adds continuous capture." else "Removes continuous capture.")
            }
            val antes = board?.signature ?: boardSignature
            if (antes != null && (antes and 0x0FFF) != (file.signature and 0x0FFF)) {
                val a = LogFormat.fields(antes).map { it.name }.toSet()
                val n = LogFormat.fields(file.signature).map { it.name }.toSet()
                (n - a).forEach { add("Starts recording $it.") }
                (a - n).forEach { add("Stops recording $it.") }
                if (boardRecords > 0) {
                    add("The $boardRecords records on the board were written with other channels: " +
                        "logging stays suspended until you download them and reset the counter (RC).")
                }
            }
            if (board == null) add("The board runs a firmware without build descriptor: only the log channels can be compared.")
        }
    }
}
