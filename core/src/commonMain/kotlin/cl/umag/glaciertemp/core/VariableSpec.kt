package cl.umag.glaciertemp.core

/**
 * Descriptor declarativo de las variables configurables. Los nombres salen de
 * varComm[]="INTLVMTZNADJMSW", los tipos de varTypes[]="Ubiub", las longitudes de
 * varLengths[]={4,1,2,2,1} y los rangos de varLimits() en EEPROM.ino.
 *
 * El formulario de la app se genera de esta tabla: anadir una variable al firmware
 * es anadir una fila aqui, no una pantalla nueva.
 */
enum class VarType { UNSIGNED_LONG, BYTE, INT }

data class VariableSpec(
    val code: String,          // las tres letras del comando
    val label: String,
    val type: VarType,
    val bytes: Int,
    val min: Long?,            // null = el firmware no define rango
    val max: Long?,
    val unit: String = "",
    val help: String = "",
) {
    fun validate(value: Long): String? = when {
        min != null && value < min -> "Minimum is $min"
        max != null && value > max -> "Maximum is $max"
        else -> null
    }

    /** Comando de lectura: las tres letras solas. */
    fun readCommand(): String = code

    companion object {
        /**
         * Extrae el valor de la respuesta de la placa.
         *
         * displayVars() no imprime el codigo del comando sino el ROTULO que el initializer
         * grabo en la EEPROM: pedir "INT" devuelve
         * "Interval between measurements (sec): 600". Buscar "INT:" no encontraba nada y la
         * app mostraba "?" en todos los campos contra una placa real, aunque escribir si
         * funcionara.
         *
         * Se busca el numero tras el ultimo ':' y no se mira el rotulo, porque quien llama
         * ya sabe que variable pidio y el texto del rotulo vive en la EEPROM de cada placa.
         */
        private val VALUE = Regex(""":\s*(-?\d+)\s*$""", RegexOption.MULTILINE)

        fun parseValue(reply: String): String? =
            reply.lineSequence()
                .mapNotNull { VALUE.find(it)?.groupValues?.get(1) }
                // La PRIMERA y no la ultima: la placa contesta con el valor y despues puede
                // anadir lineas propias. Una placa real responde a INT con
                //   Interval between measurements (sec): 2
                //   Next Wakeup: 2026-09-05 00:03:10 (UTC-3)
                // y esa segunda linea solo se salva de coincidir porque termina en "(UTC-3)".
                // Sin la zona horaria terminaria en ":10" y el intervalo pasaria a valer 10.
                .firstOrNull()
    }

    /** Comando de escritura: TRES=valor, tal como lo espera isVarCommand(). */
    fun writeCommand(value: Long): String = "$code=$value"
}

/**
 * Unidad con la que se escribe un intervalo. La placa siempre habla en segundos; esto solo
 * evita que quien quiera medir cada seis horas tenga que teclear 21600 y contar ceros.
 */
enum class TimeUnit(val label: String, val short: String, val seconds: Long) {
    SECONDS("seconds", "s", 1),
    MINUTES("minutes", "m", 60),
    HOURS("hours", "h", 3600);

    fun toSeconds(value: Long): Long = value * seconds


}

object Variables {
    // Los limites replican varLimits(); las constantes MAX_* viven en el firmware y se
    // confirman contra la cabecera de metadatos (F4) cuando la placa la reporte.
    val ALL = listOf(
        VariableSpec("INT", "Measurement interval", VarType.UNSIGNED_LONG, 4,
            1, null, "s", "Seconds between measurements"),
        VariableSpec("LVM", "Low-battery multiplier", VarType.BYTE, 1,
            1, null, "x", "Multiplies the interval while the battery is low"),
        VariableSpec("TZN", "Time zone", VarType.INT, 2,
            null, null, "h", "Offset from UTC"),
        VariableSpec("ADJ", "RTC adjust interval", VarType.INT, 2,
            1, null, "d", "How often the clock is corrected"),
        VariableSpec("MSW", "Message frequency", VarType.BYTE, 1,
            0, null, "d", "Days between Iridium messages; 0 disables them"),
    )

    fun byCode(code: String): VariableSpec? =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }
}
