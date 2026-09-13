package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.BuildConfig
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import cl.umag.glaciertemp.core.BoardClock
import cl.umag.glaciertemp.core.ClockSyncMode
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import cl.umag.glaciertemp.core.Battery
import cl.umag.glaciertemp.core.BatteryEstimate
import cl.umag.glaciertemp.core.BatteryType
import cl.umag.glaciertemp.core.BatteryUnknown
import cl.umag.glaciertemp.core.CsvExporter
import cl.umag.glaciertemp.core.LogFormat
import cl.umag.glaciertemp.core.Protocol
import cl.umag.glaciertemp.core.Stats
import cl.umag.glaciertemp.core.TimeUnit
import cl.umag.glaciertemp.core.Variables
import kotlin.math.roundToInt

@Composable
fun GlacierTempApp(vm: DeviceViewModel, onBack: (() -> Unit)? = null) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    // imePadding en la Column EXTERIOR y no dentro de la pestana.
    //
    // Con targetSdk 36 Android impone el modo borde a borde y windowSoftInputMode
    // "adjustResize" ya no encoge la ventana: la aplicacion tiene que descontar el teclado
    // ella misma. Puesto aqui, todo el contenido queda por encima del teclado --cabecera y
    // pestanas incluidas-- y el reparto por weight(1f) hace el resto.
    // navigationBarsPadding ademas de imePadding: sin el, el final del contenido queda
    // debajo de la barra de navegacion de Android y, en Terminal, el boton Send se veia
    // tapado en sus tres cuartas partes.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Los dos colores del logotipo, y separado de la barra de estado: sin el
            // margen, la parte alta de las mayusculas quedaba bajo el reloj del telefono.
            val estilo = MaterialTheme.typography.headlineSmall
            val oscuro = androidx.compose.foundation.isSystemInDarkTheme()
            onBack?.let {
                TextButton(onClick = it, contentPadding = PaddingValues(0.dp),
                           modifier = Modifier.testTag("device-back")) { Text("‹ Tools") }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = if (oscuro) WordmarkBlueDark else WordmarkBlue)) {
                        append("Glacio")
                    }
                    // Del esquema y no un color fijo: en oscuro el fondo es el marino del
                    // logo, y "Tools" en marino quedaba invisible.
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) {
                        append("Tools")
                    }
                    // La version, en pequeno y apagada. Sirve para una sola cosa --saber que
                    // APK hay instalado cuando se reporta un fallo-- y para eso tiene que
                    // estar SIEMPRE a la vista, no detras de un menu que nadie abre. Sale de
                    // BuildConfig y no de una constante escrita a mano: la que se escribe a
                    // mano se olvida, y una version equivocada es peor que ninguna.
                    withStyle(SpanStyle(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        fontSize = estilo.fontSize * 0.5f,
                        fontWeight = FontWeight.Normal,
                    )) {
                        append("  v${BuildConfig.VERSION_NAME}")
                    }
                },
                style = estilo,
                modifier = Modifier.padding(top = with(LocalDensity.current) {
                    (estilo.fontSize.toPx() * 0.25f).toDp()
                }),
            )
            Text(s.status, Modifier.testTag("status"),
                 style = MaterialTheme.typography.bodyMedium)
            s.transportNote?.let {
                Text(it, Modifier.testTag("transport-note"),
                     style = MaterialTheme.typography.bodySmall)
            }
            s.error?.let {
                Text("Error: $it", Modifier.testTag("error"),
                     color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
        }

        // El terminal va en su propia pestana y no al final de la pagina: apilarlo todo en
        // una sola columna obliga a recorrer el grafico y la tabla entera con el dedo cada
        // vez que se quiere teclear un comando.
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("Device") }, modifier = Modifier.testTag("tab-device"))
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("Terminal") }, modifier = Modifier.testTag("tab-terminal"))
        }

        // weight(1f) sobre el contenido: al encogerse la ventana con el teclado, lo que se
        // reduce es el contenido y no la cabecera. Sin esto las pestanas se salian por
        // arriba y no se podia volver a Device sin cerrar el teclado.
        // El estado del scroll del terminal vive AQUI y no dentro de la pestana. Al cambiar
        // de pestana el subarbol se destruye, asi que un rememberLazyListState de dentro
        // volvia a nacer en la posicion cero y el efecto de seguimiento lo arrastraba otra
        // vez hasta el final: de ahi el barrido de arriba abajo cada vez que se entraba.
        // Manteniendolo fuera, la posicion es la misma que se dejo.
        val terminalScroll = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> DeviceTab(vm, s)
                else -> TerminalTab(vm, s, terminalScroll)
            }
        }
    }
}

@Composable
private fun DeviceTab(vm: DeviceViewModel, s: UiState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!s.connected) {
            ConnectCard(vm, s)
            // Offered without a connection too: reviewing an old download on the phone
            // should not require having the board at hand.
            OpenCsvCard(vm, s)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.disconnect() }, enabled = !s.busy,
                               modifier = Modifier.testTag("disconnect")) { Text("Disconnect") }
                Spacer(Modifier.weight(1f))
                // Ocultar y no desactivar: en modo normal los controles avanzados no se
                // dibujan, asi que no ocupan sitio ni invitan a tocarlos. El terminal sigue
                // estando en su pestana, que es la via de escape para todo lo demas.
                FilterChip(
                    selected = s.advanced,
                    onClick = { vm.setAdvanced(!s.advanced) },
                    label = { Text(if (s.advanced) "Advanced" else "Normal") },
                    modifier = Modifier.testTag("advanced-toggle"),
                )
            }
            InfoCard(s)
            ClockCard(vm, s)
            ConfigCard(vm, s)
            DownloadCard(vm, s)
        }
        s.syncPrompt?.let { SyncDialog(vm, it) }
        s.locationPrompt?.let { LocationDialog(vm, it) }
        if (s.records.isNotEmpty()) {
            s.signature?.let { ChartCard(s.records, it, s.metadata) }
            BatteryCard(vm, s)
            PreviewCard(vm, s)
        }
    }
}

