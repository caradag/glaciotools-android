package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.geo.*

private fun f(v: Double, d: Int) = "%.${d}f".format(java.util.Locale.ROOT, v)

private fun cuando(ms: Long): String = java.time.format.DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

private fun duracion(s: Long): String = when {
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min ${s % 60} s"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

/** La herramienta entera: lista, promediado y detalle de un punto. */
@Composable
fun GpsToolScreen(vm: GpsViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    var explicar by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // El atras del sistema retrocede una pantalla dentro de la herramienta antes de salir
    // de ella. Desde la pantalla de medir NO se sale sin mas: se deja que el boton Done
    // haga su trabajo, que es el que pregunta por lo que no se ha guardado.
    androidx.activity.compose.BackHandler(enabled = s.openPoint != null) { vm.closePoint() }

    Column(Modifier.fillMaxSize()) {
        ToolBar("GPS tools", onBack = onBack) {
            // El icono explica el PROMEDIADO, asi que solo en su pestana: en la del reloj
            // abriria un texto que no habla de lo que se esta mirando.
            if (tab == 0) {
                IconButton(onClick = { explicar = true },
                           modifier = Modifier.testTag("gps-info")) {
                    Icon(Icons.Outlined.Info, contentDescription = "How the averaging works")
                }
            }
        }

        // Pestanas, como en la pantalla del aparato. El promediado deja de ser "la
        // herramienta de GPS" entera y pasa a ser una de sus herramientas, que es lo que
        // hace sitio para las que vienen detras.
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("Average") }, modifier = Modifier.testTag("gps-tab-average"))
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("GPS time") }, modifier = Modifier.testTag("gps-tab-time"))
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> when {
                    s.averaging != null -> AveragingScreen(vm, s, s.averaging!!)
                    s.openPoint != null -> PointScreen(vm, s, s.openPoint!!)
                    else -> PointListScreen(vm, s)
                }
                else -> GpsTimeScreen()
            }
        }
    }

    if (explicar) AveragingExplained { explicar = false }
}

