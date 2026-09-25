package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.gnss.AlmanacFreshness
import cl.umag.glaciertemp.core.gnss.Constellation
import cl.umag.glaciertemp.core.gnss.Forecast
import cl.umag.glaciertemp.core.gnss.Freshness

/** Un color por constelación, y el total en el color del tema. */
private fun colorDe(c: Constellation?): Color = when (c) {
    Constellation.GPS -> Color(0xFF1F77B4)
    Constellation.GLONASS -> Color(0xFFD62728)
    Constellation.GALILEO -> Color(0xFF2CA02C)
    Constellation.BEIDOU -> Color(0xFFFF7F0E)
    null -> Color(0xFF444444)
}

private fun letra(c: Constellation) = when (c) {
    Constellation.GPS -> "G"
    Constellation.GLONASS -> "R"
    Constellation.GALILEO -> "E"
    Constellation.BEIDOU -> "C"
}

private fun nombre(c: Constellation?) = when (c) {
    Constellation.GPS -> "GPS"
    Constellation.GLONASS -> "GLONASS"
    Constellation.GALILEO -> "Galileo"
    Constellation.BEIDOU -> "BeiDou"
    null -> "Total"
}

/**
 * El planificador: cuantos satelites habra a cada hora de hoy.
 *
 * La pregunta que responde es "a que hora salgo a medir", no "donde apunto": por eso se
 * dibuja un RECUENTO por hora y no un mapa del cielo. Y por eso basta con elementos orbitales
 * de semanas -- un par de grados de error mueven la salida de un satelite unos cuatro
 * minutos, y la forma de la curva no se entera.
 */
@Composable
fun PlannerScreen(vm: AlmanacViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ahora = System.currentTimeMillis()
    var constelaciones by rememberSaveable { mutableStateOf(false) }

    // El GPS se enciende solo mientras esta pestana esta delante: atarlo a la herramienta
    // entera lo dejaria encendido mientras se promedia una posicion en otra pestana.
    DisposableEffect(Unit) {
        vm.watchSky()
        onDispose { vm.stopSky() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // LA RESPUESTA PRIMERO. Antes lo primero que se veia al abrir la pestana era la
        // edad del almanaque, que es un dato de mantenimiento: interesa una vez al mes y no
        // cuando uno esta decidiendo a que hora salir. La grafica arriba, los mandos debajo,
        // y el estado del almanaque al final, que es donde se mira cuando se va a buscar.
        s.forecast?.let { Grafica(it, s.enabled, s.showTotal, s.maskDeg, s.isToday) } ?: Text(
            if (s.tles.isEmpty()) "No orbit data yet."
            else "Waiting for a position to compute from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("planner-nochart"))

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { constelaciones = true },
                           modifier = Modifier.testTag("planner-constellations")) {
                Text("Constellations (${s.enabled.size})")
            }
            SelectorMascara(s.maskDeg) { vm.setMask(it) }
            if (s.computing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        if (s.excluded.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.excluded.size} satellite${if (s.excluded.size == 1) "" else "s"} " +
                     "left out of the count: seen broadcasting from somewhere the catalogue " +
                     "does not place them.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f).testTag("planner-excluded"))
                TextButton(onClick = { vm.clearExcluded() },
                           modifier = Modifier.testTag("planner-clear-excluded")) { Text("Reset") }
            }
        }

        TarjetaPosicion(vm, s)
        TarjetaAlmanaque(vm, s, ahora)

        s.note?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         modifier = Modifier.testTag("planner-note"))
                    TextButton(onClick = { vm.clearNote() }) { Text("Dismiss") }
                }
            }
        }
    }

    if (constelaciones) {
        ConstellationDialog(s, onToggle = { c, on -> vm.setEnabled(c, on) },
                            onTotal = { vm.setShowTotal(it) },
                            onDismiss = { constelaciones = false })
    }
}