/**
 * Serial monitor: everything the board says, plus a box to type commands.
 *
 * Not a page section but a tab of its own, and with its own scroll: the log can run to
 * hundreds of lines, and stacking it under the chart would mean scrolling past everything
 * else every time you want to type.
 */
@Composable
private fun TerminalTab(vm: DeviceViewModel, s: UiState, listState: LazyListState) {
    var command by remember { mutableStateOf("") }
    var historyAt by remember { mutableStateOf(-1) }
    val hScroll = rememberScrollState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // SAF, igual que el CSV: el usuario elige donde y no hacen falta permisos.
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.terminalText()) }
        }
    }

    // Al entrar en la pestana se salta al final SIN animar. Animar desde la posicion cero
    // era el barrido de arriba abajo; y saltar es ademas lo que uno quiere al abrir un
    // terminal: lo ultimo que dijo la placa.
    LaunchedEffect(Unit) {
        if (s.terminal.isNotEmpty()) listState.scrollToItem(s.terminal.lastIndex)
    }

    // "Seguir lo nuevo" es un estado PROPIO y no una lectura del layout.
    //
    // Antes se decidia mirando `layoutInfo` en el momento de anadir lineas, y ahi esa
    // informacion todavia describe la lista ANTERIOR: el contador de elementos ya ha
    // crecido pero los visibles no, asi que la comprobacion "estoy al final" daba falso
    // justo cuando acababa de llegar la respuesta. De ahi que unas veces siguiera y otras
    // no, y que a veces se quedara a medio camino.
    //
    // Ahora solo se reevalua cuando el usuario TERMINA de arrastrar, que es un instante en
    // el que el layout ya esta asentado y es ademas el unico momento en que su intencion
    // cambia de verdad.
    var seguir by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && s.terminal.isNotEmpty()) {
            val ultimo = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            seguir = ultimo >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    // Se sigue mientras lleguen lineas Y no se haya subido a leer. El bucle repite mientras
    // el tamano cambie: las lineas se publican en tandas cada 150 ms, y un solo salto se
    // queda corto en cuanto llega la siguiente tanda -- ese era el "no llega hasta el final".
    LaunchedEffect(s.terminal.size, seguir) {
        if (s.terminal.isNotEmpty() && seguir) {
            listState.scrollToItem(s.terminal.lastIndex)
        }
    }

    Column(
        // Sin imePadding aqui: lo aplica la Column exterior una sola vez. Ponerlo tambien
        // en la pestana lo contaba dos veces y dejaba un hueco en blanco bajo el terminal.
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!s.connected) {
            Text("Connect to a board to use the terminal.",
                 Modifier.testTag("terminal-offline"),
                 style = MaterialTheme.typography.bodySmall)
        }
        Card(Modifier.fillMaxWidth().weight(1f)) {
            if (s.terminal.isEmpty()) {
                Text("No output yet. Type a command below; H lists them.",
                     Modifier.padding(12.dp).testTag("terminal-empty"),
                     style = MaterialTheme.typography.bodySmall)
            } else {
                // SelectionContainer alrededor de la lista: mantener pulsado selecciona y
                // permite copiar. Envuelve al LazyColumn entero y no a cada linea, porque
                // una seleccion por linea no dejaria arrastrar sobre varias, que es lo que
                // uno quiere para copiar un tramo del volcado.
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        // Un solo scroll horizontal para TODAS las lineas: puesto en cada
                        // linea, cada una se desplazaria por su cuenta y las columnas
                        // dejarian de alinearse, que es justo lo que hace legible un volcado.
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                            .horizontalScroll(hScroll).testTag("terminal"),
                    ) {
                        items(s.terminal) { line ->
                            Text(
                                line.text,
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                // Sin ajuste de linea: una fila del log partida en dos deja
                                // de poder leerse por columnas.
                                softWrap = false, maxLines = 1,
                                lineHeight = 13.sp,
                                color = if (line.fromBoard) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it; historyAt = -1 },
                label = { Text("Command") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("terminal-input"),
            )
            // Los dos botones apilados y no en fila: en fila robaban el ancho al cuadro de
            // texto, que es lo unico que necesita espacio.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Save y Clear en la misma fila: apilados gastaban dos lineas de alto,
                // que es justo lo que le falta al terminal con el teclado abierto.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { saver.launch(vm.terminalFileName()) },
                        enabled = s.terminal.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("terminal-save"),
                    ) { Text("Save", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = { vm.clearTerminal() }, enabled = s.terminal.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("terminal-clear"),
                    ) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
                }
                IconButton(
                    onClick = {
                        if (s.history.isNotEmpty()) {
                            historyAt = (historyAt + 1).coerceAtMost(s.history.lastIndex)
                            command = s.history[historyAt]
                        }
                    },
                    enabled = s.history.isNotEmpty(),
                    modifier = Modifier.testTag("terminal-history"),
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous command") }
                Button(
                    onClick = {
                        // Quien manda un comando quiere ver la respuesta, aunque hubiera
                        // subido a leer algo antes de escribirlo.
                        seguir = true
                        vm.sendCommand(command); command = ""; historyAt = -1
                    },
                    enabled = s.connected && !s.busy && command.isNotBlank(),
                    modifier = Modifier.testTag("terminal-send"),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ConnectCard(vm: DeviceViewModel, s: UiState) {
    val d = s.discovery
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text("Cable and Bluetooth speak the same protocol but share the board's UART, " +
                 "so they cannot be used at once.", style = MaterialTheme.typography.bodySmall)

            // --- Cable ---------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cable", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.refreshUsb() }, enabled = !s.busy,
                           modifier = Modifier.testTag("usb-refresh")) { Text("Scan") }
            }
            if (d.usb.isEmpty()) {
                Text("No USB-serial adapter connected. The board exposes an FTDI-style header, " +
                     "so it needs a cable with a CH340, CP2102, FT232 or PL2303 chip and " +
                     "an OTG adapter.",
                     style = MaterialTheme.typography.bodySmall,
                     modifier = Modifier.testTag("usb-empty"))
            } else {
                d.usb.forEach { t ->
                    Button(onClick = { vm.connect(t) }, enabled = !s.busy,
                           modifier = Modifier.fillMaxWidth().testTag("connect-usb")) {
                        Text(t.label)
                    }
                }
            }

            HorizontalDivider()

            // --- Bluetooth -----------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bluetooth", style = MaterialTheme.typography.titleSmall,
                     modifier = Modifier.weight(1f))
                if (d.scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { vm.stopBleScan() },
                               modifier = Modifier.testTag("ble-stop")) { Text("Stop") }
                } else {
                    TextButton(onClick = { vm.startBleScan() }, enabled = !s.busy,
                               modifier = Modifier.testTag("ble-scan")) { Text("Scan") }
                }
            }
            if (!d.bluetoothEnabled) {
                Text("Bluetooth is off.", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
            if (d.ble.isEmpty()) {
                Text(if (d.scanning) "Scanning..."
                     else "Press Scan. Devices advertising right now are listed, paired or not: an " +
                          "unconfigured HM-10 often does not advertise its service, and " +
                          "filtering by it would leave the board out.",
                     style = MaterialTheme.typography.bodySmall,
                     modifier = Modifier.testTag("ble-empty"))
            } else {
                d.ble.forEach { t ->
                    Button(onClick = { vm.connect(t) }, enabled = !s.busy,
                           modifier = Modifier.fillMaxWidth().testTag("connect-ble")) {
                        // El RSSI ayuda a distinguir el modulo que se tiene delante de los
                        // del resto del edificio.
                        Text(if (t.rssi != 0) "${t.label}   ${t.rssi} dBm" else t.label)
                    }
                }
            }

            // El simulador solo se ofrece DENTRO del emulador, que es donde 10.0.2.2
            // significa algo. En un telefono no era mas que un boton que siempre falla, y
            // el test instrumentado --que corre en el emulador-- lo sigue viendo.
            if (BuildConfig.ENABLE_TCP_TRANSPORT && vm.runningOnEmulator) {
                HorizontalDivider()
                Text("Simulator", style = MaterialTheme.typography.titleSmall)
                var endpoint by remember { mutableStateOf("${vm.tcpHost}:${vm.tcpPort}") }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; vm.setTcpEndpoint(it) },
                    label = { Text("host:port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("tcp-endpoint"),
                )
                OutlinedButton(onClick = { vm.connect(TransportKind.TCP_DEBUG) }, enabled = !s.busy,
                               modifier = Modifier.testTag("connect-tcp")) {
                    Text("Connect to simulator")
                }
            }
        }
    }
}