@Composable
private fun PointListScreen(vm: GpsViewModel, s: GpsUiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var explicar by remember { mutableStateOf(false) }
    var exportar by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    var formato by remember { mutableStateOf(GpsViewModel.Format.CSV) }

    // El cuadro del sistema es el que deja elegir carpeta; el nombre se le pasa propuesto y
    // el usuario lo cambia ahi mismo si quiere.
    val guardador = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val datos = vm.exportSelectedBytes(formato)
        if (datos.isEmpty()) { vm.onExported(false, "selection"); return@rememberLauncherForActivityResult }
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(datos) } }
            .onSuccess { vm.onExported(true, "${s.selected.size} point(s)") }
            .onFailure { vm.onExported(false, "${s.selected.size} point(s)") }
        vm.clearSelection()
    }

    Column(Modifier.fillMaxSize().padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Records GNSS fixes for as long as you like and combines them. " +
             "Nothing is uploaded anywhere.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(onClick = { vm.startAveraging() },
               modifier = Modifier.testTag("gps-new")) { Text("New point") }

        Aviso(vm, s)

        if (s.points.isEmpty()) {
            Text("No points yet.", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("gps-empty"))
        } else {
            // La barra de acciones solo aparece cuando hay algo marcado: ocupar sitio
            // permanentemente para dos botones que casi nunca se usan estrecha la lista.
            if (s.selected.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("gps-actions")) {
                    Text("${s.selected.size} selected",
                         style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { exportar = true },
                               modifier = Modifier.testTag("gps-export-selected")) {
                        Text("Export")
                    }
                    TextButton(onClick = { borrar = true },
                               modifier = Modifier.testTag("gps-delete-selected")) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { vm.clearSelection() },
                               modifier = Modifier.testTag("gps-clear-selection")) {
                        Text("Clear")
                    }
                }
            }

            LazyColumn(Modifier.weight(1f).testTag("gps-list"),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.points, key = { it.id }) { p ->
                    val marcado = p.id in s.selected
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = marcado,
                                     onCheckedChange = { vm.toggleSelected(p.id) },
                                     modifier = Modifier.testTag("gps-check-${p.id}"))
                            // El resto de la tarjeta sigue abriendo el punto: marcar y abrir
                            // son dos gestos distintos sobre zonas distintas, sin modos.
                            Column(Modifier.weight(1f)
                                .then(Modifier.padding(vertical = 12.dp))) {
                                Text(p.name.ifBlank { "(unnamed)" },
                                     style = MaterialTheme.typography.titleSmall)
                                Text("${p.samples} fixes  ·  last ${cuando(p.lastEpochMillis)}",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { vm.open(p.id) },
                                       modifier = Modifier.testTag("gps-open-${p.id}")) {
                                Text("Open")
                            }
                        }
                    }
                }
            }
            if (s.points.size > 1) {
                TextButton(onClick = {
                    if (s.selected.size == s.points.size) vm.clearSelection() else vm.selectAll()
                }, modifier = Modifier.testTag("gps-select-all")) {
                    Text(if (s.selected.size == s.points.size) "Select none" else "Select all")
                }
            }
        }
    }

    if (explicar) AveragingExplained { explicar = false }

    if (exportar) {
        var nombre by remember { mutableStateOf(vm.defaultSelectionName(formato)) }
        AlertDialog(
            onDismissRequest = { exportar = false },
            modifier = Modifier.testTag("gps-export-dialog"),
            title = { Text("Export ${s.selected.size} point(s)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Only the final solution of each point: one row per point, not the " +
                         "individual fixes.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = formato == GpsViewModel.Format.CSV,
                                   onClick = {
                                       formato = GpsViewModel.Format.CSV
                                       nombre = vm.defaultSelectionName(formato)
                                   },
                                   label = { Text("CSV") },
                                   modifier = Modifier.testTag("gps-sel-csv"))
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = formato == GpsViewModel.Format.GPX,
                                   onClick = {
                                       formato = GpsViewModel.Format.GPX
                                       nombre = vm.defaultSelectionName(formato)
                                   },
                                   label = { Text("GPX") },
                                   modifier = Modifier.testTag("gps-sel-gpx"))
                    }
                    OutlinedTextField(nombre, { nombre = it }, singleLine = true,
                                      label = { Text("File name") },
                                      modifier = Modifier.testTag("gps-sel-name"))
                    Text("You pick the folder in the next screen.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { exportar = false; guardador.launch(nombre) },
                           modifier = Modifier.testTag("gps-sel-go")) { Text("Choose folder") }
            },
            dismissButton = {
                TextButton(onClick = { exportar = false }) { Text("Cancel") }
            })
    }

    if (borrar) {
        val cuantos = s.selected.size
        val muestras = s.points.filter { it.id in s.selected }.sumOf { it.samples }
        AlertDialog(
            onDismissRequest = { borrar = false },
            modifier = Modifier.testTag("gps-delete-many-warning"),
            title = { Text("Delete $cuantos point(s)?") },
            text = { Text("Their $muestras fixes go with them, and this cannot be undone. " +
                          "Export them first if you may want them.") },
            confirmButton = {
                TextButton(onClick = { borrar = false; vm.deleteSelected() },
                           modifier = Modifier.testTag("gps-delete-many-confirm")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { borrar = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Aviso(vm: GpsViewModel, s: GpsUiState) {
    s.error?.let {
        Text("Error: $it", color = MaterialTheme.colorScheme.error,
             style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.testTag("gps-error"))
    }
    s.note?.let {
        Text(it, color = MaterialTheme.colorScheme.primary,
             style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.testTag("gps-note"))
    }
}

/**
 * Que hace la app con los arreglos, en castellano llano.
 *
 * Existe porque cada una de estas decisiones cambia el numero que sale en pantalla, y quien
 * lo anota en una libreta tiene derecho a saber de donde viene. Sin esto, "± 0,4 m" es un
 * numero que hay que creerse.
 */
@Composable
private fun AveragingExplained(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("gps-info-dialog"),
        title = { Text("How the averaging works") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                   verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Parrafo("Why average at all",
                    "A single GNSS fix is off by a few metres. The error is not constant: " +
                    "it wanders as satellites move and as the signal bounces off rock, ice " +
                    "and buildings. Collect many fixes and the wandering partly cancels, so " +
                    "the combined position is better than any single reading.")

                Parrafo("Fixes are not all equal",
                    "Your receiver reports, with every fix, how good it thinks that fix is — " +
                    "a radius in metres. We use it as a weight: a fix it calls good counts " +
                    "more than one it calls poor. A fix reported as twice as accurate counts " +
                    "four times as much, which is the weighting that makes the result as " +
                    "precise as it can be.")

                Parrafo("Throwing out the wild ones",
                    "Every so often a receiver produces a fix hundreds of metres away — and " +
                    "sometimes it reports that one as accurate, so weighting alone would give " +
                    "it MORE influence. So before combining anything we discard the fixes " +
                    "that sit far from the rest, and the screen tells you how many went.")

                Parrafo("What MAD means",
                    "MAD is short for median absolute deviation, and it is simply a robust " +
                    "way to say \u201chow spread out are these numbers\u201d.\n\n" +
                    "Take the middle value of all your fixes — the median. For each fix, " +
                    "measure how far it is from that middle, ignoring the sign. Now take the " +
                    "middle value of THOSE distances. That is the MAD.\n\n" +
                    "It is used instead of the usual standard deviation because the standard " +
                    "deviation is itself dragged around by the wild fixes we are trying to " +
                    "find: one fix 500 m away inflates it enormously, and then nothing looks " +
                    "unusual any more. The MAD barely notices that fix, so it keeps a clean " +
                    "sense of the normal spread. A fix further than five MADs from the middle " +
                    "is treated as a failure, not as data.")

                Parrafo("Why time matters more than count",
                    "Fixes taken one second apart are almost the same measurement repeated. " +
                    "The things that cause the error — where the satellites are, the state of " +
                    "the ionosphere, what the signal is bouncing off — change over minutes, " +
                    "not seconds. So a thousand fixes in twenty minutes do NOT contain a " +
                    "thousand independent pieces of information: they contain a handful, " +
                    "repeated.\n\n" +
                    "The app measures how much each fix resembles the one before it and works " +
                    "out how many independent fixes you effectively have. That is the " +
                    "\u201ceffective\u201d number shown under the chart. It is usually far " +
                    "smaller than the raw count, and that is honest rather than pessimistic.")

                Parrafo("Coming back another day is worth a lot",
                    "A second visit sees a different satellite geometry and a different " +
                    "atmosphere, so it really does bring new information. Each visit is " +
                    "combined according to how much it independently contributes — not " +
                    "according to how many fixes it happens to contain.\n\n" +
                    "In practice: twenty minutes on each of three days beats an hour on one " +
                    "day, by a wide margin.")

                Parrafo("The two numbers on screen",
                    "sd is how scattered the fixes are around the estimate. It describes the " +
                    "receiver and the site on that occasion — it tends to settle down as the " +
                    "receiver warms up, and it climbs if you walk under a cliff.\n\n" +
                    "± est. is the uncertainty of the estimate itself: how far the answer is " +
                    "likely to be from the truth. This is the one that improves as you keep " +
                    "measuring, and the one to watch when deciding whether to stop.")

                Parrafo("What it does not account for",
                    "± est. assumes the leftover errors average out. Some do not: a systematic " +
                    "bias from a nearby wall, or from the receiver's own model of the " +
                    "atmosphere, stays put no matter how long you measure. Treat ± est. as a " +
                    "lower bound on your real error, not a guarantee.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun Parrafo(titulo: String, cuerpo: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        Text(cuerpo, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * La pantalla de promediar: dos graficos y las cifras debajo de cada uno.
 *
 * Se puede guardar sin dejar de medir. Guardar escribe solo lo que aun no estaba en disco,
 * asi que pulsarlo cada tanto durante una sesion larga no duplica nada y deja a salvo lo que
 * lleva -- que en terreno, con una bateria que se agota y un telefono que se cae, es la
 * diferencia entre perder diez minutos y perderlo todo.
 */
@Composable
private fun AveragingScreen(vm: GpsViewModel, s: GpsUiState, a: AveragingState) {
    var nombre by remember(a.pointId) { mutableStateOf(a.name) }
    var confirmarSalida by remember { mutableStateOf(false) }
    var explicar by remember { mutableStateOf(false) }
    val st = a.stats

    // La pantalla se queda encendida mientras se mide, y solo mientras se mide.
    //
    // No es comodidad: Android limita las actualizaciones de posicion de una app que no
    // esta en primer plano a unas pocas por HORA. Con la pantalla apagada, una sesion de
    // veinte minutos recogeria un punado de muestras en vez de mil, y el usuario no tendria
    // forma de saber por que su promedio no mejora.
    KeepScreenOn(a.running)

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = nombre, onValueChange = { nombre = it },
                label = { Text("Point name") }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("gps-name"))
            // El icono tambien aqui: es donde se miran las cifras, y es donde surge la
            // pregunta de que significan.
            IconButton(onClick = { explicar = true },
                       modifier = Modifier.testTag("gps-info-measuring")) {
                Icon(Icons.Outlined.Info, contentDescription = "How the averaging works")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${a.samples.size} fixes", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.testTag("gps-count"))
            st?.takeIf { it.sessions > 1 }?.let {
                Text("in ${it.sessions} sessions", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a.running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            st?.let {
                Text("·  ${duracion(it.durationSeconds)}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (s.gpsUnavailable) {
            Text("No GNSS fixes. Check that location is on, that GlacioTools has " +
                 "permission, and that you are outdoors.",
                 color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.testTag("gps-unavailable"))
        } else if (a.waiting && a.running) {
            Text("Waiting for the first fix...",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Aviso(vm, s)

        if (a.outOfZone > 0) {
            // No es un error, pero hay que decirlo: significa que el punto esta sobre un
            // meridiano de zona y que las coordenadas salen en la zona de la primera muestra.
            Text("${a.outOfZone} fixes fell in the neighbouring UTM zone; they are " +
                 "projected into zone ${st?.zone ?: 0} so the cloud stays in one frame.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (st != null) {
            GraficosYCifras(st, a.projected, a.samples.mapNotNull { it.altitudeMetres },
                            etiquetar = true)
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Guardar es ademas terminar: no hay un "Done" aparte, porque tenerlo obligaba a
            // pulsar dos botones para lo unico que uno quiere hacer al acabar.
            Button(onClick = { vm.saveAveraging(nombre) },
                   enabled = a.samples.isNotEmpty(),
                   modifier = Modifier.testTag("gps-save")) { Text("Save and finish") }
            if (a.running) {
                OutlinedButton(onClick = { vm.pauseAveraging() },
                               modifier = Modifier.testTag("gps-pause")) { Text("Pause") }
            } else {
                // Reanudar CONSERVA lo medido: abre un tramo nuevo sobre la misma nube.
                OutlinedButton(onClick = { vm.resumeAveraging() },
                               modifier = Modifier.testTag("gps-resume")) { Text("Resume") }
            }
            OutlinedButton(
                onClick = { if (a.unsaved > 0) confirmarSalida = true else vm.closeAveraging() },
                modifier = Modifier.testTag("gps-discard")) { Text("Discard") }
        }
        if (a.unsaved > 0) {
            Text("${a.unsaved} fixes not saved yet",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
                 modifier = Modifier.testTag("gps-unsaved"))
        }
    }

    if (explicar) AveragingExplained { explicar = false }

    if (confirmarSalida) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = false },
            modifier = Modifier.testTag("gps-discard-warning"),
            title = { Text("Discard ${a.unsaved} fixes?") },
            text = { Text("They have not been saved. Leaving now loses them, and a " +
                          "position takes as long to measure again as it took the first time.") },
            confirmButton = {
                TextButton(onClick = { confirmarSalida = false; vm.closeAveraging() },
                           modifier = Modifier.testTag("gps-discard-confirm")) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSalida = false }) { Text("Keep measuring") }
            })
    }
}

/** Mantiene la pantalla encendida mientras [activo], y la suelta al salir. */
@Composable
private fun KeepScreenOn(activo: Boolean) {
    val vista = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(activo) {
        vista.keepScreenOn = activo
        onDispose { vista.keepScreenOn = false }
    }
}

/**
 * Los dos graficos con sus cifras. Lo usan la pantalla de medir y la de ver un punto: son
 * la misma presentacion de los mismos numeros, y tenerla dos veces solo garantiza que un dia
 * se arregle una y no la otra.
 */
@Composable
private fun GraficosYCifras(
    st: GpsPointStats,
    proyectadas: List<Pair<Double, Double>>,
    alturas: List<Double>,
    etiquetar: Boolean,
) {
    // Una vista por grafico, conservada mientras la pantalla vive: acercarse y que el zoom
    // se deshaga solo porque llego una muestra nueva seria inutilizable.
    val vistaNube = rememberChartView()
    val vistaAltura = rememberChartView()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Horizontal", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        if (vistaNube.moved) {
            TextButton(onClick = { vistaNube.reset() },
                       modifier = Modifier.testTag("gps-scatter-reset")) { Text("Reset view") }
        }
    }
    ScatterPlot(proyectadas, st.easting.estimate, st.northing.estimate, vistaNube,
                Modifier.fillMaxWidth().height(260.dp)
                    .then(if (etiquetar) Modifier.testTag("gps-scatter") else Modifier))
    Text("Pinch to zoom, drag to pan.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
    Cifras(st)

    Spacer(Modifier.height(4.dp))
    val alt = st.altitude
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Altitude", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        if (vistaAltura.moved) {
            TextButton(onClick = { vistaAltura.reset() }) { Text("Reset view") }
        }
    }
    if (alt == null) {
        Text("No altitude in these fixes (2D solution).",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        AltitudePlot(alturas, alt, vistaAltura,
                     Modifier.fillMaxWidth().height(180.dp)
                         .then(if (etiquetar) Modifier.testTag("gps-altitude") else Modifier))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigReading("Altitude", f(alt.estimate, 1), "m", Modifier.weight(1f))
            BigReading("sd", f(alt.sd, 2), "m", Modifier.weight(1f))
            BigReading("± est.", f(alt.standardError, 2), "m", Modifier.weight(1f))
        }
    }
}

/**
 * Las tres cifras de la estimacion horizontal.
 *
 * Van las tres juntas porque responden a preguntas distintas y solo una de ellas baja al
 * seguir midiendo. Ensenar solo la dispersion es lo que hace que alguien mire la pantalla
 * diez minutos, vea que el numero no se mueve y concluya que promediar no sirve para nada.
 */
@Composable
private fun Cifras(st: GpsPointStats) {
    Text(st.estimateUtm.format(), style = MaterialTheme.typography.titleMedium,
         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
         modifier = Modifier.testTag("gps-utm"))
    Text("${f(st.estimateLatitude, 6)}, ${f(st.estimateLongitude, 6)}",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         modifier = Modifier.testTag("gps-latlon"))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BigReading("sd East", f(st.easting.sd, 2), "m", Modifier.weight(1f))
        BigReading("sd North", f(st.northing.sd, 2), "m", Modifier.weight(1f))
        BigReading("± est.", f(st.horizontalStandardError, 2), "m", Modifier.weight(1f))
    }
    Text("sd is how much the fixes scatter around the estimate. " +
         "± est. is the uncertainty of the estimate itself: it counts " +
         "${f(st.easting.nEffective, 1)} effective independent fixes out of ${st.samples}, " +
         "because fixes taken seconds apart repeat much of the same error." +
         (if (st.sessions > 1) "  ${st.sessions} separate sessions." else "") +
         (if (st.rejected > 0) "  ${st.rejected} outlier(s) discarded." else ""),
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         modifier = Modifier.testTag("gps-explain"))
}

@Composable
private fun PointScreen(vm: GpsViewModel, s: GpsUiState, p: OpenPoint) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var renombrar by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    var alcance by remember { mutableStateOf(GpsViewModel.Scope.AVERAGE) }
    var formato by remember { mutableStateOf(GpsViewModel.Format.CSV) }

    val guardador = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val nombre = vm.exportName(p, alcance, formato)
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use {
                it.write(vm.exportBytes(p, alcance, formato))
            }
        }.onSuccess { vm.onExported(true, nombre) }
         .onFailure { vm.onExported(false, nombre) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.closePoint() },
                       modifier = Modifier.testTag("gps-back-list")) { Text("‹ Points") }
        }
        Text(p.summary.name.ifBlank { "(unnamed)" },
             style = MaterialTheme.typography.titleLarge,
             modifier = Modifier.testTag("gps-point-name"))
        Text("${p.summary.samples} fixes  ·  created ${cuando(p.summary.createdEpochMillis)}",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Aviso(vm, s)

        val st = p.stats
        if (st == null) {
            Text("This point has no fixes yet.",
                 style = MaterialTheme.typography.bodyMedium)
        } else {
            GraficosYCifras(st, p.projected, p.samples.mapNotNull { it.altitudeMetres },
                            etiquetar = false)
        }

        HorizontalDivider()
        Button(onClick = { vm.startAveraging(p.summary.id, p.summary.name) },
               modifier = Modifier.testTag("gps-continue")) { Text("Keep averaging") }
        Text("Adds more fixes to this same point on another visit.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        Text("Export", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = alcance == GpsViewModel.Scope.AVERAGE,
                       onClick = { alcance = GpsViewModel.Scope.AVERAGE },
                       label = { Text("Median only") },
                       modifier = Modifier.testTag("gps-scope-avg"))
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = alcance == GpsViewModel.Scope.ALL_FIXES,
                       onClick = { alcance = GpsViewModel.Scope.ALL_FIXES },
                       label = { Text("All fixes") },
                       modifier = Modifier.testTag("gps-scope-all"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = formato == GpsViewModel.Format.CSV,
                       onClick = { formato = GpsViewModel.Format.CSV },
                       label = { Text("CSV") },
                       modifier = Modifier.testTag("gps-fmt-csv"))
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = formato == GpsViewModel.Format.GPX,
                       onClick = { formato = GpsViewModel.Format.GPX },
                       label = { Text("GPX") },
                       modifier = Modifier.testTag("gps-fmt-gpx"))
        }
        Button(onClick = { guardador.launch(vm.exportName(p, alcance, formato)) },
               enabled = st != null,
               modifier = Modifier.testTag("gps-export")) { Text("Export") }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { renombrar = true },
                           modifier = Modifier.testTag("gps-rename")) { Text("Rename") }
            OutlinedButton(onClick = { borrar = true },
                           colors = ButtonDefaults.outlinedButtonColors(
                               contentColor = MaterialTheme.colorScheme.error),
                           modifier = Modifier.testTag("gps-delete")) { Text("Delete") }
        }
    }

    if (renombrar) {
        var nuevo by remember { mutableStateOf(p.summary.name) }
        AlertDialog(
            onDismissRequest = { renombrar = false },
            title = { Text("Rename point") },
            text = {
                OutlinedTextField(nuevo, { nuevo = it }, singleLine = true,
                                  modifier = Modifier.testTag("gps-rename-field"))
            },
            confirmButton = {
                TextButton(onClick = { renombrar = false; vm.rename(p.summary.id, nuevo) },
                           modifier = Modifier.testTag("gps-rename-ok")) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renombrar = false }) { Text("Cancel") } })
    }

    if (borrar) {
        AlertDialog(
            onDismissRequest = { borrar = false },
            modifier = Modifier.testTag("gps-delete-warning"),
            title = { Text("Delete this point?") },
            text = { Text("Its ${p.summary.samples} fixes go with it, and this cannot be " +
                          "undone. Export them first if you may want them.") },
            confirmButton = {
                TextButton(onClick = { borrar = false; vm.delete(p.summary.id) },
                           modifier = Modifier.testTag("gps-delete-confirm")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { borrar = false }) { Text("Cancel") } })
    }
}