/**
 * La mascara de elevacion.
 *
 * Valores redondos en un menu y no un campo numerico: es una decision de terreno --que tan
 * cerca del horizonte me fio-- y no una medida. Tecleando se pierde mas tiempo del que se
 * gana, y con guantes, mas.
 *
 * Cero incluido a proposito: sirve para ver el horizonte teorico completo y comparar cuanto
 * quita cada grado de mascara.
 */
@Composable
private fun SelectorMascara(actual: Double, onSet: (Double) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { abierto = true },
                       modifier = Modifier.testTag("planner-mask")) {
            Text("Mask ${actual.toInt()}°")
        }
        DropdownMenu(abierto, onDismissRequest = { abierto = false }) {
            listOf(0, 5, 10, 15, 20, 25, 30, 35).forEach { g ->
                DropdownMenuItem(
                    text = { Text("$g°" + if (g == 10) "  (usual)" else "") },
                    onClick = { abierto = false; onSet(g.toDouble()) },
                    modifier = Modifier.testTag("planner-mask-$g"))
            }
        }
    }
}

@Composable
private fun TarjetaAlmanaque(vm: AlmanacViewModel, s: AlmanacUiState, ahora: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Orbit data", style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            Text(AlmanacFreshness.describeAge(s.downloadedAtMillis, ahora),
                 style = MaterialTheme.typography.bodyLarge,
                 modifier = Modifier.testTag("planner-age"))

            // EL ERROR MEDIDO, junto a la edad y no en otra pantalla. La edad dice cuanto
            // hace que se bajo; esto dice cuanto se esta equivocando AHORA, que es lo que
            // uno quiere saber de verdad y lo unico que no se puede estimar por calendario.
            val chk = s.check
            Text(
                when {
                    chk == null || (chk.matched == 0 && chk.mismatched.isEmpty()) ->
                        "Model check: needs a GNSS fix to compare against."
                    chk.matched == 0 ->
                        "Model check: nothing comparable — every satellite in view has a " +
                        "catalogue mismatch."
                    else ->
                        "Model check: worst disagreement %.2f° over %d satellite%s in view."
                            .format(chk.maxErrorDeg, chk.matched, if (chk.matched == 1) "" else "s")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    chk?.maxErrorDeg == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    chk.maxErrorDeg!! > 2.0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.testTag("planner-check"))

            if (chk != null && chk.matched > 0) {
                Text("Measured against what the receiver actually sees, so it does not have " +
                     "to be guessed from the age. Only GPS and BeiDou can be matched by " +
                     "identity, so those are the ones counted.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)

                // LOS MAL IDENTIFICADOS, EN SU PROPIA LINEA. No son error del modelo sino
                // satelites distintos con el mismo numero, asi que meterlos en la cifra de
                // arriba la volvia inservible: once coincidiendo dentro de un grado quedaban
                // tapados por uno que discrepaba 131.
                if (chk.mismatched.isNotEmpty()) {
                    Text("%d ignored (%s): the catalogue's number no longer matches what is "
                             .format(chk.mismatched.size,
                                     chk.mismatched.joinToString(", ") {
                                         "${letra(it.constellation)}${it.svid}" }) +
                         "broadcasting. BeiDou-3 reuses the numbers of retired BeiDou-2 " +
                         "satellites, and the catalogue keeps the old name.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.testTag("planner-mismatched"))
                }

                // EL DETALLE, SATELITE A SATELITE. Un solo numero dice que algo va mal pero
                // no que: si TODOS discrepan mucho, el problema es comun --marco de
                // coordenadas, reloj, posicion--; si discrepa UNO, es su identidad, o sea
                // que el PRN que trae el nombre del TLE ya no es el que emite ese satelite.
                // Sin esta lista hay que adivinar cual de las dos cosas es.
                var detalle by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { detalle = !detalle },
                           modifier = Modifier.testTag("planner-check-details")) {
                    Text(if (detalle) "Hide per-satellite detail" else "Per-satellite detail")
                }
                if (detalle) {
                    Text("  sat    model az/el     seen az/el    diff",
                         style = MaterialTheme.typography.bodySmall,
                         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    chk.details.forEach { d ->
                        Text("%s%-3d %6.1f/%5.1f  %6.1f/%5.1f  %6.1f"
                                 .format(letra(d.constellation), d.svid,
                                         d.predictedAz, d.predictedEl,
                                         d.observedAz, d.observedEl, d.separationDeg),
                             style = MaterialTheme.typography.bodySmall,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             color = if (d.separationDeg > 5.0) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.onSurface,
                             modifier = Modifier.testTag("planner-detail-${d.svid}"))
                    }
                }
            }

            Text(
                when (s.freshness) {
                    Freshness.MISSING ->
                        "Without orbit data the planner cannot predict anything. Connect to " +
                        "a network once and it downloads by itself."
                    Freshness.FRESH -> "Up to date."
                    Freshness.AGING ->
                        "Still good. It refreshes by itself next time there is a network."
                    Freshness.STALE ->
                        "Older than ${AlmanacFreshness.STALE_DAYS} days. Still usable for " +
                        "choosing hours; refresh it when you get a network."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.freshness == Freshness.MISSING || s.freshness == Freshness.STALE)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("planner-freshness"))

            Button(onClick = { vm.refreshNow() }, enabled = !s.busy,
                   modifier = Modifier.testTag("planner-refresh")) { Text("Update now") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TarjetaPosicion(vm: AlmanacViewModel, s: AlmanacUiState) {
    var editando by rememberSaveable { mutableStateOf(false) }
    var calendario by rememberSaveable { mutableStateOf(false) }
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Computed for", style = MaterialTheme.typography.labelMedium)
            if (!editando) {
                Text(
                    if (s.latDeg == null)
                        if (s.waitingFix) "Waiting for a GNSS fix…" else "No position yet"
                    else "%.5f, %.5f".format(s.latDeg, s.lonDeg),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("planner-position"))
                Text(if (s.fromFix) "From this phone's GNSS." else "Entered by hand.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Editable SOLO con la coordenada ya puesta: la especificacion pide que
                    // se pueda cambiar por otro sitio, y sin un punto de partida el campo
                    // vacio invita a teclear grados en un formato cualquiera.
                    OutlinedButton(
                        onClick = {
                            lat = s.latDeg?.let { "%.5f".format(it) } ?: ""
                            lon = s.lonDeg?.let { "%.5f".format(it) } ?: ""
                            editando = true
                        },
                        enabled = s.latDeg != null,
                        modifier = Modifier.testTag("planner-edit-position")) { Text("Change") }
                    if (!s.fromFix) {
                        TextButton(onClick = { vm.useFix() },
                                   modifier = Modifier.testTag("planner-use-fix")) {
                            Text("Use my position")
                        }
                    }
                }
            } else {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("planner-lat"))
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("planner-lon"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val la = lat.trim().toDoubleOrNull()
                        val lo = lon.trim().toDoubleOrNull()
                        if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                            vm.setManualPosition(la, lo); editando = false
                        }
                    }, modifier = Modifier.testTag("planner-apply")) { Text("Update") }
                    TextButton(onClick = { editando = false }) { Text("Cancel") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ---------------------------------- el dia ----------------------------------
            Text("Day", style = MaterialTheme.typography.labelMedium)
            Text(if (s.dayStartMillis > 0L) fechaLarga(s.dayStartMillis) else "today",
                 style = MaterialTheme.typography.bodyLarge,
                 modifier = Modifier.testTag("planner-day"))

            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!s.isToday) {
                    TextButton(onClick = { vm.setToday() },
                               modifier = Modifier.testTag("planner-today")) { Text("Today") }
                }
                TextButton(onClick = { vm.setTomorrow() },
                           modifier = Modifier.testTag("planner-tomorrow")) { Text("Tomorrow") }
                TextButton(onClick = { calendario = true },
                           modifier = Modifier.testTag("planner-pick-date")) { Text("Pick a date…") }
            }

            // Para un dia que no es hoy, el contraste contra el cielo no mide nada: se dice
            // por que esta apagado en vez de dejar la casilla en blanco, que se leeria como
            // "no hay fix" y mandaria a buscar cielo abierto para nada.
            if (!s.isToday) {
                Text("Planning another day: the model check is off, because it compares " +
                     "against the satellites overhead right now.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.testTag("planner-check-off"))
            }

            // Y si el dia cae lejos de la epoca de los elementos, se avisa con el numero.
            AlmanacFreshness.daysOutsideValidity(s.epochRefMillis, s.dayStartMillis)?.let { d ->
                Text("That day is $d days from the orbit data. Beyond about " +
                     "${AlmanacFreshness.STALE_DAYS} days the prediction drifts by more than " +
                     "the couple of degrees this tool is good for — update the orbit data " +
                     "closer to that date.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("planner-out-of-range"))
            }
        }
    }

    if (calendario) {
        val estado = rememberDatePickerState(
            initialSelectedDateMillis = s.dayStartMillis.takeIf { it > 0L }
                ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { calendario = false },
            modifier = Modifier.testTag("planner-date-dialog"),
            confirmButton = {
                TextButton(onClick = {
                    // El selector devuelve medianoche UTC; lo que hace falta es el dia
                    // LOCAL. Se le suma medio dia antes de recortar para que una zona
                    // horaria al oeste de Greenwich no retroceda al dia anterior -- que en
                    // Patagonia son tres horas y el fallo saldria siempre.
                    estado.selectedDateMillis?.let { vm.setDay(it + 12 * 3_600_000L) }
                    calendario = false
                }, modifier = Modifier.testTag("planner-date-ok")) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { calendario = false }) { Text("Cancel") } },
        ) { DatePicker(state = estado) }
    }
}