@Composable
private fun OpenCsvCard(vm: DeviceViewModel, s: UiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val opener = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // El nombre se saca del propio proveedor; la ruta de un Uri de SAF no es un
            // fichero y no siempre lleva el nombre real.
            val name = queryDisplayName(ctx, uri) ?: "file.csv"
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) vm.showError("Could not read $name")
            else vm.loadCsv(name, bytes)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open a CSV", style = MaterialTheme.typography.titleMedium)
            Text("A CSV exported earlier by this app, or the output of the LOGC command. The " +
                 "columns say which channels it carries, so it plots like a download.", style = MaterialTheme.typography.bodySmall)
            Button(
                // "text/csv" deja fuera ficheros que el proveedor etiqueta de otra forma, y
                // muchos gestores marcan un .csv como text/plain o application/octet-stream.
                onClick = { opener.launch(arrayOf("text/*", "text/csv",
                                                  "application/octet-stream", "*/*")) },
                enabled = !s.busy,
                modifier = Modifier.testTag("open-csv")) { Text("Choose file") }
            s.source?.takeIf { s.fromFile }?.let {
                Text(it, Modifier.testTag("csv-source"),
                     style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.closeFile() },
                               modifier = Modifier.testTag("close-csv")) { Text("Close") }
            }
        }
    }
}

/** Nombre visible de un Uri de SAF. */
private fun queryDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

