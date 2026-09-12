package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.*

/** Metadatos que la placa reporta en la cabecera legible por maquina (comando INFO). */
data class DeviceInfo(
    val firmware: String, val protocol: Int, val boardId: String,
    val signature: Int, val recordBytes: Int, val recordCount: Long, val flashBytes: Long,
    /** Velocidad de la consola, y la que el volcado binario puede pedir; 0 si no la ofrece. */
    val baud: Int = 0, val fastBaud: Int = 0,
    /**
     * Forma corta del identificador: 32 bits derivados de los 64 de fabrica. La calcula la
     * PLACA y no la app; deducirla aqui seria una segunda implementacion del mismo CRC
     * esperando a divergir de la primera.
     */
    val shortId: String = "",
) {
    /**
     * Se decide leyendo la placa y no por el numero de version: la cabecera se describe a si
     * misma, igual que el resto del formato. Un firmware que no ofrezca fastbaud no lo
     * anuncia, y entonces no se le pide.
     */
    /** Lo mas corto que identifica la placa sin ambiguedad; cae al completo si no hay. */
    val displayId: String get() = shortId.ifEmpty { boardId }

    val supportsFastDump: Boolean get() = fastBaud > 0 && fastBaud != baud
    companion object {
        private val FIELD = Regex("""(\w+)=(\S+)""")
        fun parse(text: String): DeviceInfo? {
            val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("INFO ") }
                ?: return null
            val f = FIELD.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            return DeviceInfo(
                firmware = f["fw"] ?: return null,
                protocol = f["proto"]?.toIntOrNull() ?: return null,
                boardId = f["id"] ?: return null,
                signature = f["sig"]?.removePrefix("0x")?.toIntOrNull(16) ?: return null,
                recordBytes = f["rec"]?.toIntOrNull() ?: return null,
                recordCount = f["count"]?.toLongOrNull() ?: return null,
                flashBytes = f["flash"]?.toLongOrNull() ?: 0L,
                baud = f["baud"]?.toIntOrNull() ?: 0,
                fastBaud = f["fastbaud"]?.toIntOrNull() ?: 0,
                shortId = f["sid"].orEmpty(),
            )
        }
    }
}

/**
 * Avance de una descarga, medido en REGISTROS y sobre el total.
 *
 * Antes contaba bloques de la peticion en curso, y con tramos cortos cada peticion cabe en
 * un solo bloque: el indicador decia "1/1" de principio a fin. Y el ritmo se reiniciaba en
 * cada tramo, asi que marcaba 0,0 kB/s casi siempre. El registro es ademas la unidad en la
 * que uno piensa; el bloque es un detalle del protocolo.
 */
/** Aborto pedido por el usuario; lleva cuantos registros alcanzaron a bajarse. */
class DownloadCancelled(val recordsDone: Long) : Exception("Download cancelled")

data class DownloadProgress(
    val recordsDone: Long, val recordsTotal: Long,
    val bytesDone: Long, val bytesTotal: Long,
    /** Desde que empezo la descarga ENTERA, no el tramo. */
    val elapsedSeconds: Double,
    /** Tamano de tramo en uso; cambia si el ajuste automatico lo mueve. */
    val recordsPerRequest: Int = 0,
) {
    val fraction: Float
        get() = if (recordsTotal == 0L) 0f else recordsDone.toFloat() / recordsTotal

    val recordsPerSecond: Double
        get() = if (elapsedSeconds <= 0) 0.0 else recordsDone / elapsedSeconds

    /** Estimacion con el ritmo medido en la propia transferencia, no con un supuesto. */
    val secondsRemaining: Double
        get() = if (recordsPerSecond <= 0) Double.NaN
                else (recordsTotal - recordsDone) / recordsPerSecond
}

/**
 * Conversacion con la placa sobre un [Transport]. Cable y BLE hablan el mismo protocolo,
 * asi que esta clase no sabe cual de los dos hay debajo.
 */
