package cl.umag.glaciertemp.core

/**
 * Captura continua (CONT, firmware 3.10, protocolo 6): la placa mide y graba sin pausa
 * entre registros, para perfiles verticales desde un dron.
 *
 * Mientras captura, la placa solo atiende `CONT OFF` y `CONT?`; todo lo demas lo ignora,
 * incluido INFO. Por eso la app no puede tratar el silencio a INFO como "placa dormida" sin
 * preguntar antes si esta capturando: en vuelo el Bluetooth se cae y al aterrizar hay que
 * poder reconectar y pararla.
 *
 * Las lineas, tal como las emite la placa:
 *
 *     CONT begin heater=on rec=16
 *     CONT n=8412 t=1203s
 *     CONT end reason=stop n=8412 dur=1203456ms mean=143ms max=402ms drift=-4ms heater=on
 *     CONT needs empty log: download & RC
 *     CONT idle
 */
object ContCapture {

    /** Protocolo a partir del cual la placa entiende CONT. */
    const val PROTOCOL = 6

    const val ON = "CONT ON"
    const val ON_HEATER = "CONT ON+H"
    const val OFF = "CONT OFF"
    const val QUERY = "CONT?"

    /** La placa cada 10 s escribe una linea de estado; el plazo de la app para darla por viva. */
    const val STATUS_EVERY_S = 10

    data class Begin(val heater: Boolean, val recordBytes: Int)

    data class Status(val records: Long, val seconds: Long)

    data class End(
        val reason: String,
        val records: Long,
        val durationMs: Long,
        val meanMs: Long,
        val maxMs: Long,
        val driftMs: Long,
        val heater: Boolean,
    )

    private val BEGIN = Regex("""CONT begin heater=(on|off) rec=(\d+)""")
    private val STATUS = Regex("""CONT n=(\d+) t=(\d+)s""")
    private val END = Regex(
        """CONT end reason=(\w+) n=(\d+) dur=(\d+)ms mean=(\d+)ms max=(\d+)ms """ +
        """drift=(-?\d+)ms heater=(on|off)""")

    fun parseBegin(text: String): Begin? = BEGIN.find(text)?.let {
        Begin(it.groupValues[1] == "on", it.groupValues[2].toInt())
    }

    /** El ULTIMO estado del texto: si llegaron varios, el que vale es el mas reciente. */
    fun parseStatus(text: String): Status? = STATUS.findAll(text).lastOrNull()?.let {
        Status(it.groupValues[1].toLong(), it.groupValues[2].toLong())
    }

    fun parseEnd(text: String): End? = END.find(text)?.let {
        val g = it.groupValues
        End(g[1], g[2].toLong(), g[3].toLong(), g[4].toLong(), g[5].toLong(), g[6].toLong(),
            g[7] == "on")
    }

    /** Firmware 3.15 acorto el texto; se aceptan los dos para placas sin actualizar. */
    fun needsEmptyLog(text: String): Boolean =
        text.contains("CONT needs empty log") || text.contains("CONT needs an empty log")

    fun isIdle(text: String): Boolean = text.contains("CONT idle")

    /** Valores por muestra de la linea LIVE que acompana al estado, segun el tamano del registro. */
    fun valuesPerRecord(recordBytes: Int): Int =
        (recordBytes - LogFormat.TIMESTAMP_BYTES_CONT) / 2

    fun reasonText(reason: String): String = when (reason) {
        "stop" -> "stopped on request"
        "time" -> "reached the one-hour limit"
        "full" -> "memory full"
        "battery" -> "battery critical"
        else -> reason
    }

    /** Duracion legible: "20 min 03 s", "45 s". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60} min ${(s % 60).toString().padStart(2, '0')} s" else "$s s"
    }

    /** El resumen que se ensena al parar: lo que hay que saber del vuelo en una linea. */
    fun summary(e: End): String = buildString {
        append("${e.records} records in ${duration(e.durationMs)}")
        if (e.records > 1) append(" · every ${e.meanMs} ms on average, longest gap ${e.maxMs} ms")
        append(" · clock drift ${if (e.driftMs > 0) "+" else ""}${e.driftMs} ms")
        append(" · heater ${if (e.heater) "on" else "off"}")
        append(" · ${reasonText(e.reason)}")
    }
}