@Composable
private fun InfoCard(s: UiState) {
    val i = s.info ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Board", style = MaterialTheme.typography.titleMedium)
            // A single Text: with the testTag on the Row, its children are separate nodes
            // and assertTextContains cannot see the descendants' text.
            // "Hardware ID" y no "ID" a secas: en esta misma tarjeta conviven la version
            // de firmware y la revision de hardware, y el "001" del identificador pertenece
            // a la segunda. Un "ID" solo no dice a cual.
            Text("Hardware ID: ${i.displayId}", Modifier.testTag("board-id"),
                 fontFamily = FontFamily.Monospace)
            Text("Firmware ${i.firmware}  ·  protocol ${i.protocol}")
            Text("${i.recordCount} records  ·  ${i.recordBytes} B each",
                 Modifier.testTag("record-count"))

            // The raw signature said nothing to a reader. It encodes WHICH channels wrote
            // the log -- that is how the decoder recovers the record layout from the data
            // itself -- so what a person needs is the list of columns, not the number.
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Recorded channels", style = MaterialTheme.typography.titleSmall)
            LogFormat.describe(i.signature).forEachIndexed { n, name ->
                Text("${n + 1}.  $name", style = MaterialTheme.typography.bodySmall)
            }
            Text("${LogFormat.summary(i.signature)}  ·  code 0x%04X".format(i.signature),
                 Modifier.testTag("format-summary"),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Board clock, and one button to put the phone's time on it. */
@Composable
private fun ClockCard(vm: DeviceViewModel, s: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clock", style = MaterialTheme.typography.titleMedium)
            s.boardTime?.let {
                Text(it, Modifier.testTag("board-time"),
                     style = MaterialTheme.typography.bodySmall,
                     fontFamily = FontFamily.Monospace)
            }
            Text("Sets date, time and time zone from this phone, to the second.",
                 style = MaterialTheme.typography.bodySmall)
            s.clockWarning?.let {
                Text(it, Modifier.testTag("clock-warning"),
                     color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
            // El desfase de la ULTIMA DESCARGA, que es el que se grabo en los metadatos del
            // CSV y con el que se corrigen las marcas. Puede diferir del de arriba si el
            // reloj se ha vuelto a leer despues.
            s.metadata?.offsetDescription()?.let {
                Text("Offset recorded with the downloaded data: $it",
                     Modifier.testTag("clock-offset-recorded"),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.checkClock() }, enabled = !s.busy,
                               modifier = Modifier.testTag("check-clock")) {
                    Text("Check offset")
                }
                Button(onClick = { vm.requestSyncClock() }, enabled = !s.busy,
                       modifier = Modifier.testTag("sync-clock")) { Text("Synchronize") }
            }
        }
    }
}

/**
 * Los dos avisos que preceden a sincronizar el reloj, en pasos separados.
 *
 * El primero trata de DATOS que se perderian; el segundo, de como quedara configurada la
 * placa. Son preguntas independientes y por eso no se funden en un dialogo con cuatro
 * botones, donde ninguna de las dos se leeria entera.
 */
@Composable
private fun SyncDialog(vm: DeviceViewModel, p: SyncPrompt) {
    fun tz(h: Int) = if (h >= 0) "UTC+$h" else "UTC$h"
    when (p.stage) {
        SyncStage.DATA_AT_RISK -> AlertDialog(
            onDismissRequest = { vm.cancelSync() },
            modifier = Modifier.testTag("sync-warning"),
            title = { Text("Download the data first?") },
            text = {
                Text("Setting the clock resets the board's offset to zero. The drift that " +
                     "the records already in memory were written with becomes impossible " +
                     "to measure, so their timestamps can no longer be corrected.\n\n" +
                     "Download the log first, then synchronize.")
            },
            confirmButton = {
                TextButton(onClick = { vm.cancelSync() },
                           modifier = Modifier.testTag("sync-download-first")) {
                    Text("Download first")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.continueToTimeZoneStep() },
                           modifier = Modifier.testTag("sync-anyway")) {
                    Text("Synchronize anyway")
                }
            },
        )
        SyncStage.TIMEZONE -> AlertDialog(
            onDismissRequest = { vm.cancelSync() },
            modifier = Modifier.testTag("tz-warning"),
            title = { Text("Time zones differ") },
            text = {
                Text("The board is set to ${tz(p.boardTz ?: 0)} and this phone is on " +
                     "${tz(p.phoneTz)}.\n\n" +
                     "\"Change both\" makes the board adopt this phone's time zone, which " +
                     "also shifts its clock. \"Time only\" corrects the clock and keeps the " +
                     "board's own time zone.")
            },
            confirmButton = {
                TextButton(onClick = { vm.syncClock(ClockSyncMode.BOTH) },
                           modifier = Modifier.testTag("tz-both")) { Text("Change both") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.cancelSync() },
                               modifier = Modifier.testTag("tz-cancel")) { Text("Cancel") }
                    TextButton(onClick = { vm.syncClock(ClockSyncMode.TIME_ONLY) },
                               modifier = Modifier.testTag("tz-time-only")) { Text("Time only") }
                }
            },
        )
    }
}

