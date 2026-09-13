package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
fun GpsToolScreen(vm: GpsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    // El atras del sistema retrocede una pantalla dentro de la herramienta antes de salir
    // de ella. Desde la pantalla de medir NO se sale sin mas: se deja que el boton Done
    // haga su trabajo, que es el que pregunta por lo que no se ha guardado.
    androidx.activity.compose.BackHandler(enabled = s.openPoint != null) { vm.closePoint() }

    when {
        s.averaging != null -> AveragingScreen(vm, s, s.averaging!!)
        s.openPoint != null -> PointScreen(vm, s, s.openPoint!!)
        else -> PointListScreen(vm, s)
    }
}

@Composable
private fun PointListScreen(vm: GpsViewModel, s: GpsUiState) {
    Column(Modifier.fillMaxSize().padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Averaged positions", style = MaterialTheme.typography.titleMedium)
        Text("Records GNSS fixes for as long as you like and keeps the median. " +
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
            LazyColumn(Modifier.weight(1f).testTag("gps-list"),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.points, key = { it.id }) { p ->
                    Card(onClick = { vm.open(p.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.name.ifBlank { "(unnamed)" },
                                 style = MaterialTheme.typography.titleSmall)
                            Text("${p.samples} fixes  ·  last ${cuando(p.lastEpochMillis)}",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
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

        OutlinedTextField(
            value = nombre, onValueChange = { nombre = it },
            label = { Text("Point name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("gps-name"))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${a.samples.size} fixes", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.testTag("gps-count"))
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
            Text("Horizontal", style = MaterialTheme.typography.titleSmall)
            ScatterPlot(a.projected, st.easting.median, st.northing.median,
                        Modifier.fillMaxWidth().height(240.dp).testTag("gps-scatter"))
            Cifras(st)

            Spacer(Modifier.height(4.dp))
            Text("Altitude", style = MaterialTheme.typography.titleSmall)
            val alturas = a.samples.mapNotNull { it.altitudeMetres }
            val alt = st.altitude
            if (alt == null) {
                Text("No altitude in these fixes (2D solution).",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AltitudePlot(
                    a.samples.filter { it.altitudeMetres != null }.map { it.epochMillis },
                    alturas, alt,
                    Modifier.fillMaxWidth().height(160.dp).testTag("gps-altitude"))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigReading("Altitude", f(alt.median, 1), "m", Modifier.weight(1f))
                    BigReading("sd", f(alt.sd, 2), "m", Modifier.weight(1f))
                    BigReading("± est.", f(alt.standardError, 2), "m", Modifier.weight(1f))
                }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveAveraging(nombre) },
                   enabled = a.samples.isNotEmpty(),
                   modifier = Modifier.testTag("gps-save")) { Text("Save") }
            if (a.running) {
                OutlinedButton(onClick = { vm.pauseAveraging() },
                               modifier = Modifier.testTag("gps-pause")) { Text("Pause") }
            } else {
                OutlinedButton(onClick = { vm.startAveraging(a.pointId, nombre) },
                               modifier = Modifier.testTag("gps-resume")) { Text("Resume") }
            }
            OutlinedButton(
                onClick = { if (a.unsaved > 0) confirmarSalida = true else vm.closeAveraging() },
                modifier = Modifier.testTag("gps-done")) { Text("Done") }
        }
        if (a.unsaved > 0) {
            Text("${a.unsaved} fixes not saved yet",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
                 modifier = Modifier.testTag("gps-unsaved"))
        }
    }

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
 * Las tres cifras de la estimacion horizontal.
 *
 * Van las tres juntas porque responden a preguntas distintas y solo una de ellas baja al
 * seguir midiendo. Ensenar solo la dispersion es lo que hace que alguien mire la pantalla
 * diez minutos, vea que el numero no se mueve y concluya que promediar no sirve para nada.
 */
@Composable
private fun Cifras(st: GpsPointStats) {
    Text(st.medianUtm.format(), style = MaterialTheme.typography.titleMedium,
         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
         modifier = Modifier.testTag("gps-utm"))
    Text("${f(st.medianLatitude, 6)}, ${f(st.medianLongitude, 6)}",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant,
         modifier = Modifier.testTag("gps-latlon"))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BigReading("sd East", f(st.easting.sd, 2), "m", Modifier.weight(1f))
        BigReading("sd North", f(st.northing.sd, 2), "m", Modifier.weight(1f))
        BigReading("± est.", f(st.horizontalStandardError, 2), "m", Modifier.weight(1f))
    }
    Text("sd is how much the fixes scatter; it describes the receiver and the site and " +
         "does not shrink. ± est. is the uncertainty of the median and does shrink — " +
         "though more slowly than shown, because consecutive fixes are correlated.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ScatterPlot(p.projected, st.easting.median, st.northing.median,
                        Modifier.fillMaxWidth().height(220.dp))
            Cifras(st)
            st.altitude?.let { alt ->
                AltitudePlot(
                    p.samples.filter { it.altitudeMetres != null }.map { it.epochMillis },
                    p.samples.mapNotNull { it.altitudeMetres }, alt,
                    Modifier.fillMaxWidth().height(140.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigReading("Altitude", f(alt.median, 1), "m", Modifier.weight(1f))
                    BigReading("sd", f(alt.sd, 2), "m", Modifier.weight(1f))
                    BigReading("± est.", f(alt.standardError, 2), "m", Modifier.weight(1f))
                }
            }
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
