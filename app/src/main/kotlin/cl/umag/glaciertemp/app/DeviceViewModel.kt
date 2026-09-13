package cl.umag.glaciertemp.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.umag.glaciertemp.core.*
import cl.umag.glaciertemp.transport.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TransportKind { TCP_DEBUG, USB, BLE }

/** Lo que la pantalla de conexion sabe de los enlaces disponibles. */
data class DiscoveryState(
    val usb: List<ConnectionTarget.Usb> = emptyList(),
    val ble: List<ConnectionTarget.Ble> = emptyList(),
    val scanning: Boolean = false,
    val bluetoothEnabled: Boolean = true,
)

data class BatterySettings(
    val type: BatteryType = BatteryType.ALKALINE_ENERGIZER,
    val customMah: Int = 1200,
    val cellCount: Int = 1,
) {
    val capacityMah: Int get() = if (type == BatteryType.CUSTOM) customMah else type.nominalMah
}

/** Una linea del terminal, con su origen para poder distinguirlas en pantalla. */
data class TerminalLine(val text: String, val fromBoard: Boolean)

data class UiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String = "Not connected",
    val info: DeviceInfo? = null,
    /**
     * Layout del registro de los datos que hay ahora en pantalla. Se toma del INFO de la
     * placa o, si el log se cargo de un CSV, de su cabecera. Tenerlo aparte de [info] es lo
     * que permite graficar, estimar la bateria y volver a exportar un fichero abierto sin
     * placa: de aqui en adelante da igual de donde vinieran los datos.
     */
    val signature: Int? = null,
    /** De donde salieron los registros, para poder decirlo en pantalla. */
    val source: String? = null,
    /**
     * Si los datos vienen de un fichero. Bandera y no una comparacion con el texto de
     * [source]: al traducir ese texto, el boton de cerrar dejo de aparecer sin que nada
     * avisara, porque nadie comprueba un prefijo escrito a mano.
     */
    val fromFile: Boolean = false,
    val variables: Map<String, String> = emptyMap(),
    val progress: DownloadProgress? = null,
    val records: List<Record> = emptyList(),
    val csvPreview: String = "",
    val battery: BatterySettings = BatterySettings(),
    val discovery: DiscoveryState = DiscoveryState(),
    /**
     * Historial del terminal, acotado: una placa que despierta cada 10 minutos puede escupir
     * miles de lineas en una sesion larga, y guardarlas todas termina en un fallo de memoria
     * justo cuando mas falta hace el registro.
     */
    val terminal: List<TerminalLine> = emptyList(),
    val boardTime: String? = null,
    /** Aviso de desfase del reloj, o null si esta en hora. */
    val clockWarning: String? = null,
    /** Comandos enviados en esta sesion, del mas reciente al mas antiguo. */
    val history: List<String> = emptyList(),
    val transportNote: String? = null,
    /** Lo que el transporte usara de verdad, para poder enseñarlo. */
    val effectiveChunk: Int = 0,
    /** Resumen al terminar: cuanto tardo y a que ritmo. */
    val downloadSummary: String? = null,
    val batteryEstimate: BatteryEstimate? = null,
    /**
     * Modo avanzado. En normal se ocultan --no se desactivan-- el volcado crudo y toda la
     * configuracion salvo el intervalo. Ocultar y no desactivar: un control gris invita a
     * buscar como encenderlo; uno que no esta no ocupa sitio ni distrae. El terminal sigue
     * disponible en los dos modos, asi que el modo normal no impide nada.
     */
    val advanced: Boolean = false,
    /** Lo que se sabe de la descarga que hay en pantalla: reloj, posicion y nota. */
    val metadata: DownloadMetadata? = null,
    /** Nota libre que el usuario escribe al exportar, si activo la casilla. */
    val exportNote: String? = null,
    /** Casilla de la columna de tiempo corregido. */
    val exportCorrected: Boolean = false,
    /** Avance del volcado crudo, en texto listo para mostrar. */
    val rawLogProgress: String? = null,
    /** Dialogo de sincronizacion en curso, o null. */
    val syncPrompt: SyncPrompt? = null,
    /** Dialogo de posicion al terminar la descarga, o null. */
    val locationPrompt: LocationPrompt? = null,
    val error: String? = null,
)

private const val KEY_ADVANCED = "ui.advanced"

/** En que paso del dialogo de sincronizacion estamos. */
enum class SyncStage {
    /** Hay datos sin descargar en esta placa: sincronizar destruye su desfase. */
    DATA_AT_RISK,
    /** El huso de la placa no es el del telefono. */
    TIMEZONE,
}

/**
 * Los dos avisos van en pasos SEPARADOS y no en un solo dialogo con cuatro botones, porque
 * son dos preguntas independientes con respuestas independientes: si perder o no el desfase
 * de los datos aun no descargados, y si la placa debe adoptar el huso del telefono.
 */
data class SyncPrompt(val stage: SyncStage, val boardTz: Int?, val phoneTz: Int) {
    val tzDiffers: Boolean get() = boardTz != null && boardTz != phoneTz
}

/**
 * Se pregunta por la posicion al TERMINAR la descarga y solo si no llego un arreglo fresco.
 *
 * La posicion de una descarga es en la practica la del sitio, que no se mueve, asi que un
 * arreglo de hace diez minutos alli mismo vale igual de bien. Uno de esa manana en el
 * alojamiento tambien se guardaria, y quedaria escrito en el CSV con aspecto de dato bueno:
 * la antiguedad permitiria detectarlo despues, pero solo si alguien la mira, y nadie la
 * mira. Poner la decision delante de quien SI sabe si ha viajado desde entonces resuelve el
 * problema en el unico momento en que hay alguien capaz de resolverlo.
 */
data class LocationPrompt(
    val lastKnown: cl.umag.glaciertemp.core.GeoFix?,
    val waiting: Boolean = false,
)

class DeviceViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: DeviceSession? = null
    private var transport: Transport? = null
    private var tap: SerialTap? = null

    /**
     * Donde escucha el simulador. Por defecto el alias del emulador; en un telefono real
     * hay que poner la IP del PC en la red local, y por eso el campo es editable.
     */
    var tcpHost: String by mutableStateOf(ConnectionTarget.EMULATOR_HOST)
    var tcpPort: Int by mutableStateOf(5599)

    /** true si la app corre en un emulador, donde 10.0.2.2 si significa algo. */
    var runningOnEmulator: Boolean = false

    /** Acepta "host" o "host:puerto"; lo que no se entienda se deja como estaba. */
    fun setTcpEndpoint(text: String) {
        val t = text.trim()
        val host = t.substringBefore(':').trim()
        val port = t.substringAfter(':', "").trim().toIntOrNull()
        if (host.isNotEmpty()) tcpHost = host
        if (port != null && port in 1..65535) tcpPort = port
    }

    /**
     * Se inyecta desde la Activity. Mientras sea null solo funciona el transporte TCP de
     * depuracion, que es lo que necesita el test instrumentado: asi el ViewModel no
     * depende de un Context ni de que haya radio.
     */
    var connectivity: Connectivity? = null

    /**
     * Preferencias, inyectadas por la actividad igual que [connectivity]. El ViewModel no
     * tiene contexto propio y no debe pedirlo: quien lo tiene lo pasa.
     */
    /** De donde sale la posicion. Inyectada por la actividad; en los tests, una falsa. */
    var location: LocationSource? = null

    var prefs: android.content.SharedPreferences? = null
        set(value) {
            field = value
            // Quien necesita el modo avanzado lo necesita cada vez; quien no, no deberia
            // tener que apagarlo en cada arranque.
            value?.let {
                _state.value = _state.value.copy(advanced = it.getBoolean(KEY_ADVANCED, false))
            }
        }

    fun setAdvanced(on: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ADVANCED, on)?.apply()
        _state.value = _state.value.copy(advanced = on)
    }

    fun refreshUsb() {
        val c = connectivity ?: return
        // Igual que startBleScan: se llama desde un onClick y nadie recoge lo que escape.
        runCatching {
            _state.value = _state.value.copy(discovery = _state.value.discovery.copy(
                usb = c.usbTargets(), bluetoothEnabled = c.bluetoothEnabled))
        }.onFailure { e ->
            _state.value = _state.value.copy(
                error = "Could not list USB adapters: ${e.message}", status = "Error")
        }
    }

    /**
     * Todo el cuerpo va dentro de runCatching porque esto se llama desde un onClick: una
     * excepcion que escape de aqui no la recoge nadie y cierra la app. Ya paso una vez, con
     * el SecurityException de getBondedDevices().
     */
    fun startBleScan() = runCatching {
        val c = connectivity ?: return@runCatching
        // Los permisos PRIMERO, antes de tocar ninguna API de la radio: sin
        // BLUETOOTH_CONNECT, hasta leer la lista de emparejados lanza SecurityException.
        if (c.missingBlePermissions().isNotEmpty()) {
            c.requestBlePermissions()
            _state.value = _state.value.copy(
                status = "Grant the Bluetooth permission to scan")
            return@runCatching
        }
        if (!c.bluetoothEnabled) {
            _state.value = _state.value.copy(error = "Turn Bluetooth on to scan")
            return@runCatching
        }
        // La lista arranca VACIA en cada busqueda. Conservar lo de la vez anterior ofreceria
        // modulos que ya no estan, y conectarse a uno de esos solo da un tiempo de espera.
        // El estado se reescribe: si venimos de pedir permiso, el aviso ya no aplica.
        _state.value = _state.value.copy(
            status = "Scanning for Bluetooth devices...", error = null,
            discovery = _state.value.discovery.copy(
                ble = emptyList(), scanning = true, bluetoothEnabled = true))
        c.scanBle { list ->
            // El mas cercano primero: con varios modulos iguales alrededor, el que se tiene
            // en la mano es el de mayor RSSI.
            _state.value = _state.value.copy(discovery = _state.value.discovery.copy(
                ble = list.sortedByDescending { it.rssi }))
        }
    }.onFailure { e ->
        _state.value = _state.value.copy(
            error = "Could not scan: ${e.message ?: e::class.simpleName}",
            status = "Error",
            discovery = _state.value.discovery.copy(scanning = false))
    }.let { }

    fun stopBleScan() {
        runCatching { connectivity?.stopBleScan() }
        val d = _state.value.discovery
        _state.value = _state.value.copy(
            status = if (!_state.value.connected && d.scanning)
                         "${d.ble.size} devices found" else _state.value.status,
            discovery = d.copy(scanning = false))
    }

    fun connect(kind: TransportKind) = connect(when (kind) {
        TransportKind.TCP_DEBUG -> ConnectionTarget.TcpDebug
        TransportKind.USB -> _state.value.discovery.usb.firstOrNull()
            ?: ConnectionTarget.Usb(-1, "no adapter")
        TransportKind.BLE -> _state.value.discovery.ble.firstOrNull()
            ?: ConnectionTarget.Ble("", "no device")
    })

    fun connect(target: ConnectionTarget) = launchGuarded("Connecting...") {
        stopBleScan()
        if (target is ConnectionTarget.TcpDebug &&
            tcpHost == ConnectionTarget.EMULATOR_HOST && !runningOnEmulator) {
            // Sin esto el usuario ve un error de socket que no explica nada. La causa no es
            // que el simulador no este corriendo, sino que la direccion no existe aqui.
            error("10.0.2.2 only works inside the emulator. On a real phone use the PC's IP " +
                  "on the local network, and check both are on the same network.")
        }
        val c = connectivity
        val t: Transport = when {
            c != null -> {
                // El permiso USB lo concede el usuario en un dialogo del sistema y llega de
                // forma asincrona: se pide y se le dice que reintente, en vez de fallar con
                // un mensaje que no explica nada.
                if (target is ConnectionTarget.Usb && !c.hasUsbPermission(target)) {
                    c.requestUsbPermission(target)
                    error("Grant permission to the USB adapter and try again")
                }
                c.open(target)
            }
            target is ConnectionTarget.TcpDebug -> TcpTransport(tcpHost, tcpPort)
            else -> error("Transport not available: ${target.label}")
        }
        t.open()
        // El espia va en el TRANSPORTE y no en cada ruta: asi no hay nada que recordar
        // enseñar. Todo lo que se envia y todo lo que llega pasa por aqui, incluido lo que
        // la app manda por su cuenta al leer la configuracion o el reloj.
        // wrap() y no el constructor: conserva si el transporte de debajo sabe cambiar de
        // velocidad, que es con lo que DeviceSession decide si puede pedir el volcado rapido.
        val espiado = SerialTap.wrap(t) { linea, deLaPlaca -> appendTerminal(linea, deLaPlaca) }
        val s = DeviceSession(espiado)
        // El banner ya lo publica el espia; leerlo aqui solo sirve para vaciar la cola.
        s.exchange(null, quietMs = 600)

        // Se reintenta una vez: si el enlace se abrio justo cuando la placa estaba
        // arrancando, la primera pregunta se pierde contra el bootloader.
        var info = s.info()
        if (info == null) {
            info = s.info()
        }
        if (info == null) {
            // La placa solo atiende la consola durante los 30 s siguientes a un reinicio;
            // el resto del tiempo duerme y no escucha. Decir solo "no respondio" deja al
            // usuario buscando un fallo que no existe.
            error("The board did not answer.\n\n" +
                  "It only listens on the serial port for 30 s after a reset. " +
                  "Press the RESET button on the board and connect again right away.")
        }
        // Se RECHAZA una placa con protocolo anterior en vez de degradar. Mantener dos
        // caminos de codigo de los que solo uno se ejerce a diario es peor que actualizar
        // la flota, que en todo caso hay que actualizar. El mensaje dice que hacer.
        if (info.protocol < Protocol.MIN_PROTOCOL) {
            t.close()
            error("This board runs firmware ${info.firmware} (protocol ${info.protocol}).\n\n" +
                  "This app needs protocol ${Protocol.MIN_PROTOCOL} or newer. " +
                  "Update the board to firmware 3.0 or later and connect again.")
        }
        val vars = Variables.ALL.associate { spec ->
            spec.code to (VariableSpec.parseValue(s.readVariable(spec)) ?: "?")
        }
        // El reloj se lee al conectar, junto con la configuracion: un desfase deja mal cada
        // marca de tiempo del log y no se nota hasta que alguien compara los datos meses
        // despues.
        val boardNow = BoardClock.parse(String(s.exchange(Protocol.TIME, quietMs = 600)))
        val drift = boardNow?.let { BoardClock.driftSeconds(it, java.time.LocalDateTime.now()) }

        transport = espiado; session = s
        tap = espiado
        arrancarPublicador()
        _state.value = _state.value.copy(
            boardTime = boardNow?.let {
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss").format(it)
            },
            clockWarning = drift?.let { BoardClock.warning(it) },
            connected = true, info = info, signature = info.signature, variables = vars,
            transportNote = describe(t),
            effectiveChunk = t.recordsPerRequest,
            status = "Connected  ·  board ${info.displayId}  ·  ${info.recordCount} records",
        )
    }

    /**
     * Detalle del enlace, util para diagnosticar: en BLE dice que perfil se detecto y que
     * MTU se negocio, que es lo primero que hay que mirar cuando la descarga va lenta o no
     * llega nada.
     */
    private fun describe(t: Transport): String? = when (t) {
        is cl.umag.glaciertemp.transport.android.BleTransport ->
            t.profile?.let { "BLE · ${it.name} · MTU ${t.negotiatedMtu}" }
        is cl.umag.glaciertemp.transport.android.UsbSerialTransport -> {
            val i = _state.value.info
            val normal = i?.baud ?: cl.umag.glaciertemp.transport.android.UsbSerialTransport.BAUD_RATE
            // La de AHORA, no la de siempre. Durante un volcado por cable la linea sube a
            // 230400 y la barra seguia diciendo 115200, que es justo el momento en que uno
            // la mira para comprobar que el volcado rapido entro.
            val rapida = session?.velocidadDeVolcado ?: 0
            val extra = when {
                rapida > 0 -> "  ·  fast dump"
                i != null && i.supportsFastDump -> "  ·  download at ${i.fastBaud}"
                else -> ""
            }
            "Cable · ${if (rapida > 0) rapida else normal} baud$extra"
        }
        else -> null
    }

    /**
     * Tope de lineas del terminal.
     *
     * 500 cortaba el volcado de un log de miles de registros justo cuando mas falta hacia
     * verlo entero. 20.000 lineas son del orden de un megabyte de texto: sobra en un telefono
     * y cubre un LOGC completo de la memoria en uso.
     */
    private val terminalLimit = 20_000

    /**
     * Buffer real del terminal.
     *
     * Las lineas se acumulan aqui y el estado se publica como mucho cada [terminalFlushMs].
     * Publicar en cada linea copiaba la lista entera cada vez --cuadratico-- y con un enlace
     * BLE, que entrega de veinte en veinte bytes, un LOGC de miles de filas provocaba
     * decenas de miles de recomposiciones. La escritura en vivo se volvia mas lenta que
     * esperar al final, que era justamente lo que se queria evitar.
     */
    /**
     * Las lineas del terminal, y el cerrojo que las protege.
     *
     * Las escriben VARIOS hilos: el lector del enlace --que es quien ve lo que la placa dice
     * por su cuenta--, el de la operacion en curso, que publica el eco de cada comando, y el
     * de la interfaz. Y las lee el publicador. Un ArrayDeque sin cerrojo entre esos cuatro se
     * corrompe, o lanza una excepcion al copiarlo justo mientras otro le quita el primero.
     */
    private val terminalBuffer = ArrayDeque<TerminalLine>()
    private val cerrojoTerminal = Any()
    private var lastTerminalFlush = 0L
    private val terminalFlushMs = 150L

    /**
     * Cuantas lineas se han anadido EN TOTAL, no cuantas quedan.
     *
     * El publicador mira este numero para saber si hay algo nuevo, y por eso no puede ser el
     * tamano: pasado el limite se descarta una linea por cada una que entra y el tamano se
     * queda fijo, con lo que el terminal se congelaria justo en la sesion larga, que es
     * cuando uno lo necesita.
     */
    private var anadidas = 0L

    private fun appendTerminal(text: String, fromBoard: Boolean) =
        appendTerminalLines(text.trimEnd('\n').split("\n"), fromBoard)

    private fun appendTerminalLines(lines: List<String>, fromBoard: Boolean, force: Boolean = false) {
        val utiles = lines.filter { it.isNotBlank() || fromBoard }
        if (utiles.isEmpty() && !force) return
        val instantanea: List<TerminalLine>?
        synchronized(cerrojoTerminal) {
            utiles.forEach { terminalBuffer.addLast(TerminalLine(it, fromBoard)) }
            anadidas += utiles.size
            while (terminalBuffer.size > terminalLimit) terminalBuffer.removeFirst()

            val ahora = System.currentTimeMillis()
            instantanea = if (force || ahora - lastTerminalFlush >= terminalFlushMs) {
                lastTerminalFlush = ahora
                terminalBuffer.toList()
            } else null
        }
        instantanea?.let { _state.value = _state.value.copy(terminal = it) }
    }

    /**
     * El mensaje que sale cuando la placa no contesta.
     *
     * Es SIEMPRE la misma causa --la consola solo escucha durante los 30 s siguientes a un
     * reinicio y el resto del tiempo la placa duerme-- y por eso el texto vive en un solo
     * sitio y lo usan todas las rutas. Decir "no respondio" a secas manda a buscar el fallo
     * donde no esta: en el cable, en el modulo o en el comando.
     */
    private fun reportarSilencio(que: String) {
        val aviso = "The board did not answer $que.\n\n" +
                    "It is probably asleep: the console only listens for 30 s after a reset. " +
                    "Press RESET on the board and try again right away."
        appendTerminal("(no answer — the board is probably asleep; press RESET and retry)",
                       fromBoard = true)
        flushTerminal()
        _state.value = _state.value.copy(error = aviso, status = "No answer from the board")
    }

    /**
     * Cierra el ciclo del terminal al terminar CUALQUIER operacion: vacia lo que el espia
     * tenga a medias y publica el buffer en la pantalla.
     *
     * Son dos cosas distintas y hacian falta las dos. `tap.flush()` saca del espia la linea
     * que no acabo en salto de linea; `flushTerminal()` publica el buffer en el estado. Y el
     * publicado esta limitado a una vez cada 150 ms para que un volcado de miles de lineas
     * no repinte la pantalla por cada una -- con lo que el ULTIMO tramo, el que llega cuando
     * ya no viene nada mas detras, se quedaba dentro de esa ventana y no se veia nunca. Por
     * eso cambiar el intervalo no mostraba la respuesta de la placa: estaba en el buffer,
     * sin publicar.
     *
     * Va en launchGuarded y no en cada ruta por lo mismo que el espia va en el transporte:
     * lo que hay que recordar hacer en cada sitio acaba olvidandose en alguno.
     */
    private fun publicarTerminal() {
        tap?.flush()
        flushTerminal()
    }

    private fun flushTerminal() {
        val instantanea = synchronized(cerrojoTerminal) {
            lastTerminalFlush = System.currentTimeMillis()
            terminalBuffer.toList()
        }
        _state.value = _state.value.copy(terminal = instantanea)
    }

    /** Todo el terminal como texto, para guardarlo en un fichero. */
    fun terminalText(): ByteArray = synchronized(cerrojoTerminal) {
        terminalBuffer.joinToString("\n") { it.text }.toByteArray()
    }

    fun terminalFileName(): String {
        val id = _state.value.info?.displayId ?: "session"
        val t = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        return "glaciotools_terminal_${id}_$t.txt"
    }

    fun clearTerminal() {
        synchronized(cerrojoTerminal) { terminalBuffer.clear() }
        flushTerminal()
    }

    /** Envia un comando escrito a mano y muestra lo que conteste la placa. */
    fun sendCommand(command: String) = launchGuarded("Sending...") {
        val s = checkNotNull(session) { "not connected" }
        val cmd = command.trim()
        if (cmd.isEmpty()) return@launchGuarded
        // El eco del comando y la respuesta los publica el espia del transporte.
        // Sin repetidos y con el ultimo primero: el comando que uno quiere repetir suele ser
        // el que acaba de escribir.
        _state.value = _state.value.copy(
            history = (listOf(cmd) + _state.value.history.filter { it != cmd }).take(50))
        // Se va mostrando segun llega, no al final: un LOG de miles de filas tardaba
        // minutos en aparecer y hasta entonces parecia que no pasaba nada.
        //
        // El resto de linea se guarda entre trozos porque un fragmento puede cortar una
        // linea por la mitad, y pintarla partida en dos desalinea las columnas.
        val reply = String(s.exchange(cmd, quietMs = 800, overallTimeoutMs = 10 * 60_000))
        publicarTerminal()
        if (reply.isBlank()) {
            reportarSilencio("the command \"$cmd\"")
        }
        _state.value = _state.value.copy(status = "Sent: $cmd")
    }

    /**
     * Pone en la placa la hora del telefono, con segundos y con la zona horaria.
     *
     * La zona va aparte porque la placa guarda las marcas de tiempo en hora local y usa TZN
     * solo para lo que necesita UTC. Ajustar una sin la otra deja el reloj desplazado tantas
     * horas como el huso.
     */
    /**
     * De que placa se han descargado datos en esta sesion. Es todo el estado que necesita el
     * aviso: sale cuando no coincide con la placa conectada, asi que conectar otra lo
     * reactiva sin ninguna logica adicional.
     */
    private var downloadedFrom: String? = null

    /** El huso que declara la placa, leido de la configuracion que ya tenemos. */
    private fun boardTimeZone(): Int? = _state.value.variables["TZN"]?.trim()?.toIntOrNull()

    private fun phoneTimeZone(): Int =
        java.time.ZonedDateTime.now().offset.totalSeconds / 3600

    /**
     * Punto de entrada del boton Synchronize. Antes de escribir nada comprueba las dos cosas
     * que el usuario no puede deshacer despues.
     */
    fun requestSyncClock() {
        val id = _state.value.info?.boardId
        if (id != null && id != downloadedFrom) {
            _state.value = _state.value.copy(
                syncPrompt = SyncPrompt(SyncStage.DATA_AT_RISK, boardTimeZone(), phoneTimeZone()))
            return
        }
        continueToTimeZoneStep()
    }

    /** Tras aceptar el primer aviso: o sale el del huso, o se sincroniza directamente. */
    fun continueToTimeZoneStep() {
        val p = SyncPrompt(SyncStage.TIMEZONE, boardTimeZone(), phoneTimeZone())
        if (p.tzDiffers) {
            _state.value = _state.value.copy(syncPrompt = p)
        } else {
            _state.value = _state.value.copy(syncPrompt = null)
            syncClock(ClockSyncMode.BOTH)
        }
    }

    fun cancelSync() {
        _state.value = _state.value.copy(syncPrompt = null)
    }

    /**
     * Vuelve a leer el reloj de la placa y recalcula el desfase, sin escribir nada.
     *
     * Hace falta un boton propio porque el desfase solo se media al conectar: tras una
     * descarga larga, o si uno quiere confirmar antes de sincronizar, no habia forma de
     * volver a mirarlo que no fuera desconectar y conectar.
     */
    fun checkClock() = launchGuarded("Reading the board clock...") {
        val s = checkNotNull(session) { "not connected" }
        val reply = String(s.exchange(Protocol.TIME, quietMs = 800))
        publicarTerminal()
        val boardNow = BoardClock.parse(reply)
        if (boardNow == null) {
            reportarSilencio("the TIME command")
            return@launchGuarded
        }
        val ahora = java.time.LocalDateTime.now()
        val drift = BoardClock.driftSeconds(boardNow, ahora)
        _state.value = _state.value.copy(
            boardTime = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss").format(boardNow),
            clockWarning = BoardClock.warning(drift),
            status = if (drift == 0L) "Board clock matches this phone"
                     else "Board clock offset: ${if (drift > 0) "+" else "-"}" +
                          BoardClock.format(drift),
        )
    }

    fun syncClock() = syncClock(ClockSyncMode.BOTH)

    fun syncClock(mode: ClockSyncMode) = launchGuarded("Setting clock...") {
        val s = checkNotNull(session) { "not connected" }
        _state.value = _state.value.copy(syncPrompt = null)
        val phoneOffset = phoneTimeZone()
        val boardOffset = boardTimeZone()

        // La conversion vive en :core y se prueba alli: con husos distintos, "solo la hora"
        // NO puede mandar la hora local del telefono. Ver ClockSyncTest.
        val effectiveOffset = ClockSync.targetOffsetHours(mode, boardOffset, phoneOffset)
        val now = ClockSync.stampFor(java.time.ZonedDateTime.now(), mode,
                                     boardOffset, phoneOffset)

        if (mode == ClockSyncMode.BOTH) {
            // El huso PRIMERO: el firmware, al cambiarlo, mueve el reloj en consecuencia.
            Variables.byCode("TZN")?.let { tz ->
                val reply = s.writeVariable(tz, phoneOffset.toLong())
                VariableSpec.parseValue(reply)?.let { v ->
                    _state.value = _state.value.copy(
                        variables = _state.value.variables + ("TZN" to v))
                }
            }
        }
        val offsetHours = effectiveOffset
        val reply = String(s.exchange(Protocol.setTime(now).trimEnd('\n'), quietMs = 800))
        appendTerminal(reply, fromBoard = true)
        val shown = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").format(now)
        val tz = if (offsetHours >= 0) "UTC+$offsetHours" else "UTC$offsetHours"
        val boardNow = BoardClock.parse(reply)
        _state.value = _state.value.copy(
            boardTime = boardNow?.let {
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss").format(it)
            } ?: shown,
            clockWarning = boardNow
                ?.let { BoardClock.warning(BoardClock.driftSeconds(it, now)) },
            status = "Clock set to $shown  ·  $tz")
    }

    /** Plazo del arreglo fresco que se pide en paralelo con la descarga. */
    private val FRESH_FIX_TIMEOUT_MS = 20_000L

    /** Plazo de la espera EXPLICITA, cuando el usuario elige esperar en el dialogo. */
    private val WAIT_FIX_TIMEOUT_MS = 120_000L

    private var waitJob: kotlinx.coroutines.Job? = null

    /** Aceptar el ultimo arreglo conocido tal cual, con su antiguedad. */
    fun useLastKnownLocation() {
        val fix = _state.value.locationPrompt?.lastKnown
        applyPosition(fix, if (fix == null) LocationNotes.NO_PROVIDER else null)
    }

    /**
     * Seguir esperando un arreglo fresco. El boton de continuar sin posicion sigue
     * disponible durante la espera: una espera de la que no se puede salir obligaria a matar
     * la app, y con ella se perderia la descarga recien hecha.
     */
    fun waitForFreshLocation() {
        val src = location ?: return applyPosition(null, LocationNotes.NO_PROVIDER)
        _state.value = _state.value.copy(
            locationPrompt = _state.value.locationPrompt?.copy(waiting = true))
        waitJob = viewModelScope.launch {
            val fix = runCatching {
                withContext(Dispatchers.IO) { src.freshFix(WAIT_FIX_TIMEOUT_MS) }
            }.getOrNull()
            if (fix != null) applyPosition(fix, null)
            else _state.value = _state.value.copy(
                locationPrompt = _state.value.locationPrompt?.copy(waiting = false),
                error = "No fresh position arrived. You can use the last known one or " +
                        "continue without a position.")
        }
    }

    fun continueWithoutLocation() {
        waitJob?.cancel()
        applyPosition(null, LocationNotes.USER_SKIPPED)
    }

    private fun applyPosition(fix: cl.umag.glaciertemp.core.GeoFix?, note: String?) {
        waitJob?.cancel()
        val meta = _state.value.metadata?.copy(position = fix, positionNote = note)
        _state.value = _state.value.copy(metadata = meta, locationPrompt = null)
        refreshPreview()
    }

    /** La nota libre que el usuario escribe al exportar. */
    fun setExportNote(text: String?) {
        _state.value = _state.value.copy(exportNote = text)
        refreshPreview()
    }

    /**
     * Rehace la vista previa cuando cambia algo que va en la cabecera del CSV.
     *
     * Formatea SOLO las filas que se ven. Antes exportaba el log entero y se quedaba con las
     * primeras lineas: con cien mil registros eran casi seis megas de texto en cada
     * pulsacion del campo de la nota, y el teclado tardaba segundos en responder.
     */
    private fun refreshPreview() {
        val st = _state.value
        val sig = st.signature ?: return
        if (st.records.isEmpty()) return
        _state.value = _state.value.copy(
            csvPreview = CsvExporter.preview(st.records, sig, metaForExport(),
                                             st.exportCorrected))
    }

    private fun metaForExport(): DownloadMetadata? =
        _state.value.metadata?.copy(note = _state.value.exportNote?.takeIf { it.isNotBlank() })

    fun setExportCorrected(on: Boolean) {
        _state.value = _state.value.copy(exportCorrected = on)
        refreshPreview()
    }

    // --- volcado crudo (LOGH) ---------------------------------------------

    fun rawLogName(): String {
        val id = _state.value.info?.displayId ?: "no-board"
        val t = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        return "glaciotools_${id}_${t}_rawlog.hex"
    }

    /**
     * El tiempo estimado, calculado con la velocidad del enlace y no con una constante: por
     * cable son minutos y por radio mas de media hora, y esa diferencia es justo lo que el
     * usuario necesita saber ANTES de pulsar.
     */
    /**
     * Bytes de LINEA que va a mover el volcado crudo: la memoria ENTERA.
     *
     * Desde el firmware 2.9, LOGH sin argumento vuelca los ocho megas completos y no los
     * registros que anuncia el contador. Es la via de recuperacion para cuando el firmware
     * no puede leer su propio log, y en ese caso el contador es justamente el dato del que
     * no hay que fiarse: estimar con el daria una barra de progreso que se pasa del 100 %
     * a la octava parte del camino.
     *
     * En Intel HEX, cada 16 bytes de dato salen como 44 de linea, mas un registro de
     * direccion extendida por cada 64 KiB.
     */
    fun rawLogLineBytes(): Long {
        val flash = _state.value.info?.flashBytes ?: 0L
        if (flash <= 0) return 0L
        return (flash + 15) / 16 * 44 + (flash / 65536 + 1) * 15 + 11
    }

    fun rawLogEstimate(): String {
        val flash = _state.value.info?.flashBytes ?: 0L
        val lineBytes = rawLogLineBytes()
        // Un transporte que trocea es BLE; el ritmo sale de lo medido contra modulos
        // reales. Por cable manda la velocidad de linea, diez bits por byte con start y stop.
        val troceado = (transport?.recordsPerRequest ?: 0) > 0
        val i2 = _state.value.info
        // La velocidad que de verdad se va a usar: por cable, la rapida si la placa la
        // ofrece. Estimar con la nominal anunciaria el doble de tiempo del real.
        val baudReal = if (!troceado && i2?.supportsFastDump == true) i2.fastBaud
                       else (i2?.baud ?: 115200)
        val bps = if (troceado) 1500.0 else baudReal / 10.0
        val secs = if (bps > 0) (lineBytes / bps).toLong() else 0L
        return "This dumps the WHOLE memory -- " +
               "${"%.0f".format(flash / 1024.0 / 1024.0)} MiB, about " +
               "${"%.0f".format(lineBytes / 1024.0 / 1024.0)} MiB as Intel HEX -- and not " +
               "just the records the board says it has.\n\n" +
               "Estimated time over this link: ${BoardClock.format(secs)}.\n\n" +
               "Use it when the log cannot be read normally: then the record count is " +
               "exactly what cannot be trusted. For everyday downloads use Download.\n\n" +
               "The file is written as it arrives, so an interrupted download keeps what " +
               "already came through."
    }

    fun downloadRawLog(uri: android.net.Uri, resolver: android.content.ContentResolver) =
        launchGuarded("Dumping raw log...") {
            session?.cancelled = false
            val s = checkNotNull(session) { "not connected" }
            val t = checkNotNull(transport) { "not connected" }
            val info = checkNotNull(_state.value.info) { "no metadata" }
            val out = resolver.openOutputStream(uri) ?: error("could not open the file")
            val r = out.use { stream ->
                RawHexDownload.download(
                    s, t, rawLogLineBytes(),
                    // Como en LOGB: lo decide el ANFITRION, porque es el unico que sabe si
                    // debajo hay un cable o una radio. Por cable baja el volcado de media
                    // hora a diecisiete minutos; por BLE el cuello es el modulo, no la UART.
                    fastBaud = if (info.supportsFastDump) info.fastBaud else 0,
                    normalBaud = info.baud.takeIf { it > 0 } ?: 115200,
                    sink = { stream.write(it) },
                    onProgress = { p ->
                        val eta = if (p.secondsRemaining.isNaN()) "estimating"
                                  else "${BoardClock.format(
                                      p.secondsRemaining.toLong().coerceAtLeast(0))} left"
                        _state.value = _state.value.copy(
                            rawLogProgress =
                                "${"%.0f".format(p.fraction * 100)} %  ·  " +
                                "${"%.1f".format(p.bytesPerSecond / 1024)} kB/s  ·  $eta",
                            status = "Raw log  ·  ${"%.0f".format(p.fraction * 100)} %  ·  $eta")
                    },
                    onDiagnostic = { appendTerminal(it, fromBoard = true) },
                    isCancelled = { session?.cancelled == true },
                )
            }
            publicarTerminal()
            val resumen = buildString {
                append("${r.recordsWritten} records written")
                if (r.recordsRejected > 0) append("  ·  ${r.recordsRejected} dropped (bad checksum)")
                if (!r.sawEndOfFile) append("  ·  INCOMPLETE: no end-of-file record")
            }
            _state.value = _state.value.copy(rawLogProgress = resumen, status = resumen)
            if (r.recordsWritten == 0L) {
                reportarSilencio("the LOGH command")
            } else if (!r.sawEndOfFile) {
                _state.value = _state.value.copy(
                    error = "The raw dump stopped before the end-of-file record, so the " +
                            "file is incomplete.\n\n" +
                            "If the board fell asleep, press RESET and download again.")
            }
        }

    /**
     * Reinicia el contador de registros de la placa (comando RC).
     *
     * NO borra la flash: mueve el contador a cero, con lo que la descarga normal deja de
     * poder alcanzar lo que hay escrito. Los datos siguen fisicamente ahi hasta que la placa
     * tome mas mediciones, que vuelven a escribir desde la direccion 0 y los van
     * sobrescribiendo uno a uno.
     *
     * Esa distincion es la que hace util el aviso: si se reinicia por error, el volcado
     * crudo todavia puede rescatar los datos, pero solo antes de que la placa vuelva a
     * medir.
     */
    fun resetCounter() = launchGuarded("Resetting the counter...") {
        val s = checkNotNull(session) { "not connected" }
        val reply = String(s.exchange(Protocol.RESET_COUNTER, quietMs = 800))
        publicarTerminal()
        if (reply.isBlank()) {
            reportarSilencio("the RC command")
            return@launchGuarded
        }
        // Se vuelve a leer la cabecera para que el contador que se ensena sea el de la placa
        // y no el que teniamos guardado: si no, la tarjeta seguiria anunciando los registros
        // de antes y la descarga ofreceria un rango que ya no existe.
        val info = s.info()
        _state.value = _state.value.copy(
            info = info ?: _state.value.info,
            // Los datos en pantalla son de ANTES del reinicio. Dejarlos con la placa ya
            // reiniciada invita a creer que se pueden volver a descargar.
            records = emptyList(), csvPreview = "", metadata = null, exportNote = null,
            downloadSummary = null, batteryEstimate = null,
            status = "Counter reset  ·  ${info?.recordCount ?: 0} records",
        )
        // La placa vuelve a estar sin descargar: el aviso de sincronizar el reloj tiene que
        // volver a salir, porque el desfase de lo que grabe desde ahora aun no se ha medido.
        downloadedFrom = null
    }

    fun setBatterySettings(b: BatterySettings) {
        _state.value = _state.value.copy(battery = b)
        recomputeBattery()
    }

    private fun recomputeBattery() {
        val st = _state.value
        val sig = st.signature
        if (sig == null || st.records.isEmpty()) {
            _state.value = st.copy(batteryEstimate = null); return
        }
        _state.value = st.copy(batteryEstimate = Battery.estimate(
            st.records, sig, st.battery.type,
            st.battery.capacityMah, st.battery.cellCount))
    }

    fun disconnect() {
        val s = session
        val t = transport
        val espia = tap
        publicador?.cancel(); publicador = null
        transport = null; session = null; tap = null
        // El cierre se va a un hilo aparte porque ahora ESPERA: mandar Q y aguardar el "Bye"
        // son unos cientos de milisegundos, y bloquear el hilo de la interfaz por eso deja
        // la pantalla congelada justo al pulsar el boton.
        //
        // Un hilo y no viewModelScope: esto es limpieza que TIENE que terminar. Si el
        // usuario desconecta y acto seguido se sale de la app, una corrutina del scope se
        // cancela a media faena y el puerto se queda abierto.
        Thread({
            // Q ANTES de soltar nada. La consola de la placa se queda escuchando dos minutos
            // despues del ultimo comando, y durante esos dos minutos la placa NO esta
            // grabando: desconectar sin avisar deja un agujero en el registro tan largo como
            // la ventana de consola. Con Q la placa se despide y vuelve a medir en el acto.
            //
            // Con tope corto y sin dar importancia al fallo: si la placa no contesta --ya
            // dormida, cable retirado de golpe-- lo que toca es cerrar igual, no quedarse
            // esperando a algo que no va a venir.
            runCatching {
                s?.exchange("Q", quietMs = 200, overallTimeoutMs = 1000)
            }
            // El eco y la respuesta los publica el espia, pero el publicador ya no corre.
            runCatching { espia?.flush() }
            publicarTerminal()
            // La sesion suelta su hilo lector ANTES de cerrar el transporte: al reves, la
            // siguiente lectura fallaria sobre un enlace ya cerrado.
            runCatching { s?.cerrar() }
            runCatching { t?.close() }
        }, "glaciotools-cierre").start()
        // El terminal y el historial SOBREVIVEN a la desconexion: si algo fallo, el registro
        // de lo que dijo la placa es justo lo que hace falta despues, y borrarlo al soltar el
        // enlace obliga a reproducir el fallo para volver a verlo.
        _state.value = UiState(
            status = "Not connected",
            terminal = _state.value.terminal,
            history = _state.value.history,
            discovery = _state.value.discovery.copy(scanning = false),
        )
    }

    fun setVariable(spec: VariableSpec, value: Long) = launchGuarded("Writing ${spec.code}...") {
        val s = checkNotNull(session) { "not connected" }
        spec.validate(value)?.let { error(it) }
        val reply = s.writeVariable(spec, value)
        publicarTerminal()
        if (reply.isBlank()) {
            reportarSilencio("the ${spec.code} command")
            return@launchGuarded
        }
        val read = VariableSpec.parseValue(reply) ?: "?"
        _state.value = _state.value.copy(
            variables = _state.value.variables + (spec.code to read),
            status = "${spec.code} = $read",
        )
    }

    private var publicador: kotlinx.coroutines.Job? = null

    /**
     * Publica en pantalla lo que la bomba va recogiendo.
     *
     * Ya no lee nada del puerto: de eso se encarga el hilo de la bomba, que nunca para, y el
     * espia va poniendo cada linea completa en el buffer del terminal. Aqui solo se empuja
     * ese buffer al estado, porque el publicado esta limitado a una vez cada 150 ms para que
     * un volcado de miles de lineas no repinte la pantalla por cada una -- y sin alguien que
     * insista, la ultima tanda se quedaria sin salir.
     *
     * NO se llama a `tap.flush()` en cada vuelta: eso saca tambien la linea a medio llegar, y
     * publicarla partida en dos desalinea las columnas. Se reserva para el final de cada
     * operacion, donde ya no viene nada detras.
     */
    private fun arrancarPublicador() {
        publicador?.cancel()
        publicador = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(200)
                if (anadidas != publicadas) {
                    publicadas = anadidas
                    flushTerminal()
                }
                // La velocidad de la linea cambia sola a mitad de un volcado, sin que nadie
                // toque la interfaz. Se recalcula aqui porque este es el unico sitio que
                // vuelve a mirar mientras la descarga esta en marcha.
                transport?.let { t ->
                    val nota = describe(t)
                    if (nota != _state.value.transportNote) {
                        _state.value = _state.value.copy(transportNote = nota)
                    }
                }
            }
        }
    }

    private var publicadas = 0L

    /**
     * Aborta la descarga en curso y deja la linea limpia antes de devolver el control.
     *
     * Poner la bandera no basta: la placa sigue volcando contra un enlace que ya no se lee,
     * y esa cola se cuela despues como si fuera la respuesta de los comandos siguientes. Se
     * le manda el byte de cancelacion y se espera su acuse, y hasta entonces la interfaz
     * queda ocupada -- que es lo honesto, porque hasta entonces no se puede mandar nada.
     */
    fun abortDownload() {
        val s = session ?: return
        s.cancelled = true
        _state.value = _state.value.copy(status = "Stopping the board...", busy = true)
        viewModelScope.launch {
            val paro = withContext(Dispatchers.IO) {
                runCatching {
                    s.abortarVolcado { appendTerminal(it, fromBoard = true) }
                }.getOrDefault(false)
            }
            publicarTerminal()
            _state.value = _state.value.copy(
                busy = false, progress = null,
                status = if (paro) "Download aborted; the link is clear"
                         else "Download aborted",
                error = if (paro) null else
                    "The board did not confirm that it stopped dumping.\n\n" +
                    "With firmware older than 3.1 there is no way to tell it to stop, so " +
                    "whatever it had left may still be arriving. If the terminal shows " +
                    "leftover download output, press RESET on the board.")
        }
    }

    fun download(from: Long?, to: Long?) = launchGuarded("Downloading...") {
        session?.cancelled = false
        val s = checkNotNull(session) { "not connected" }
        val info = checkNotNull(_state.value.info) { "no metadata" }
        loadedFileName = null
        val t0 = System.nanoTime()

        // La hora de la placa se lee ANTES de descargar: es el instante al que se refiere el
        // desfase que luego corrige las marcas. Leerla despues mediria otra cosa, y por una
        // descarga larga por radio la diferencia no es despreciable.
        val boardNow = BoardClock.parse(String(s.exchange(Protocol.TIME, quietMs = 800)))
        val reference = java.time.LocalDateTime.now().withNano(0)

        // La posicion se pide en paralelo y sin esperarla: los datos son el objetivo y la
        // posicion es contexto. El ultimo arreglo conocido es instantaneo; el fresco tarda.
        val src = location
        val puedeUbicar = src != null && runCatching { src.isAvailable() }.getOrDefault(false)
        val fixJob = if (puedeUbicar) viewModelScope.async(Dispatchers.IO) {
            runCatching { src!!.freshFix(FRESH_FIX_TIMEOUT_MS) }.getOrNull()
        } else null
        val lastKnown = if (puedeUbicar) runCatching { src!!.lastKnownFix() }.getOrNull() else null

        // El primer tramo tarda en dar senales: entre la orden y el primer bloque pasan
        // varios segundos por radio, y hasta ahora la linea de estado se quedaba muda.
        _state.value = _state.value.copy(status = "Requesting log from the board...")
        val payload = s.download(info, from, to,
            onDiagnostic = { appendTerminal(it, fromBoard = true) },
        ) { p ->
            _state.value = _state.value.copy(
                progress = p,
                status = "Downloading  ·  ${p.recordsDone} of ${p.recordsTotal} records",
            )
        }
        _state.value = _state.value.copy(status = "Decoding ${payload.size} bytes...")
        val records = LogDecoder.decode(payload, info.signature)

        // A partir de aqui los datos ya estan a salvo en el telefono: pase lo que pase con
        // la posicion, la descarga no se pierde. Por eso el dialogo de posicion viene
        // DESPUES y nunca antes.
        downloadedFrom = info.boardId
        val fresh = fixJob?.await()

        // La hora GPS manda sobre la del telefono cuando la hay. Un telefono que lleva
        // semanas sin red puede ir tan mal como la placa, y corregir contra el arrastraria
        // el error en vez de quitarlo. El desvio se mide UNA vez, al recibir el arreglo, y
        // se aplica a la referencia tomada al empezar: es un error del reloj del telefono,
        // no del instante, asi que vale igual para los dos momentos.
        //
        // Se exige que pase de un segundo: por debajo es ruido de redondeo y anunciar "GPS"
        // por medio segundo daria una confianza que el dato no respalda.
        val skew = fresh?.clockSkewSeconds
        val usaGps = skew != null && kotlin.math.abs(skew) >= 1
        val referenciaReal = if (usaGps) reference.plusSeconds(skew!!) else reference

        val meta = DownloadMetadata(
            downloadedAt = referenciaReal,
            boardId = info.displayId,
            boardFullId = info.boardId,
            boardTime = boardNow,
            referenceTime = referenciaReal,
            reference = if (usaGps) ClockReference.GPS else ClockReference.PHONE,
            position = fresh,
            positionNote = when {
                fresh != null -> null
                location == null -> LocationNotes.NO_PROVIDER
                !puedeUbicar -> LocationNotes.NO_PERMISSION
                else -> null      // lo decide el dialogo
            },
        )
        val vistaPrevia = CsvExporter.preview(records, info.signature, meta, corrected = false)
        _state.value = _state.value.copy(
            metadata = meta,
            exportNote = null,
            // Si no hubo arreglo fresco se pregunta, con el ultimo conocido y su antiguedad
            // delante: quien esta alli sabe si ha viajado desde entonces y el programa no.
            // Solo se pregunta si preguntar puede servir de algo: sin permiso ni proveedor,
            // el dialogo solo podria decir "no hay posicion" despues de cada descarga.
            locationPrompt = if (fresh == null && puedeUbicar)
                LocationPrompt(lastKnown = lastKnown, waiting = false) else null,
            records = records, csvPreview = vistaPrevia,
            progress = null, source = "download from board ${info.boardId}", fromFile = false,
            // Cuanto tardo y a que ritmo: es lo unico que permite comparar un ajuste con
            // otro en vez de opinar sobre cual va mas rapido.
            downloadSummary = buildString {
                val secs = (System.nanoTime() - t0) / 1e9
                append("${records.size} records in ")
                append(BoardClock.format(secs.toLong().coerceAtLeast(1)))
                append("  ·  %.1f rec/s".format(if (secs > 0) records.size / secs else 0.0))
                // Los DOS valores: el inicial es con el que arranca la busqueda y el final
                // el que de verdad sostuvo el enlace. Mostrar solo el inicial --que era lo
                // que hacia-- describia el punto de partida y no el resultado.
                if (s.finalChunk > 0) {
                    append("  ·  ${s.initialChunk} initial / ${s.finalChunk} final " +
                           "records per request")
                }
            },
            effectiveChunk = transport?.recordsPerRequest ?: _state.value.effectiveChunk,
            status = "${records.size} records downloaded",
        )
        recomputeBattery()
    }

    /** Nombre de fichero por placa, para no mezclar series de equipos distintos. */
    fun exportName(): String {
        // Un log abierto de fichero conserva su nombre: proponer uno nuevo con la fecha de
        // hoy sugiere que son datos de hoy, y no lo son.
        loadedFileName?.let { return it }
        // El corto: un nombre de fichero con 16 digitos hexadecimales no lo lee nadie.
        val id = _state.value.info?.displayId ?: "no-board"
        val t = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        return "glaciotools_${id}_$t.csv"
    }

    private var loadedFileName: String? = null

    /** CSV completo de lo descargado, para escribirlo en el destino que elija el usuario. */
    fun csvBytes(): ByteArray {
        val st = _state.value
        val sig = st.signature ?: return ByteArray(0)
        return CsvExporter.export(st.records, sig, metaForExport(), st.exportCorrected)
            .toByteArray()
    }

    /**
     * Carga un CSV exportado antes. No necesita placa ni conexion: sirve para revisar en
     * el telefono una descarga vieja, o para comprobar la visualizacion sin salir a terreno.
     */
    fun loadCsv(name: String, bytes: ByteArray) = launchGuarded("Reading $name...") {
        val log = CsvImporter.parse(String(bytes))
        loadedFileName = name
        val skipped = if (log.skipped > 0) "  ·  ${log.skipped} unreadable rows" else ""
        _state.value = _state.value.copy(
            records = log.records, signature = log.signature,
            csvPreview = CsvExporter.preview(log.records, log.signature),
            // Un fichero abierto no trae metadatos de descarga ni nota: son de la descarga
            // que lo produjo, no del fichero. Arrastrar los de la anterior seria peor.
            metadata = null, exportNote = null, exportCorrected = false,
            source = "file $name", fromFile = true, progress = null,
            status = "${log.records.size} records read from $name$skipped",
        )
        recomputeBattery()
    }

    /** Vuelve a la pantalla de conexion descartando el fichero cargado. */
    fun showError(message: String) {
        _state.value = _state.value.copy(error = message, status = "Error")
    }

    fun closeFile() {
        loadedFileName = null
        _state.value = UiState(status = "Not connected")
    }

    fun onExported(ok: Boolean, name: String) {
        _state.value = _state.value.copy(
            status = if (ok) "Saved to $name" else "Export cancelled")
    }

    /** Trozos de mensaje que delatan un fallo por silencio y no por otra cosa. */
    private val SILENCIO = listOf(
        "no LOGB header", "did not answer", "no answer", "sent no", "timed out",
        "end-of-file record never arrived",
    )

    private fun launchGuarded(busyMsg: String, block: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null, status = busyMsg)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                publicarTerminal()
                _state.value = _state.value.copy(busy = false)
            } catch (e: cl.umag.glaciertemp.transport.DownloadCancelled) {
                // Cancelar no es fallar: no se pinta en rojo.
                publicarTerminal()
                _state.value = _state.value.copy(
                    busy = false, progress = null,
                    status = "Download aborted after ${e.recordsDone} records")
            } catch (e: Throwable) {
                publicarTerminal()
                // Un fallo por SILENCIO tiene casi siempre una sola causa: la placa duerme y
                // la consola solo escucha 30 s tras un reinicio. Decir "no respondio" a
                // secas manda a buscarlo en el cable o en el comando, que es donde no esta.
                val texto = e.message ?: e.toString()
                val mudo = SILENCIO.any { texto.contains(it, ignoreCase = true) }
                _state.value = _state.value.copy(
                    busy = false, progress = null,
                    error = if (mudo) "$texto\n\n" +
                        "The board is probably asleep: the console only listens for 30 s " +
                        "after a reset. Press RESET on the board and try again right away."
                        else texto,
                    status = if (mudo) "No answer from the board" else "Error")
            }
        }
    }
}