@Composable
private fun ConfigCard(vm: DeviceViewModel, s: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)
            // The form is generated from the variable table, not from hand-written screens:
            // adding a variable to the firmware is adding a row to the descriptor.
            //
            // En modo normal solo se ofrece el intervalo: es el unico que se cambia en
            // terreno y el unico cuyo efecto se entiende sin tener el firmware delante.
            Variables.ALL.filter { s.advanced || it.code == "INT" }.forEach { spec ->
                if (spec.code == "INT") IntervalRow(vm, s, spec) else VariableRow(vm, s, spec)
            }
            if (!s.advanced) {
                Text("More settings are available in Advanced mode.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The measurement interval, with a unit selector.
 *
 * The board always speaks seconds; the unit only saves counting zeros. Six hours is 21600,
 * and a mistyped digit there is a deployment that samples ten times too often and fills the
 * flash, or ten times too rarely and misses the event.
 */
@Composable
private fun IntervalRow(vm: DeviceViewModel, s: UiState, spec: cl.umag.glaciertemp.core.VariableSpec) {
    val current = s.variables[spec.code]
    // Seconds by default, so the field shows exactly what the board stores. Offering 600 s
    // as "10" with the unit elsewhere invites reading it as ten seconds, and an interval
    // misread by a factor of sixty is a ruined deployment.
    var unit by remember(current) { mutableStateOf(TimeUnit.SECONDS) }
    var text by remember(current, unit) {
        mutableStateOf(current?.toLongOrNull()?.let { (it / unit.seconds).toString() } ?: "")
    }
    var menu by remember { mutableStateOf(false) }

    val typed = text.toLongOrNull()
    val seconds = typed?.let { unit.toSeconds(it) }
    val problem = when {
        text.isBlank() -> null
        typed == null -> "Whole number required"
        seconds == null -> null
        else -> spec.validate(seconds)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("${spec.code} — ${spec.label}") },
                isError = problem != null,
                supportingText = { problem?.let { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("var-${spec.code}"),
            )
            // Apilados y abreviados: en fila, los dos botones estrechaban el campo hasta
            // que su etiqueta se partia en tres lineas.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    OutlinedButton(
                        onClick = { menu = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("unit-${spec.code}"),
                    ) { Text(unit.short) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        TimeUnit.entries.forEach { u ->
                            DropdownMenuItem(
                                text = { Text("${u.short}  —  ${u.label}") },
                                onClick = { unit = u; menu = false },
                                modifier = Modifier.testTag("unit-option-${u.name}"),
                            )
                        }
                    }
                }
                Button(
                    onClick = { seconds?.let { vm.setVariable(spec, it) } },
                    enabled = !s.busy && problem == null && seconds != null,
                    modifier = Modifier.testTag("set-${spec.code}"),
                ) { Text("Set") }
            }
        }
        // The value actually sent, spelled out: the board stores seconds whatever unit was
        // picked, and this is what makes the conversion checkable before pressing Set.
        seconds?.let {
            Text("Sends $it seconds", Modifier.testTag("interval-seconds"),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VariableRow(vm: DeviceViewModel, s: UiState, spec: cl.umag.glaciertemp.core.VariableSpec) {
    var text by remember(s.variables[spec.code]) {
        mutableStateOf(s.variables[spec.code] ?: "")
    }
    val parsed = text.toLongOrNull()
    // Beware `parsed?.let { validate(it) } ?: "not an integer"`: let returns null both when
    // parsing fails and when validation PASSES, so a correct value fell through to the error
    // message and left the button disabled.
    val problem = when {
        text.isBlank() -> null
        parsed == null -> "Whole number required"
        else -> spec.validate(parsed)
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("${spec.code} — ${spec.label}") },
            suffix = { if (spec.unit.isNotEmpty()) Text(spec.unit) },
            isError = problem != null,
            supportingText = { problem?.let { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("var-${spec.code}"),
        )
        Button(
            onClick = { parsed?.let { vm.setVariable(spec, it) } },
            enabled = !s.busy && problem == null && parsed != null,
            modifier = Modifier.testTag("set-${spec.code}"),
        ) { Text("Set") }
    }
}

@Composable
private fun DownloadCard(vm: DeviceViewModel, s: UiState) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val total = s.info?.recordCount ?: 0L
    val from = fromText.toLongOrNull()
    val to = toText.toLongOrNull()
    val count = if (from != null && to != null) (to - from + 1).coerceAtLeast(0) else total
    val bytes = count * (s.info?.recordBytes ?: 12)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Download", style = MaterialTheme.typography.titleMedium)
            Text("Usually you only want what is new. Leave the range empty to download everything.",
                 style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fromText, { fromText = it }, label = { Text("From") },
                    singleLine = true, modifier = Modifier.weight(1f).testTag("range-from"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(toText, { toText = it }, label = { Text("To") },
                    singleLine = true, modifier = Modifier.weight(1f).testTag("range-to"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("$count records  ·  ${"%.1f".format(bytes / 1024.0)} KiB",
                 Modifier.testTag("download-estimate"))

            // El cuadro para fijar los registros por peticion se retiro: la descarga
            // encuentra el tamano sola y se queda con el, asi que teclearlo era pedirle al
            // usuario que adivinara un numero que la app ya sabe medir.

            s.progress?.let { p ->
                LinearProgressIndicator({ p.fraction }, Modifier.fillMaxWidth().testTag("progress"))
                // BoardClock.format y no Stats.formatSpan: este ultimo esta pensado para
                // despliegues y por debajo de un minuto devuelve "0 min", que es justo el
                // tramo final de una descarga y el momento en que uno mira la cifra.
                val eta = if (p.secondsRemaining.isNaN()) "estimating"
                          else BoardClock.format(p.secondsRemaining.toLong().coerceAtLeast(0))
                Text("${p.recordsDone} / ${p.recordsTotal} records  ·  " +
                     "${"%.1f".format(p.recordsPerSecond)} rec/s  ·  $eta left" +
                     if (p.recordsPerRequest > 0) "  ·  ${p.recordsPerRequest}/request" else "",
                     Modifier.testTag("progress-text"),
                     style = MaterialTheme.typography.bodySmall)
            }

            s.downloadSummary?.let {
                Text(it, Modifier.testTag("download-summary"),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.download(from, to) },
                    enabled = !s.busy,
                    modifier = Modifier.testTag("download"),
                ) { Text("Download") }
                if (s.progress != null) {
                    OutlinedButton(
                        onClick = { vm.abortDownload() },
                        modifier = Modifier.testTag("abort"),
                    ) { Text("Abort") }
                }
            }
            LiveRow(vm, s)
            // Solo en modo avanzado: es la via de recuperacion cuando el log no se puede
            // interpretar, no una descarga corriente, y son minutos de volcado.
            if (s.advanced) {
                RawLogRow(vm, s)
            }
            ResetCounterRow(vm, s)
        }
    }
}

/**
 * Los sensores en directo, debajo de la descarga.
 *
 * Nada de esto se graba: es para apuntar la sonda a algo y ver como responde. Por eso los
 * numeros van grandes --se leen de lejos, con el aparato en la mano y la vista en el
 * sensor-- y por eso la lectura se queda en pantalla al parar, marcada como vieja en vez de
 * borrada: lo ultimo que se vio suele ser lo que se estaba buscando.
 */
@Composable
private fun LiveRow(vm: DeviceViewModel, s: UiState) {
    val proto = s.info?.protocol ?: 0
    // Se ofrece solo si la placa lo entiende. Un boton que manda un comando que la placa no
    // conoce devuelve "Unrecognized command" y deja al usuario adivinando de quien es la
    // culpa; no ofrecerlo dice lo mismo sin necesidad de probarlo.
    if (proto < Protocol.LIVE_PROTOCOL) return
    val campos = s.info?.let { LogFormat.fields(it.signature) } ?: return

    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (s.live.running) {
            Button(onClick = { vm.stopLive() },
                   colors = ButtonDefaults.buttonColors(
                       containerColor = MaterialTheme.colorScheme.error),
                   modifier = Modifier.testTag("live-stop")) { Text("Stop") }
        } else {
            Button(onClick = { vm.startLive() }, enabled = !s.busy,
                   modifier = Modifier.testTag("live-start")) { Text("See live data") }
        }
        if (s.live.samples > 0) {
            Text("${s.live.samples} samples",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (s.live.values.isNotEmpty()) {
        // Dos columnas: en un telefono en vertical, tres dejan los numeros de cuatro cifras
        // partidos por la mitad, y una sola obliga a desplazarse para ver el ultimo canal.
        val enPares = campos.indices.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp),
               modifier = Modifier.testTag("live-panel")) {
            enPares.forEach { fila ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    fila.forEach { i ->
                        LiveReading(
                            nombre = campos[i].name,
                            valor = s.live.values.getOrNull(i) ?: "—",
                            unidad = LogFormat.unitOf(campos[i].name),
                            apagado = s.live.stale,
                            modifier = Modifier.weight(1f).testTag("live-${campos[i].name}"),
                        )
                    }
                    // Rellena el hueco de una fila impar para que la ultima lectura no se
                    // estire al doble de ancho que las demas.
                    if (fila.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            s.live.time?.let {
                Text(if (s.live.stale) "Last reading: $it" else "Board time: $it",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.testTag("live-time"))
            }
        }
    }
    Text("Reads the sensors without recording anything. The board stops on its own after " +
         "ten minutes.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Un numero grande con su nombre encima y su unidad al lado. */
@Composable
private fun LiveReading(
    nombre: String,
    valor: String,
    unidad: String,
    apagado: Boolean,
    modifier: Modifier = Modifier,
) {
    // Apagado, no oculto: al parar, la ultima lectura sigue siendo util, pero tiene que
    // distinguirse de una que se esta refrescando ahora mismo.
    val color = if (apagado) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onBackground
    Column(modifier) {
        Text(nombre, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valor,
                 style = MaterialTheme.typography.headlineSmall,
                 fontFamily = FontFamily.Monospace,
                 color = color)
            if (unidad.isNotEmpty()) {
                Text(" $unidad", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Reiniciar el contador de la placa, al final de la zona de descarga.
 *
 * Va con un dialogo porque es irreversible desde la app, y el dialogo dice lo que de verdad
 * pasa en vez de un "estas seguro" generico: el comando NO borra la flash, mueve el contador
 * a cero. La descarga normal deja de alcanzar los datos, pero siguen fisicamente ahi hasta
 * que la placa tome mas mediciones, que escriben desde el principio y los van sobrescribiendo.
 *
 * Esa diferencia es la que le sirve a alguien que acaba de equivocarse: el volcado crudo
 * todavia puede rescatarlos, y solo antes de que la placa vuelva a medir.
 */
@Composable
private fun ResetCounterRow(vm: DeviceViewModel, s: UiState) {
    var confirmar by remember { mutableStateOf(false) }
    val registros = s.info?.recordCount ?: 0L
    val sinDescargar = s.info?.boardId != null && s.records.isEmpty()

    HorizontalDivider()
    OutlinedButton(
        onClick = { confirmar = true },
        enabled = !s.busy,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag("reset-counter"),
    ) { Text("Reset counter") }
    Text("Starts a new log. The board will record from the beginning again.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            modifier = Modifier.testTag("reset-warning"),
            title = { Text("Reset the counter?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The $registros records now on the board will no longer be " +
                         "downloadable: the counter goes back to zero and Download can no " +
                         "longer reach them.")
                    Text("They are not erased yet. They stay in the flash until the board " +
                         "takes its next measurements, which start writing from the " +
                         "beginning and overwrite them one by one. Until then, the raw " +
                         "memory dump could still recover them.")
                    if (sinDescargar) {
                        Text("You have not downloaded anything from this board in this " +
                             "session. Download first.",
                             color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmar = false; vm.resetCounter() },
                           modifier = Modifier.testTag("reset-confirm")) {
                    Text("Reset counter")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = false },
                           modifier = Modifier.testTag("reset-cancel")) { Text("Cancel") }
            },
        )
    }
}

/**
 * El volcado crudo de la memoria entera como Intel HEX.
 *
 * Se ofrece en cualquier transporte, pero con el tiempo estimado DELANTE y calculado a
 * partir del enlace: por cable son unos diecisiete minutos y por BLE mas de media hora, y
 * quien pulsa sin saberlo da la descarga por colgada a los cinco.
 *
 * Se escribe directamente al fichero que elige el usuario, segun llega: diez megas de datos
 * son unos veintiocho de linea en Intel HEX, y ademas una descarga interrumpida a los veinte
 * minutos no puede perderse entera.
 */
@Composable
private fun RawLogRow(vm: DeviceViewModel, s: UiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val name = remember(s.info) { vm.rawLogName() }
    var confirmar by remember { mutableStateOf(false) }

    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) vm.downloadRawLog(uri, ctx.contentResolver) }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirmar = true }, enabled = !s.busy,
                       modifier = Modifier.testTag("raw-log")) { Text("Raw log as Intel HEX") }
        s.rawLogProgress?.let {
            Text(it, Modifier.testTag("raw-log-progress"),
                 style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Dumps the whole flash without decoding it. The way back when the log itself " +
         "cannot be interpreted.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            modifier = Modifier.testTag("raw-log-confirm"),
            title = { Text("Download the whole memory?") },
            text = { Text(vm.rawLogEstimate()) },
            confirmButton = {
                TextButton(onClick = { confirmar = false; saver.launch(name) },
                           modifier = Modifier.testTag("raw-log-go")) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Se pregunta por la posicion al TERMINAR la descarga: pase lo que pase con la respuesta,
 * los datos ya estan en el telefono.
 *
 * El boton de continuar sin posicion sigue disponible DURANTE la espera. Una espera de la
 * que no se puede salir obligaria a matar la app, y con ella se perderia la descarga recien
 * hecha -- que es peor que no haber ofrecido esperar.
 */
@Composable
private fun LocationDialog(vm: DeviceViewModel, p: LocationPrompt) {
    val last = p.lastKnown
    AlertDialog(
        onDismissRequest = { },
        modifier = Modifier.testTag("location-prompt"),
        title = { Text(if (p.waiting) "Waiting for a position" else "No fresh position") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (last != null) {
                    Text("Last known position: %.5f, %.5f".format(last.latitude, last.longitude))
                    Text("Taken ${BoardClock.format(last.ageSeconds)} ago" +
                         (last.accuracyMetres?.let { ", accuracy %.0f m".format(it) } ?: ""),
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Use it only if you have not moved since then.",
                         style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("No position is available on this phone yet.")
                }
                if (p.waiting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Still waiting. You can continue without a position at any time.",
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.useLastKnownLocation() },
                       enabled = last != null && !p.waiting,
                       modifier = Modifier.testTag("loc-use-last")) { Text("Use this one") }
        },
        dismissButton = {
            Row {
                // SIEMPRE habilitado, tambien mientras se espera: es la salida.
                TextButton(onClick = { vm.continueWithoutLocation() },
                           modifier = Modifier.testTag("loc-skip")) { Text("No position") }
                if (!p.waiting) {
                    TextButton(onClick = { vm.waitForFreshLocation() },
                               modifier = Modifier.testTag("loc-wait")) { Text("Wait for one") }
                }
            }
        },
    )
}

@Composable
private fun BatteryCard(vm: DeviceViewModel, s: UiState) {
    val b = s.battery
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Battery", style = MaterialTheme.typography.titleMedium)

            Box {
                OutlinedButton(onClick = { menuOpen = true },
                               modifier = Modifier.testTag("battery-type")) {
                    Text(b.type.label)
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    BatteryType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.label) },
                            onClick = { menuOpen = false; vm.setBatterySettings(b.copy(type = t)) },
                            modifier = Modifier.testTag("battery-option-${t.name}"),
                        )
                    }
                }
            }

            if (b.type == BatteryType.CUSTOM) {
                var txt by remember(b.customMah) { mutableStateOf(b.customMah.toString()) }
                OutlinedTextField(
                    value = txt,
                    onValueChange = {
                        txt = it
                        it.toIntOrNull()?.let { v -> vm.setBatterySettings(b.copy(customMah = v)) }
                    },
                    label = { Text("Capacidad") }, suffix = { Text("mAh") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("battery-mah"),
                )
            }

            when (val e = s.batteryEstimate) {
                null -> Text("Download data to estimate", Modifier.testTag("battery-text"),
                             style = MaterialTheme.typography.bodySmall)
                is BatteryEstimate.Unavailable -> Text(
                    when (e.reason) {
                        BatteryUnknown.NO_VOLTAGE_CHANNEL ->
                            "The log has no battery-voltage channel"
                        BatteryUnknown.TOO_FEW_POINTS ->
                            "At least ${Battery.MIN_POINTS} readings are needed"
                        BatteryUnknown.TOO_SHORT ->
                            "At least one day of records is needed"
                        BatteryUnknown.NOT_DISCHARGING ->
                            "Voltage is not dropping measurably: a fresh battery, a solar panel, " +
                            "or the cell warming back up after the cold"
                    },
                    Modifier.testTag("battery-text"), style = MaterialTheme.typography.bodySmall)
                is BatteryEstimate.Available -> Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("%.0f %% left  ·  %.0f mV/cell".format(e.percentNow, e.currentMv),
                         Modifier.testTag("battery-text"))
                    Text("Consumption: %.1f mAh/day".format(e.mahPerDay),
                         Modifier.testTag("battery-consumption"))
                    Text("Estimated life left: %.0f days".format(e.daysRemaining),
                         Modifier.testTag("battery-days"))
                    Text("Fitted over %d readings across %.1f days".format(e.points, e.spanDays),
                         style = MaterialTheme.typography.bodySmall)
                    if (e.lowConfidence) {
                        Text("The lithium plateau is very flat: voltage tells little about the " +
                             "state of charge until the very end. Treat as indicative.",
                             Modifier.testTag("battery-warning"),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                    Text("The curve is a lab curve at ~21 degC; in the cold it reads low and " +
                         "recovers as the cell warms.",
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(vm: DeviceViewModel, s: UiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val name = remember(s.records) { vm.exportName() }
    // SAF: the user picks where to save and no storage permission is needed.
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) vm.onExported(false, name)
        else runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.csvBytes()) }
        }.onSuccess { vm.onExported(true, name) }
         .onFailure { vm.onExported(false, name) }
    }

    val sig = s.signature
    // NO se formatean las filas por adelantado. Con cien mil registros eran cien mil cadenas
    // construidas de golpe, y como la pestana se destruye al cambiar a Terminal, volvian a
    // construirse enteras cada vez que se regresaba -- de ahi el tiron al volver. LazyColumn
    // solo compone lo que se ve, asi que formatear dentro de items() cuesta lo que se ve.
    val rowCount = s.records.size
    // Bounded to a fraction of the screen instead of a fixed number of dp, so it stays
    // proportionate on a small phone and on a tablet. Letting it grow with the data made a
    // page thousands of rows long: reaching the end took dozens of swipes.
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current
        .screenHeightDp * 0.5f).dp

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Data", style = MaterialTheme.typography.titleMedium)
            Text("${s.records.size} records  ·  $name",
                 Modifier.testTag("export-name"), style = MaterialTheme.typography.bodySmall)
            val puedeCorregir = remember(s.records, s.metadata) {
                cl.umag.glaciertemp.core.TimeCorrection.isAvailable(s.records, s.metadata)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.exportCorrected && puedeCorregir,
                         onCheckedChange = { vm.setExportCorrected(it) },
                         enabled = puedeCorregir,
                         modifier = Modifier.testTag("export-corrected"))
                Column {
                    Text("Include drift-corrected time column",
                         style = MaterialTheme.typography.bodyMedium)
                    if (!puedeCorregir) {
                        Text("Needs a clock offset measured during a download.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Apagada por defecto: la exportacion tiene que seguir siendo un boton y no un
            // formulario. Quien solo quiere el fichero no deberia saltarse un campo vacio.
            var conNota by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = conNota,
                         onCheckedChange = {
                             conNota = it
                             if (!it) vm.setExportNote(null)
                         },
                         modifier = Modifier.testTag("export-note-toggle"))
                Text("Add a note", style = MaterialTheme.typography.bodyMedium)
            }
            if (conNota) {
                OutlinedTextField(
                    value = s.exportNote.orEmpty(),
                    onValueChange = { vm.setExportNote(it) },
                    label = { Text("Note") },
                    placeholder = { Text("Anything worth knowing about this download") },
                    modifier = Modifier.fillMaxWidth().testTag("export-note"),
                    minLines = 2,
                )
            }
            Button(onClick = { saver.launch(name) }, enabled = !s.busy,
                   modifier = Modifier.testTag("export")) { Text("Save CSV") }

            // The header stays put while the rows scroll under it: without it, a column of
            // bare numbers says nothing once the first line is out of view.
            sig?.let {
                Text(CsvExporter.header(it), Modifier.testTag("csv-preview"),
                     fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                     color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            val listState = rememberLazyListState()
            val alcance = rememberCoroutineScope()
            Row(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                LazyColumn(
                    state = listState,
                    // Its own scroll, with a hard height: nested inside the page's scroll it
                    // would otherwise be measured with unbounded height and crash.
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .testTag("csv-rows"),
                ) {
                    items(s.records) { record ->
                        Text(if (sig != null) CsvExporter.row(record, sig) else "",
                             fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                             maxLines = 1)
                    }
                }
                VerticalScrollbar(listState, rowCount, Modifier.fillMaxHeight())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { alcance.launch { listState.scrollToItem(0) } },
                           modifier = Modifier.testTag("rows-top")) { Text("First") }
                TextButton(
                    onClick = {
                        alcance.launch {
                            listState.scrollToItem((rowCount - 1).coerceAtLeast(0))
                        }
                    },
                    modifier = Modifier.testTag("rows-end"),
                ) { Text("Last") }
            }
        }
    }
}


/**
 * Barra de desplazamiento para una lista larga.
 *
 * Compose no trae una para LazyColumn. Con miles de filas, deslizar hasta el final son
 * decenas de gestos; el pulgar convierte eso en un arrastre. Su tamano es proporcional a lo
 * que se ve, asi que ademas da idea de cuanto hay.
 */
@Composable
private fun VerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 0) return
    val alcance = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.primary
    val visibles = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val fraccionVisible = (visibles.toFloat() / itemCount).coerceIn(0.05f, 1f)
    val posicion =
        if (itemCount <= visibles) 0f
        else (state.firstVisibleItemIndex.toFloat() / (itemCount - visibles)).coerceIn(0f, 1f)

    androidx.compose.foundation.Canvas(
        modifier
            .width(14.dp)
            .testTag("csv-scrollbar")
            .pointerInput(itemCount, visibles) {
                // Se salta a donde se toca o se arrastra: es lo que hace util una barra
                // frente a deslizar la lista.
                fun saltar(y: Float, alto: Float) {
                    val f = (y / alto).coerceIn(0f, 1f)
                    val destino = (f * (itemCount - visibles)).toInt()
                        .coerceIn(0, (itemCount - 1).coerceAtLeast(0))
                    alcance.launch { state.scrollToItem(destino) }
                }
                detectDragGestures(
                    onDragStart = { offset -> saltar(offset.y, size.height.toFloat()) },
                ) { change, _ -> saltar(change.position.y, size.height.toFloat()) }
            }
    ) {
        val altoPulgar = size.height * fraccionVisible
        val y = (size.height - altoPulgar) * posicion
        drawRoundRect(
            color = color.copy(alpha = 0.35f),
            topLeft = androidx.compose.ui.geometry.Offset(size.width / 3f, y),
            size = androidx.compose.ui.geometry.Size(size.width / 3f, altoPulgar),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 6f),
        )
    }
}
