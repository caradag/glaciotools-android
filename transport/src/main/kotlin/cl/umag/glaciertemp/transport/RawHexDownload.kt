package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.Protocol

/** Avance de un volcado crudo. [bytes] son los de LINEA, no los de dato. */
data class RawHexProgress(
    val bytesReceived: Long,
    val bytesExpected: Long,
    val elapsedMs: Long,
) {
    val fraction: Double
        get() = if (bytesExpected > 0)
            (bytesReceived.toDouble() / bytesExpected).coerceAtMost(1.0) else 0.0
    val bytesPerSecond: Double
        get() = if (elapsedMs > 0) bytesReceived * 1000.0 / elapsedMs else 0.0

    /**
     * Segundos que faltan, o NaN mientras no haya con que estimarlos.
     *
     * Se calcula con el ritmo MEDIO de lo que va llegando y no con una velocidad teorica de
     * linea: por BLE el ritmo real no se parece al nominal, y en un volcado de media hora un
     * numero inventado es peor que ninguno. NaN hasta el primer segundo, que es cuando la
     * media empieza a significar algo.
     */
    val secondsRemaining: Double
        get() {
            val bps = bytesPerSecond
            if (elapsedMs < 1000 || bps <= 0 || bytesExpected <= 0) return Double.NaN
            return ((bytesExpected - bytesReceived).coerceAtLeast(0) / bps)
        }
}

/** Resultado de un volcado crudo: lo que se escribio y lo que hubo que descartar. */
data class RawHexResult(
    val bytesWritten: Long,
    val recordsWritten: Long,
    val recordsRejected: Long,
    val sawEndOfFile: Boolean,
)

/**
 * Volcado de la memoria entera como Intel HEX (comando LOGH), escrito SEGUN LLEGA.
 *
 * Tres decisiones que no son evidentes:
 *
 * **Se escribe incrementalmente y no se acumula en memoria.** El volcado completo son diez
 * megas de datos y unos veintiocho de linea en Intel HEX; un telefono no tiene por que
 * poder juntarlos en un array, y una descarga interrumpida a los veinte minutos no puede
 * perderse entera.
 *
 * **Se frena al emisor con XOFF/XON.** El firmware atiende la pausa cada 256 bytes de dato
 * durante LOGH. Sin eso, un puente BLE que entrega mas despacio de lo que la UART emite
 * descarta bytes en silencio, y en Intel HEX eso son registros con el checksum roto.
 *
 * **Los registros malos se cuentan, no se ocultan.** Intel HEX no tiene reintento por
 * bloque como LOGB: cada registro lleva su checksum y eso permite DETECTAR el dano, no
 * repararlo. Devolver cuantos se descartaron es la diferencia entre un fichero incompleto y
 * un fichero incompleto que nadie sabe que lo esta.
 */
object RawHexDownload {

    private const val XON: Byte = 0x11
    private const val XOFF: Byte = 0x13

    /** Se pide la pausa cuando el buffer sin procesar pasa de aqui. */
    const val PAUSE_ABOVE_BYTES = 64 * 1024

    /** Y se reanuda al bajar de aqui. La histeresis evita un XOFF/XON por cada lectura. */
    const val RESUME_BELOW_BYTES = 16 * 1024

    /** Silencio tras el cual se da por terminado si ya llego el fin de fichero. */
    private const val QUIET_MS = 1500

