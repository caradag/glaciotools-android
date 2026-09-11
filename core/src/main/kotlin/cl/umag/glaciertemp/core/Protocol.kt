package cl.umag.glaciertemp.core

/**
 * Comandos que entiende el dispatcher del firmware. Cada comando se envia terminado en
 * '\n', que es lo que espera Serial.readBytesUntil('\n', ...).
 */
object Protocol {

    const val TERMINATOR = "\n"

    // Comandos existentes en el firmware actual.
    const val HELP = "H"
    /** Bloque de informacion para leer con los ojos. */
    const val INFO_HUMAN = "I"
    const val MEASURE = "M"
    const val TIME = "TIME"
    const val RESET_COUNTER = "RC"
    const val GPS = "GPS"
    const val LOG_ALIGNED = "LOG"
    const val LOG_CSV = "LOGC"
    const val LOG_HEX = "LOGH"

    /**
     * Volcado crudo. [bytes] 0 pide la memoria entera; [fastBaud] 0 deja la velocidad de
     * siempre. Se piden juntos porque el firmware los lee como una sola lista.
     */
    fun logHex(bytes: Long = 0, fastBaud: Int = 0): String =
        if (fastBaud > 0) "$LOG_HEX=$bytes,$fastBaud" else "$LOG_HEX=$bytes"

    // Comandos que agrega este proyecto (ver seccion 5 del plan).
    /**
     * Cabecera de metadatos legible por MAQUINA (F4). No confundir con [INFO_HUMAN]:
     * "I" imprime un bloque para personas y "INFO" una linea de campos fijos que la app
     * parsea. Atar la constante equivocada da un fallo confuso ("no respondio a INFO").
     */
    /**
     * Version MINIMA de protocolo con la que esta app habla. No se mantiene compatibilidad
     * hacia atras: toda la flota se actualiza al firmware nuevo, y sostener dos caminos de
     * codigo de los que solo uno se ejerce a diario cuesta mas que actualizar las placas.
     * El 3 trae el identificador GT001-XXXXXX y el control de flujo durante LOGH.
     */
    const val MIN_PROTOCOL = 4

    const val METADATA = "INFO"
    const val LOG_BINARY = "LOGB"
    const val BOARD_ID = "ID"
    const val VERSION = "VER"
    const val MEMORY_LIFETIME = "CALC"

    fun line(command: String): String = command + TERMINATOR

    /**
     * LOGB completo, o por rango de registro cuando se pasan los indices. Con [fastBaud]
     * se pide que los BLOQUES viajen a esa velocidad; el texto sigue en la de la consola.
     */
    fun logBinary(from: Long? = null, to: Long? = null, fastBaud: Int = 0): String = when {
        from == null || to == null -> line(LOG_BINARY)
        fastBaud > 0 -> line("$LOG_BINARY=$from,$to,$fastBaud")
        else -> line("$LOG_BINARY=$from,$to")
    }

    fun setVariable(spec: VariableSpec, value: Long): String = line(spec.writeCommand(value))

    /**
     * Ajuste del reloj CON segundos. El firmware acepta tambien la forma sin ellos, pero
     * sincronizar contra el telefono sin segundos arrastraria hasta 59 s de error, que es
     * mucho mas que la deriva que se pretende corregir.
     */
    fun setTime(t: java.time.LocalDateTime): String =
        line("TIME=" + java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").format(t))
    fun getVariable(spec: VariableSpec): String = line(spec.readCommand())
}
