package cl.umag.glaciertemp.transport

/**
 * Parametros de una linea serie, como en cualquier terminal serie: para hablar en la
 * conexion serie de la app con otros dispositivos ademas de la GlacierTemp.
 *
 * Los valores por defecto son los de la GlacierTemp: 115200 baudios, 8N1 y las ordenes
 * terminadas en LF (Serial.readBytesUntil('\n') en el firmware).
 */
data class SerialSettings(
    val baud: Int = DEFAULT_BAUD,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: StopBits = StopBits.ONE,
    val lineEnding: LineEnding = LineEnding.LF,
) {
    enum class Parity(val label: String) { NONE("None"), ODD("Odd"), EVEN("Even"), MARK("Mark"), SPACE("Space") }
    enum class StopBits(val label: String) { ONE("1"), ONE_POINT_FIVE("1.5"), TWO("2") }
    enum class LineEnding(val label: String, val bytes: String) {
        NONE("None", ""), LF("LF (\\n)", "\n"), CR("CR (\\r)", "\r"), CRLF("CR+LF (\\r\\n)", "\r\n"),
    }

    /** "115200 8N1": como lo escriben los terminales serie. */
    val summary: String get() = "$baud $dataBits${parity.name.first()}${stopBits.label}"

    val isGlacierTemp: Boolean get() = this == GLACIERTEMP

    companion object {
        const val DEFAULT_BAUD = 115200
        val GLACIERTEMP = SerialSettings()
        val COMMON_BAUDS = listOf(300, 1200, 2400, 4800, 9600, 14400, 19200, 28800, 38400,
            57600, 76800, 115200, 230400, 250000, 460800, 500000, 921600, 1000000)
        val DATA_BITS = listOf(5, 6, 7, 8)
    }
}

/**
 * Un enlace cuyos parametros de linea se pueden cambiar en vivo. Solo el cable: por Bluetooth
 * los fija el modulo, y la conexion de depuracion es un socket. Interfaz aparte, como
 * [BaudSwitchable], para que pedirselo a un enlace que no puede sea imposible.
 */
interface SerialConfigurable {
    fun applySettings(settings: SerialSettings)
}