private fun fechaLarga(ms: Long): String =
    java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.US)
        .format(java.util.Date(ms))

@Composable
private fun ConstellationDialog(s: AlmanacUiState, onToggle: (Constellation, Boolean) -> Unit,
                                onTotal: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("planner-constellation-dialog"),
        title = { Text("Constellations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Show only the ones the receiver you are taking to the field can " +
                     "actually track. A single-constellation receiver sees a very " +
                     "different sky from a multi-constellation one.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Constellation.entries.forEach { c ->
                    val on = c in s.enabled
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on,
                                 // La ultima no se puede quitar: un grafico sin lineas no
                                 // informa, y mirandolo no hay forma de saber que lo que
                                 // falta es una casilla de otra pantalla.
                                 enabled = !on || s.enabled.size > 1,
                                 onCheckedChange = { onToggle(c, it) },
                                 modifier = Modifier.testTag("planner-chk-${c.name.lowercase()}"))
                        Box(Modifier.size(12.dp).padding(end = 0.dp)) {
                            Canvas(Modifier.fillMaxSize()) { drawCircle(colorDe(c)) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(nombre(c), Modifier.weight(1f))
                        Text("${s.counts[c] ?: 0}",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.showTotal, onCheckedChange = { onTotal(it) },
                             modifier = Modifier.testTag("planner-chk-total"))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Show the total")
                        Text("Off by default: the sum dwarfs the individual lines.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

/**
 * La grafica: satelites visibles frente a la hora del dia.
 *
 * Dibujada a mano en un Canvas y no con una biblioteca: son cuatro polilineas y dos ejes, y
 * una dependencia de graficos pesa mas que el codigo que ahorra en una app que tiene que
 * caber en un telefono que se lleva a un glaciar.
 *
 * EL CURSOR es lo que la convierte en una herramienta de medida. Ver que hay un pico por la
 * tarde no basta: hay que saber ENTRE QUE HORAS, porque de ahi sale a que hora se sale del
 * campamento. Arrastrando el dedo aparece una linea vertical con la hora y el recuento de
 * cada constelacion en ese instante, y se queda puesta al levantar el dedo -- leer un numero
 * con el dedo encima tapandolo no sirve de nada.
 */
@Composable
private fun Grafica(f: Forecast, enabled: Set<Constellation>, conTotal: Boolean,
                    maskDeg: Double, esHoy: Boolean) {
    // El total se dibuja SOLO si se pide, y por eso viene apagado de fabrica: con cuatro
    // constelaciones suma unos cuarenta satelites frente a los diez de cada una, asi que
    // estira el eje y aplasta contra el suelo justo las lineas que uno queria comparar.
    // La frase de "mejor ventana" de abajo ya da lo que el total aportaba.
    val dibujadas = f.series.filter { conTotal || it.constellation != null }
    val maxY = (dibujadas.maxOfOrNull { it.counts.maxOrNull() ?: 0 } ?: 0).coerceAtLeast(4)
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val acento = MaterialTheme.colorScheme.primary
    val ahora = System.currentTimeMillis()
    var cursor by remember(f) { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Satellites above ${maskDeg.toInt()}° " +
             if (esHoy) "today"
             else "on ${java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US)
                        .format(java.util.Date(f.startMillis))}",
             style = MaterialTheme.typography.titleSmall)

        Canvas(
            Modifier.fillMaxWidth().height(240.dp).testTag("planner-chart")
                .pointerInput(f) {
                    // awaitEachGesture y no detectDragGestures: el gesto se CONSUME desde el
                    // primer contacto, para que el desplazamiento vertical de la pantalla no
                    // se lo lleve. Sin eso, arrastrar sobre el grafico hacia poca cosa mas
                    // que mover la pagina.
                    awaitEachGesture {
                        fun indice(x: Float): Int {
                            val util = size.width - MARGEN_IZQ - MARGEN_DER
                            if (util <= 0f) return 0
                            val t = (x - MARGEN_IZQ) / util
                            return (t * (f.steps - 1)).roundToInt().coerceIn(0, f.steps - 1)
                        }
                        val abajo = awaitFirstDown(requireUnconsumed = false)
                        cursor = indice(abajo.position.x)
                        abajo.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            cursor = indice(c.position.x)
                            c.consume()
                        }
                    }
                }
        ) {
            val izq = MARGEN_IZQ; val abajo = size.height - MARGEN_ABAJO
            val ancho = size.width - izq - MARGEN_DER; val alto = abajo - MARGEN_ARRIBA
            if (ancho <= 0 || alto <= 0) return@Canvas

            fun x(i: Int) = izq + ancho * i / (f.steps - 1).coerceAtLeast(1)
            fun y(v: Int) = abajo - alto * v / maxY

            // Rejilla horizontal y etiquetas del eje Y.
            val pasoY = if (maxY <= 8) 2 else 4
            var v = 0
            while (v <= maxY) {
                val yy = y(v)
                drawLine(ejes.copy(alpha = 0.2f), Offset(izq, yy), Offset(size.width - MARGEN_DER, yy), 1f)
                etiqueta("$v", izq - 8f, yy + SP_EJE_Y * this.density * 0.36f, ejes, SP_EJE_Y,
                         alinear = Alineacion.DERECHA)
                v += pasoY
            }
            // Horas cada 6.
            for (h in 0..24 step 6) {
                val i = (h * 60 * 60_000L / f.stepMillis).toInt().coerceAtMost(f.steps - 1)
                drawLine(ejes.copy(alpha = 0.2f), Offset(x(i), MARGEN_ARRIBA), Offset(x(i), abajo), 1f)
                etiqueta("${h}h", x(i), size.height - 6f, ejes, SP_EJE_X,
                         alinear = Alineacion.CENTRO)
            }
            drawLine(ejes, Offset(izq, abajo), Offset(size.width - MARGEN_DER, abajo), 1.5f)

            // AHORA: una linea vertical. Sin ella hay que contar cuadros para situarse, que
            // es justo lo que uno no quiere hacer con guantes.
            // La marca de "ahora" solo si el grafico habla de hoy: en otro dia caeria
            // siempre en un extremo y se leeria como un dato.
            val iAhora = ((ahora - f.startMillis) / f.stepMillis).toInt()
            if (esHoy && iAhora in 0 until f.steps) {
                drawLine(Color(0xFF888888), Offset(x(iAhora), MARGEN_ARRIBA), Offset(x(iAhora), abajo),
                         2f, pathEffect = androidx.compose.ui.graphics.PathEffect
                             .dashPathEffect(floatArrayOf(6f, 6f)))
            }

            // Una polilinea por serie. El total, mas grueso.
            for (serie in dibujadas) {
                val p = Path()
                serie.counts.forEachIndexed { i, n ->
                    if (i == 0) p.moveTo(x(i), y(n)) else p.lineTo(x(i), y(n))
                }
                drawPath(p, colorDe(serie.constellation),
                         style = Stroke(width = if (serie.constellation == null) 3.5f else 2f))
            }

            // EL CURSOR, encima de todo para que se vea sobre las lineas.
            cursor?.let { c ->
                drawLine(acento, Offset(x(c), MARGEN_ARRIBA), Offset(x(c), abajo), 2.5f)
                dibujadas.forEach { serie ->
                    drawCircle(colorDe(serie.constellation), 5f, Offset(x(c), y(serie.counts[c])))
                }
            }
        }

        // La lectura del cursor, FUERA del grafico: bajo el dedo no se lee nada.
        cursor?.let { c ->
            val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date(f.millisAt(c)))
            Text(
                hhmm + " — " + dibujadas.joinToString("  ") {
                    "${nombre(it.constellation)} ${it.counts[c]}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("planner-cursor"))
        }

        // Leyenda
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            dibujadas.forEach { serie ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(colorDe(serie.constellation)) }
                    Spacer(Modifier.width(4.dp))
                    Text(nombre(serie.constellation),
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        f.bestIndex()?.let { i ->
            val t = f.millisAt(i)
            val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date(t))
            val total = f.series.first { it.constellation == null }.counts[i]
            Text("Best window${if (esHoy) " today" else " that day"}: around $hhmm, " +
                 "with $total satellites.",
                 style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("planner-best"))
        }

        if (cursor == null) {
            Text("Drag across the chart to read the hour and the counts at that moment.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Margenes del grafico, en pixeles. Compartidos entre el dibujo y el gesto: si no fueran los
// mismos, la linea del cursor caeria a un lado del punto que se esta tocando.
private const val MARGEN_IZQ = 62f
private const val MARGEN_DER = 10f
private const val MARGEN_ARRIBA = 8f
private const val MARGEN_ABAJO = 54f

/** Tamanos de las etiquetas de los ejes, en sp. */
private const val SP_EJE_X = 18f
private const val SP_EJE_Y = 13f

private enum class Alineacion { IZQUIERDA, CENTRO, DERECHA }

private fun DrawScope.etiqueta(txt: String, x: Float, y: Float, c: Color, sp: Float,
                               alinear: Alineacion = Alineacion.IZQUIERDA) {
    val px = sp * this.density
    val p = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(
            (c.alpha * 255).toInt(), (c.red * 255).toInt(),
            (c.green * 255).toInt(), (c.blue * 255).toInt())
        textSize = px
        isAntiAlias = true
    }
    // Se mide el texto en vez de restar un numero a ojo: con la fuente mas grande, un
    // desplazamiento fijo dejaba "12h" pegado a la linea de la rejilla y "0h" fuera del
    // grafico. Lo que hay que centrar depende de cuantos digitos tenga la hora.
    val ancho = p.measureText(txt)
    val xx = when (alinear) {
        Alineacion.IZQUIERDA -> x
        Alineacion.CENTRO -> x - ancho / 2f
        Alineacion.DERECHA -> x - ancho
    }
    drawContext.canvas.nativeCanvas.drawText(txt, xx, y, p)
}