class DeviceSession(private val transport: Transport) {

    /**
     * Corta una descarga larga. Lo pone el hilo de la interfaz y lo lee el de la descarga,
     * de ahi el @Volatile.
     *
     * Se consulta tanto entre tramos como DENTRO del bucle que lee la linea. Solo entre
     * tramos no bastaba: por cable `recordsPerRequest` vale 0, el troceo se salta entero y
     * la descarga completa ocurre dentro de una sola llamada, con lo que el boton de abortar
     * no tenia ningun efecto.
     *
     * Cortar a mitad de un bloque deja bytes sueltos en el enlace --que era el motivo
     * original de mirar solo entre tramos-- pero eso ya no importa: la cola se drena al
     * cerrar la operacion y tambien antes de mandar el siguiente comando, asi que no hay
     * forma de que se lean como respuesta de otra cosa.
     */
    @Volatile
    var cancelled: Boolean = false

    /** Tamano de tramo con el que empezo y con el que acabo la ultima descarga. */
    var initialChunk: Int = 0; private set
    var finalChunk: Int = 0; private set

    companion object {
        /** Solo como respaldo: la placa siempre declara la suya en la cabecera INFO. */
        const val DEFAULT_BAUD = 115200

        /**
         * Mas bloques perdidos que esto y no se reintenta: no es un fallo puntual sino un
         * enlace que no da abasto, y pedirlos de uno en uno solo alarga el silencio.
         */
        const val MAX_RETRYABLE_BLOCKS = 8

        /** Por debajo de esto el problema ya no es el tamano del tramo. */
        const val MIN_RECORDS_PER_REQUEST = 4

        /** Tramos seguidos sin incidencias antes de tantear un tamano mayor. */
        const val GROW_AFTER_CHUNKS = 4

        /**
         * Tope al que se tantea. Mas alla el coste fijo por peticion ya es despreciable
         * frente a los datos, asi que subir mas no compensa arriesgar un fallo.
         */
        const val MAX_RECORDS_PER_REQUEST = 512

        /** Silencio que se tolera una vez que la placa empezo a contestar. */
        const val IDLE_AFTER_HEADER_MS = 1200

        /** Silencio que se tolera cuando no ha llegado nada: la orden puede seguir en vuelo. */
        const val IDLE_NO_ANSWER_MS = 1500

        /** CAN de ASCII: "cancela lo que estas haciendo". Lo atiende el firmware 3.1+. */
        const val CANCEL: Byte = 0x18

        /** Como dice la placa que dejo de volcar. */
        val ACUSES = listOf("LOGB aborted", "LOGH aborted")

        /**
         * Tope de la limpieza tras abortar. Con firmware 3.1 la placa para en milisegundos y
         * no se llega ni de lejos; con uno anterior hay que tragarse lo que queda, y esto
         * acota cuanto se espera antes de rendirse y decirlo.
         */
        const val ABORT_DRAIN_MS = 8000
    }

    /**
     * Un solo lector a la vez sobre el enlace.
     *
     * Hace falta desde que el terminal escucha de forma continua: si el lector de reposo y
     * una operacion leyeran a la vez, el primero se quedaria con los primeros bytes de la
     * respuesta y la operacion los daria por perdidos. Con el cerrojo eso no puede pasar --
     * el lector de reposo solo lee cuando no hay operacion en curso, y los bytes que
     * recoge son por definicion no solicitados.
     *
     * Reentrante a proposito: `download` toma el cerrojo y por dentro llama a `exchange`,
     * que lo vuelve a tomar.
     */
    private val cerrojo = java.util.concurrent.locks.ReentrantLock(/* fair = */ true)

    private inline fun <T> conElEnlace(bloque: () -> T): T {
        cerrojo.lock()
        try { return bloque() } finally { cerrojo.unlock() }
    }