    /**
     * [sink] recibe cada trozo de linea ya validado. [expectedBytes] solo alimenta el
     * progreso: sale de `flash=` de INFO y es una cota, no una promesa.
     */
    fun download(
        session: DeviceSession,
        transport: Transport,
        expectedBytes: Long,
        sink: (ByteArray) -> Unit,
        onProgress: (RawHexProgress) -> Unit = {},
        onDiagnostic: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        /** Velocidad para los registros Intel HEX; 0 deja la de siempre. */
        fastBaud: Int = 0,
        /** A la que hay que volver al terminar. */
        normalBaud: Int = 115200,
    ): RawHexResult {
        // Solo se pide si el enlace SABE cambiar de velocidad. Por BLE la fija el modulo por
        // su lado: pedirla dejaria a la placa emitiendo a 230400 contra un modulo que sigue
        // en 115200, y no llegaria nada legible.
        val switcher = transport as? BaudSwitchable
        val fast = if (switcher != null && fastBaud > 0) fastBaud else 0
        var switched = false
        fun restore() {
            if (switched) { switcher!!.setBaudRate(normalBaud); switched = false }
        }

        transport.writeLine(Protocol.logHex(0, fast))

        val started = System.currentTimeMillis()
        var received = 0L
        var written = 0L
        var records = 0L
        var rejected = 0L
        var sawEof = false
        var paused = false
        var idle = 0
        // Lo que queda de una linea partida entre dos lecturas. El troceo del transporte no
        // respeta los saltos de linea, asi que un registro puede llegar en dos pedazos.
        val pending = StringBuilder()

        fun flushLines(last: Boolean) {
            var cut = pending.lastIndexOf("\n")
            if (last) cut = pending.length - 1
            if (cut < 0) return
            val block = pending.substring(0, cut + 1)
            pending.delete(0, cut + 1)
            val good = StringBuilder()
            for (line in block.lineSequence()) {
                val t = line.trim()
                if (!t.startsWith(":")) continue          // preambulo y ruido
                records++
                if (!isValidRecord(t)) {
                    rejected++
                    continue
                }
                if (t.uppercase() == ":00000001FF") {
                    sawEof = true
                    // El fin de fichero es la senal de vuelta que emite el firmware justo
                    // antes de bajar la velocidad. Hacerlas coincidir evita tener que
                    // deducirlo contando bytes.
                    restore()
                }
                good.append(t).append('\n')
            }
            if (good.isNotEmpty()) {
                val bytes = good.toString().toByteArray()
                sink(bytes)
                written += bytes.size
            }
        }

        while (idle < QUIET_MS) {
            if (isCancelled()) {
                flushLines(last = true)
                restore()
                onDiagnostic("Raw log download cancelled after $written bytes")
                return RawHexResult(written, records, rejected, sawEof)
            }
            val chunk = transport.read(100)
            if (chunk.isEmpty()) {
                idle += 100
                if (sawEof && pending.isEmpty()) break
                continue
            }
            idle = 0
            received += chunk.size
            pending.append(String(chunk, Charsets.ISO_8859_1))

            // La cabecera anuncia la velocidad y llega a la de siempre; se cambia en cuanto
            // termina esa linea, que es exactamente donde la cambia el firmware.
            if (fast > 0 && !switched && pending.contains("Intel HEX follows") &&
                pending.indexOf('\n', pending.indexOf("Intel HEX follows")) >= 0) {
                switcher!!.setBaudRate(fast)
                switched = true
                onDiagnostic("Raw log: line speed raised to $fast baud")
            }

            // Se frena ANTES de procesar: si el buffer pendiente ya crecio, seguir leyendo
            // sin pedir la pausa es exactamente lo que desborda al modulo.
            if (!paused && pending.length > PAUSE_ABOVE_BYTES) {
                transport.write(byteArrayOf(XOFF))
                paused = true
                onDiagnostic("Asked the board to pause (${pending.length} bytes pending)")
            }
            flushLines(last = false)
            if (paused && pending.length < RESUME_BELOW_BYTES) {
                transport.write(byteArrayOf(XON))
                paused = false
            }
            onProgress(RawHexProgress(received, expectedBytes,
                                      System.currentTimeMillis() - started))
            if (sawEof) break
        }
        flushLines(last = true)
        if (paused) transport.write(byteArrayOf(XON))
        // Si el fin de fichero nunca llego --volcado cortado, placa dormida-- la linea se
        // habria quedado en la velocidad rapida y el siguiente comando saldria ilegible.
        restore()

        if (!sawEof) {
            onDiagnostic("Raw log: the end-of-file record never arrived; " +
                         "the file is incomplete")
        }
        if (rejected > 0) {
            onDiagnostic("Raw log: $rejected of $records records had a bad checksum " +
                         "and were dropped")
        }
        return RawHexResult(written, records, rejected, sawEof)
    }

    /**
     * Un registro Intel HEX es valido si su longitud declarada cuadra y la suma de todos
     * sus bytes, checksum incluido, es cero modulo 256.
     */
    fun isValidRecord(line: String): Boolean {
        if (line.length < 11 || line.length % 2 == 0) return false
        val body = line.substring(1)
        val bytes = ByteArray(body.length / 2)
        for (i in bytes.indices) {
            val v = body.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return false
            bytes[i] = v.toByte()
        }
        val declared = bytes[0].toInt() and 0xFF
        if (bytes.size != declared + 5) return false
        var sum = 0
        for (b in bytes) sum += b.toInt() and 0xFF
        return (sum and 0xFF) == 0
    }
}