    /**
     * Lectura para el lector de REPOSO del terminal: lo que la placa diga por su cuenta.
     *
     * Devuelve vacio sin esperar si hay una operacion en curso. Insistir en el cerrojo
     * dejaria al lector de reposo compitiendo con la descarga por cada trozo.
     */
    fun leerEnReposo(timeoutMs: Int): ByteArray {
        // tryLock(0, ...) y NO tryLock(): el segundo se cuela por delante de quien ya esta
        // esperando --esta documentado, y ocurre incluso con el cerrojo declarado justo--
        // asi que un lector de reposo insistiendo puede dejar a una operacion esperando
        // indefinidamente. La version con plazo respeta la cola.
        if (!cerrojo.tryLock(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return ByteArray(0)
        }
        try {
            return transport.read(timeoutMs)
        } finally {
            cerrojo.unlock()
        }
    }

    /** Lee hasta que el enlace queda en silencio [quietMs]; es como termina cada respuesta. */
    fun exchange(
        command: String?,
        quietMs: Int = 400,
        overallTimeoutMs: Int = 120_000,
        /**
         * Se llama con cada trozo segun llega. Sin esto, un comando largo como LOG parece
         * colgado durante minutos y luego escupe todo de golpe: la placa lleva rato hablando
         * y nadie lo esta viendo.
         */
        onChunk: (ByteArray) -> Unit = {},
    ): ByteArray {
        // Lo que haya en la cola ANTES de mandar el comando es, por definicion, de la
        // operacion anterior. Leerlo como parte de la respuesta es lo que hacia aparecer un
        // "LOGB end" delante del resultado, y con otro texto podria haber falseado un valor.
        return conElEnlace {
            if (command != null) {
                drenarCola(quietMs = 40)
                transport.writeLine(command)
            }
            val out = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + overallTimeoutMs
            var idle = 0
            while (idle < quietMs && System.currentTimeMillis() < deadline) {
                val chunk = transport.read(100)
                if (chunk.isEmpty()) idle += 100
                else { out.write(chunk); onChunk(chunk); idle = 0 }
            }
            out.toByteArray()
        }
    }

    fun drainBanner() { exchange(null, quietMs = 600) }

    /**
     * Recoge lo que YA haya llegado, sin esperar. Se usa al cerrar una operacion para que su
     * ultimo texto no se quede en la cola, y antes de mandar un comando para que lo que
     * quedara no se confunda con la respuesta.
     *
     * Una espera larga aqui devolveria el coste que costo quitar: el volcado por tramos
     * terminaba tres segundos tarde cada vez por esperar un texto que ya no hacia falta.
     */
    fun drenarCola(quietMs: Int = 120, maxMs: Int = 2000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val limite = System.currentTimeMillis() + maxMs
        var idle = 0
        while (idle < quietMs && System.currentTimeMillis() < limite) {
            val c = transport.read(40)
            if (c.isEmpty()) idle += 40 else { out.write(c); idle = 0 }
        }
        return out.toByteArray()
    }

    /**
     * Corta un volcado en curso y deja la linea LIMPIA.
     *
     * Parar de leer NO para de emitir. Abandonar una descarga larga dejaba a la placa
     * volcando megabytes contra un enlace que ya no lee, y esa cola se iba colando despues
     * como si fuera la respuesta de los comandos siguientes: uno escribia VER y recibia
     * bloques del volcado anterior durante minutos, hasta que la cola se agotaba sola.
     *
     * Se manda [CANCEL] --CAN de ASCII-- que el firmware atiende en los mismos puntos en
     * que atiende la pausa, y se espera su acuse: "LOGB aborted" o "LOGH aborted". Ese texto
     * es la prueba de que la placa dejo de emitir; sin el no hay forma de saber si lo que
     * deja de llegar es que paro o que va lento.
     *
     * Con un firmware anterior a 3.1 ese acuse no llega nunca, y entonces esto degrada a lo
     * unico posible: tragarse la cola hasta que el enlace calle. De ahi el tope de tiempo.
     */
    fun abortarVolcado(onDiagnostic: (String) -> Unit = {}): Boolean = conElEnlace {
        cancelled = true
        runCatching { transport.write(byteArrayOf(CANCEL)) }
        val acuse = StringBuilder()
        val limite = System.currentTimeMillis() + ABORT_DRAIN_MS
        var idle = 0
        while (System.currentTimeMillis() < limite) {
            val c = transport.read(50)
            if (c.isEmpty()) {
                idle += 50
                // Silencio prolongado: o paro, o nunca estuvo emitiendo.
                if (idle >= 400) break
                continue
            }
            idle = 0
            acuse.append(String(c, Charsets.ISO_8859_1))
            if (ACUSES.any { acuse.contains(it) }) {
                // Todavia puede quedar el resto de la linea: se recoge y se termina.
                drenarCola(quietMs = 150, maxMs = 800)
                onDiagnostic("Dump cancelled by the board")
                return@conElEnlace true
            }
        }
        val sobrante = drenarCola(quietMs = 300, maxMs = ABORT_DRAIN_MS)
        if (sobrante.isNotEmpty() || acuse.isNotEmpty()) {
            onDiagnostic("Dump aborted; discarded ${acuse.length + sobrante.size} bytes " +
                         "still in the link")
        }
        false
    }

    fun info(): DeviceInfo? = DeviceInfo.parse(String(exchange(Protocol.METADATA)))

    /**
     * Se lanza cuando la placa habla un protocolo anterior al que esta app entiende.
     *
     * Se rechaza en vez de degradar. La alternativa --seguir hablando con placas antiguas
     * sin las funciones nuevas-- deja dos caminos de codigo de los que solo uno se ejerce a
     * diario, y el que no se ejerce es el que falla en terreno. El mensaje nombra la version
     * que hace falta, porque "protocolo incompatible" no le dice a nadie que tiene que hacer.
     */
    class ProtocolTooOld(val found: Int) : Exception(
        "This board speaks protocol $found; this app needs ${Protocol.MIN_PROTOCOL} or newer. " +
        "Update the board firmware (3.0 or later) and try again.")

    /** [info] comprobando la version. Es el camino que debe usar la app al conectar. */
    fun requireInfo(): DeviceInfo {
        val i = info() ?: error("the board did not answer the INFO header")
        if (i.protocol < Protocol.MIN_PROTOCOL) throw ProtocolTooOld(i.protocol)
        return i
    }

    // boardId() retirado. No lo llamaba nadie --la identidad sale de la cabecera INFO, que
    // es el contrato de maquina-- y desde que el comando ID devuelve la linea con el corto y
    // el completo, una funcion que prometiera "el identificador" y devolviera texto con
    // formato solo serviria para que alguien la usara mal.

    fun readVariable(spec: VariableSpec): String =
        String(exchange(spec.readCommand())).trim()

    fun writeVariable(spec: VariableSpec, value: Long): String =
        String(exchange(spec.writeCommand(value))).trim()

    /**
     * Descarga por LOGB. [from]/[to] nulos piden el log entero. Los bloques con CRC malo
     * se reintentan pidiendo solo su rango, que es para lo que sirve el CRC por bloque.
     */
    /**
     * Descarga por LOGB. [from]/[to] nulos piden el log entero.
     *
     * Se pide por TRAMOS cuando el transporte lo indica: un puente serie-BLE recibe de la
     * placa mucho mas rapido de lo que emite por radio, y ante una peticion larga descarta en
     * silencio lo que no le cabe. Troceando, la placa solo envia lo que se le pide y la radio
     * se vacia entre peticiones. Con 3.238 registros de una sola vez se perdian casi todos
     * los bloques, y el reintento --silencioso y sin tope util-- parecia un cuelgue.
     */
    /**
     * Descarga por LOGB. [from]/[to] nulos piden el log entero.
     *
     * Se pide por TRAMOS cuando el transporte lo indica: un puente serie-BLE recibe de la
     * placa mucho mas rapido de lo que emite por radio, y ante una peticion larga descarta en
     * silencio lo que no le cabe.
     *
     * El tamano de tramo se AJUSTA SOLO. Empieza por el que sugiere el transporte y, si un
     * tramo se pierde, lo parte por la mitad y lo reintenta; tras varios tramos seguidos sin
     * incidencias vuelve a subirlo. El tamano optimo depende del buffer del modulo, que nadie
     * documenta y que cambia con cada marca: encontrarlo probando durante la propia descarga
     * es mas fiable que fijarlo de antemano.
     *
     * Importa afinarlo porque el coste fijo por peticion es grande: cabecera, cierre y una
     * ida y vuelta por radio. Con 12 registros (120 bytes de datos) ese coste supera al de
     * los datos.
     */
    fun download(
        info: DeviceInfo,
        from: Long? = null,
        to: Long? = null,
        retries: Int = 2,
        onDiagnostic: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): ByteArray {
        val a = from ?: 0L
        val b = to ?: (info.recordCount - 1)
        require(a <= b) { "empty range: $a..$b" }

        val total = b - a + 1
        val started = System.nanoTime()
        val sugerido = transport.recordsPerRequest
        // Toda la descarga bajo el cerrojo: `downloadRange` lee la linea directamente, no
        // solo a traves de exchange, y el lector de reposo del terminal no puede colarse a
        // robar bytes de un bloque a medias.
        cerrojo.lock()
        try {
        // Solo se salta el bucle cuando el transporte NO trocea, como el cable. Si trocea, se
        // pasa por el aunque todo quepa en un tramo: de lo contrario una descarga corta no
        // podia adaptarse, y es justo la que se hace para probar si el enlace aguanta.
        if (sugerido <= 0) {
            return downloadRange(info, a, b, retries, 0L, total, started, 0,
                                 onDiagnostic, onProgress)
        }


        val out = java.io.ByteArrayOutputStream()
        var done = 0L
        var chunk = sugerido
        var inicio = a

        // Busqueda binaria estricta del mayor tramo que el enlace sostiene.
        //
        // [bajo] es el mayor tamano que funciono; [alto] el menor que fallo. Sin ninguno que
        // funcione todavia se PARTE POR LA MITAD; en cuanto hay horquilla se prueba siempre
        // el punto medio. De 256 a 36 son tres fallos bajando (128, 64, 32) y unas pocas
        // pruebas mas para afinar, frente a los seis que costaba bajar de tres en tres
        // cuartos (192, 144, 108, 81, 60, 45).
        //
        // Cuando la horquilla se cierra --alto y bajo a distancia uno-- la busqueda TERMINA y
        // no se vuelve a tantear hacia arriba. Seguir probando seria provocar fallos a
        // proposito el resto de la descarga.
        // Todos los tamanos que alguna vez completaron un tramo. Guardar solo el mayor no
        // basta: cuando ese mayor empieza a fallar hay que retroceder al siguiente que
        // funciono, no olvidarlo todo y volver a partir por la mitad.
        val funcionaron = sortedSetOf<Int>()
        var bajo = 0
        var alto = Int.MAX_VALUE
        var ultimoUsado = 0
        var buenosSeguidos = 0
        var busquedaCerrada = false
        val inicial = sugerido

        while (inicio <= b) {
            if (cancelled) throw DownloadCancelled(done)
            val fin = minOf(inicio + chunk - 1, b)
            val trozo = try {
                downloadRange(info, inicio, fin, retries, done, total, started, chunk,
                              onDiagnostic, onProgress)
            } catch (e: Exception) {
                if (chunk <= MIN_RECORDS_PER_REQUEST) throw e
                alto = minOf(alto, chunk)
                // El limite inferior pasa a ser el mayor tamano que funciono y que esta por
                // DEBAJO del que acaba de fallar. Antes se ponia a cero, y entonces la
                // busqueda partia por la mitad y caia por debajo de un valor que ya sabiamos
                // bueno: 32 funcionaba, 48 fallaba y se probaba 24.
                bajo = funcionaron.headSet(alto).lastOrNull() ?: 0

                var siguiente = if (bajo >= MIN_RECORDS_PER_REQUEST) (bajo + alto) / 2
                                else alto / 2
                if (siguiente >= chunk) siguiente = chunk - 1
                siguiente = maxOf(siguiente, MIN_RECORDS_PER_REQUEST)
                if (siguiente >= chunk) throw e

                chunk = siguiente
                buenosSeguidos = 0
                onDiagnostic("Too large for this link; trying $chunk records per request")
                continue
            }
            out.write(trozo)
            done += fin - inicio + 1
            inicio = fin + 1
            funcionaron.add(chunk)
            if (chunk < alto) bajo = maxOf(bajo, chunk)
            ultimoUsado = chunk
            buenosSeguidos++

            if (alto - bajo <= 1) busquedaCerrada = true

            if (!busquedaCerrada && buenosSeguidos >= GROW_AFTER_CHUNKS) {
                val siguiente =
                    if (alto == Int.MAX_VALUE)
                        // Sin techo todavia: se dobla, que es la busqueda hacia arriba.
                        minOf(chunk * 2, MAX_RECORDS_PER_REQUEST)
                    else
                        (bajo + alto) / 2
                if (siguiente > chunk) {
                    chunk = siguiente
                    buenosSeguidos = 0
                    onDiagnostic("Link is keeping up; trying $chunk records per request")
                }
            }
        }
        onDiagnostic("Finished with $ultimoUsado records per request (started at $inicial)")
        finalChunk = ultimoUsado
        initialChunk = inicial
        return out.toByteArray()
        } finally {
            cerrojo.unlock()
        }
    }

    /** Un unico LOGB. [alreadyDone]/[grandTotal] son solo para que el progreso sea global. */
    private fun downloadRange(
        info: DeviceInfo, a: Long, b: Long, retries: Int,
        alreadyDone: Long, grandTotal: Long, started: Long, chunkSize: Int,
        onDiagnostic: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): ByteArray {
        val reader = LogbReader()
        var header: LogbHeader? = null
        val blocks = LinkedHashMap<Int, LogbEvent.Block>()
        var bytes = 0L

        val switcher = transport as? BaudSwitchable
        val fast = if (switcher != null && info.supportsFastDump) info.fastBaud else 0

        transport.writeLine(Protocol.logBinary(a, b, fast).trimEnd('\n'))
        val deadline = System.currentTimeMillis() + 10 * 60_000
        var idle = 0
        var finished = false
        var switched = false
        // Tres segundos era una eternidad para un tramo de una decena de registros. Cuando la
        // cabecera ya llego sabemos que la placa esta hablando, asi que basta con poco; si no
        // ha llegado nada se espera algo mas, por si la orden sigue en vuelo.
        fun idleLimit(): Int = if (header != null) IDLE_AFTER_HEADER_MS else IDLE_NO_ANSWER_MS
        fun restore() {
            if (switched) {
                switcher!!.setBaudRate(info.baud.takeIf { it > 0 } ?: DEFAULT_BAUD)
                switched = false
            }
        }

        try {
            while (!finished && idle < idleLimit() && System.currentTimeMillis() < deadline) {
                // Se comprueba AQUI y no solo en el bucle de troceo. Por cable
                // recordsPerRequest vale 0, el troceo se salta entero y toda la descarga
                // ocurre dentro de esta funcion: abortar no tenia ningun efecto.
                if (cancelled) throw DownloadCancelled(alreadyDone + bytes / info.recordBytes)
                val chunk = transport.read(100)
                if (chunk.isEmpty()) { idle += 100; continue }
                idle = 0
                for (e in reader.feed(chunk)) when (e) {
                    is LogbEvent.Header -> {
                        header = e.header
                        if (fast > 0 && !switched) {
                            switcher!!.setBaudRate(fast); switched = true
                        }
                    }
                    is LogbEvent.Block -> {
                        blocks[e.index] = e
                        if (e.crcOk) bytes += e.data.size
                        val h = header
                        if (h != null) {
                            val recBytes = info.recordBytes.toLong()
                            val hechos = alreadyDone + bytes / recBytes
                            onProgress(DownloadProgress(
                                recordsDone = hechos, recordsTotal = grandTotal,
                                bytesDone = alreadyDone * recBytes + bytes,
                                bytesTotal = grandTotal * recBytes,
                                elapsedSeconds = (System.nanoTime() - started) / 1e9,
                                recordsPerRequest = chunkSize,
                            ))
                            if (switched && blocks.size >= h.blocks) restore()
                            // En cuanto estan todos los bloques que anuncia la cabecera se
                            // termina, sin esperar al "LOGB end". Ese texto es una cortesia y
                            // por BLE se pierde a menudo: esperarlo costaba los tres segundos
                            // completos de silencio en la mitad de los tramos, que era el
                            // grueso del tiempo de descarga.
                            if (blocks.size >= h.blocks) finished = true
                        }
                    }
                    LogbEvent.End -> finished = true
                }
            }
        } finally {
            restore()
            // El bucle termina en cuanto llegan todos los bloques, SIN esperar al "LOGB end"
            // -- esperarlo costaba tres segundos de silencio por tramo. Pero no esperarlo no
            // es lo mismo que no leerlo: ese texto se quedaba en el transporte y aparecia
            // pegado al principio de la respuesta del SIGUIENTE comando, donde ademas podia
            // estropear su parseo. Se recoge lo que ya haya llegado, sin esperar a nada.
            drenarCola()
        }

        val h = header ?: run {
            onDiagnostic("LOGB $a..$b: no header; the board answered nothing usable")
            error("the board sent no LOGB header (records $a..$b)")
        }
        var (payload, bad) = Logb.assemble(h, blocks.values.toList())
        if (!finished) {
            onDiagnostic("LOGB $a..$b: no end marker after ${blocks.size}/${h.blocks} blocks")
        }

        // Reintento acotado de los bloques defectuosos, y ACOTADO TAMBIEN EN TIEMPO: con 152
        // bloques perdidos, reintentar uno a uno son minutos de silencio que se leen como un
        // cuelgue. Mejor rendirse pronto y decir que paso.
        var attempt = 0
        val retryDeadline = System.currentTimeMillis() + 60_000
        while (bad.isNotEmpty() && attempt < retries) {
            attempt++
            if (bad.size > MAX_RETRYABLE_BLOCKS) {
                onDiagnostic("LOGB $a..$b: ${bad.size} of ${h.blocks} blocks lost; " +
                             "too many to retry, the link is dropping data")
                break
            }
            onDiagnostic("LOGB $a..$b: retry $attempt for ${bad.size} block(s)")
            for (idx in bad.toList()) {
                if (System.currentTimeMillis() > retryDeadline) {
                    onDiagnostic("LOGB $a..$b: retry budget exhausted"); break
                }
                val recsPerBlock = h.blockSize / h.recordBytes
                val ra = a + idx.toLong() * recsPerBlock
                val rb = minOf(ra + recsPerBlock - 1, b)
                val again = LogbReader().feed(exchange(
                    Protocol.logBinary(ra, rb).trimEnd('\n'), quietMs = 1500,
                    overallTimeoutMs = 10_000))
                again.filterIsInstance<LogbEvent.Block>().firstOrNull { it.crcOk }?.let {
                    blocks[idx] = LogbEvent.Block(idx, it.data, true)
                }
            }
            val r = Logb.assemble(h, blocks.values.toList())
            payload = r.first; bad = r.second
        }
        check(bad.isEmpty()) {
            "records $a..$b: ${bad.size} of ${h.blocks} blocks could not be read. " +
            "On Bluetooth this usually means the link is dropping data; try a shorter range."
        }
        return payload
    }

}
