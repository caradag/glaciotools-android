package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.umag.glaciertemp.core.Chart
import cl.umag.glaciertemp.core.Decimals
import cl.umag.glaciertemp.core.fieldbook.ConductivityImport
import cl.umag.glaciertemp.core.fieldbook.ConductivityReading
import cl.umag.glaciertemp.core.fieldbook.FieldEntry
import cl.umag.glaciertemp.core.fieldbook.FieldbookCsv
import cl.umag.glaciertemp.core.fieldbook.ReadingsSource
import cl.umag.glaciertemp.core.fieldbook.SaltCalibration
import cl.umag.glaciertemp.core.fieldbook.SaltDilution
import cl.umag.glaciertemp.core.fieldbook.SaltDilutionMath
import cl.umag.glaciertemp.core.fieldbook.StreamGauging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// =================================== dilucion de sal ===================================

private val HORA_SEG: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
private fun horaSeg(ms: Long): String =
    HORA_SEG.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Factor de calibracion con cuatro decimales: el rango util es 0,45-0,6. */
private fun cal(v: Double): String = Decimals.fixed(v, 4)

/**
 * El aforo por dilucion de sal.
 *
 * TODO EN UNA LISTA QUE SE DESPLAZA, al reves que el area-velocidad. Aqui no hay una tabla que
 * recorrer mirando un dibujo: hay una configuracion larga que se hace una vez --masa, punto de
 * inyeccion, calibracion-- y despues una medida de un cuarto de hora mirando un solo grafico.
 * La configuracion se pliega en una linea al terminar, y lo que queda arriba es justo lo que
 * se usa durante el paso de la sal: la casilla, el boton y el grafico.
 */
@Composable
fun SaltDilutionBody(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
                     g: StreamGauging, bloqueada: Boolean, onBorrar: (Boolean) -> Unit) {
    val sal = g.salt ?: SaltDilution()
    val configurada = sal.saltMassG != null && sal.calibrationFactor != null
    var ajustes by remember(e.id) { mutableStateOf(!configurada && !bloqueada) }
    val plot = remember(e.id) { TimePlotState() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("fb-salt-list"),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {

        item { Spacer(Modifier.height(4.dp)) }

        if (!ajustes) {
            item { ResumenDeAjustes(e, sal, bloqueada) { ajustes = true } }
        } else {
            item { AjustesGenerales(vm, s, e, sal) }
            item { PuntoDeInyeccion(vm, s, sal) }
            item { Calibracion(vm, sal, bloqueada = false) }
            item {
                Button(onClick = { ajustes = false },
                       modifier = Modifier.fillMaxWidth().testTag("fb-salt-setup-done")) {
                    Text("OK — hide setup")
                }
            }
        }
        // Con la nota bloqueada la configuracion no se despliega para editar, pero la
        // calibracion se puede MIRAR: es lo primero que se quiere revisar de un caudal raro.
        if (bloqueada && sal.calibration != null) {
            item { Calibracion(vm, sal, bloqueada = true) }
        }

        item { HorizontalDivider() }
        item { Text("Conductivity", style = MaterialTheme.typography.titleSmall) }
        if (!bloqueada) item { Entrada(vm, sal) }
        item { GraficoDeConductividad(vm, sal, plot, bloqueada) }
        item { Resultado(vm, sal, bloqueada) }
        item { TablaDeLecturas(vm, sal, bloqueada) }
        if (sal.readings.isNotEmpty()) {
            item { CopiarTexto("Copy salt dilution data and discharge", "fb-salt-copy") {
                FieldbookCsv.saltDilution(e, geoid = { p -> Geoids.tagAt(p.latitude, p.longitude) }) } }
        }

        pieDelAforo(vm, s, e, g, bloqueada, onBorrar)
    }
}

// --------------------------------- configuracion ---------------------------------

@Composable
private fun ResumenDeAjustes(e: FieldEntry, sal: SaltDilution, bloqueada: Boolean,
                             onEditar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(buildString {
                 append(e.profileName.ifBlank { "(unnamed profile)" })
                 sal.saltMassG?.let { append("  ·  ").append(formatNumber(it)).append(" g salt") }
                 sal.calibrationFactor?.let { append("  ·  Cal ").append(cal(it)) }
                 sal.injectionDistanceM?.let { append("  ·  ").append(formatNumber(it)).append(" m") }
                 sal.injectionEpochMillis?.let { append("  ·  injected ").append(horaSeg(it)) }
             },
             Modifier.weight(1f).testTag("fb-salt-summary"),
             style = MaterialTheme.typography.bodyMedium)
        if (!bloqueada) {
            TextButton(onClick = onEditar, modifier = Modifier.testTag("fb-salt-setup")) {
                Text("Edit")
            }
        }
    }
}

@Composable
private fun AjustesGenerales(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
                             sal: SaltDilution) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NamePicker(
            label = "Profile",
            value = e.profileName,
            options = s.profiles,
            onValue = { v -> vm.update(immediate = false) { it.copy(profileName = v) } },
            onRemember = { vm.rememberName(NameList.PROFILES, it) },
            onRemove = { vm.removeName(NameList.PROFILES, it) },
            onClearAll = { vm.clearNames(NameList.PROFILES) },
            tag = "fb-salt-profile")
        NumberField("Injected salt mass", sal.saltMassG,
                    onValue = { v -> vm.updateSalt { it.copy(saltMassG = v) } },
                    suffix = "g", modifier = Modifier.fillMaxWidth(), tag = "fb-salt-mass")
        Text("Rule of thumb (Merz & Doppmann 2006): 2–5 kg of salt per m³/s of discharge.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PuntoDeInyeccion(vm: FieldbookViewModel, s: FieldbookUiState, sal: SaltDilution) {
    Card(Modifier.fillMaxWidth().testTag("fb-salt-injection")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Injection point", style = MaterialTheme.typography.titleSmall)
            PositionField(
                position = sal.injectionPosition,
                request = s.positionRequest?.takeIf { it.target == PositionTarget.INJECTION },
                savedPoints = s.savedPoints,
                onUsePhone = { vm.requestPhonePosition(target = PositionTarget.INJECTION) },
                onCancelPhone = { vm.cancelPositionRequest() },
                onUsePoint = { vm.useInjectionPoint(it) },
                onClear = { vm.clearInjectionPosition() },
                onNeedPoints = { vm.refreshSavedPoints() },
                label = "Coordinates",
                tag = "fb-inj")
            // SET NOW junto a la hora y no escondido en el cuadro de editar: es lo que se
            // pulsa con el cubo en la otra mano, en el instante de echar la sal.
            TimestampRow("Injection time", sal.injectionEpochMillis,
                         onChange = { ms -> vm.updateSalt(immediate = true) {
                             it.copy(injectionEpochMillis = ms) } },
                         tag = "fb-inj-time",
                         trailing = {
                             Button(onClick = { vm.setInjectionNow() },
                                    modifier = Modifier.testTag("fb-inj-now")) { Text("Set now") }
                         })
            NumberField("Distance to the measuring point", sal.injectionDistanceM,
                        onValue = { v -> vm.updateSalt { it.copy(injectionDistanceM = v) } },
                        suffix = "m", modifier = Modifier.fillMaxWidth(), tag = "fb-inj-distance")
            Text("Rule of thumb: 20–50 times the mean stream width, or 100 times the narrowest.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = sal.injectionNotes,
                onValueChange = { v -> vm.updateSalt { it.copy(injectionNotes = v) } },
                label = { Text("Notes on the injection point") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("fb-inj-notes"))
        }
    }
}

// ---------------------------------- calibracion ----------------------------------

/**
 * El factor de calibracion: tecleado, o sacado de la herramienta.
 *
 * Se puede teclear porque no siempre hay tiempo de calibrar --en una crecida la guia dice
 * usar el ultimo factor, o 0,5-- y porque un factor estable con el mismo conductimetro y la
 * misma sal se reusa. Por eso tiene boton de copiar.
 */
@Composable
private fun Calibracion(vm: FieldbookViewModel, sal: SaltDilution, bloqueada: Boolean) {
    val ctx = LocalContext.current
    var herramienta by remember { mutableStateOf(bloqueada) }

    Card(Modifier.fillMaxWidth().testTag("fb-salt-calibration")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).soloLectura(bloqueada)) {
                    NumberField("Calibration factor", sal.calibrationFactor,
                                onValue = { v -> vm.updateSalt { it.copy(calibrationFactor = v) } },
                                suffix = "(mg/L)/(µS/cm)", modifier = Modifier.fillMaxWidth(),
                                tag = "fb-salt-cal")
                }
                IconButton(onClick = {
                               val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                   as? android.content.ClipboardManager
                               sal.calibrationFactor?.let {
                                   cb?.setPrimaryClip(android.content.ClipData.newPlainText(
                                       "Calibration factor", cal(it)))
                               }
                           },
                           enabled = sal.calibrationFactor != null,
                           modifier = Modifier.testTag("fb-salt-cal-copy")) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy calibration factor")
                }
            }
            Text("Salt concentration per unit of conductivity. Typically 0.45–0.6 for table " +
                 "salt; the guide suggests 0.5 when there is no time to calibrate.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!herramienta) {
                OutlinedButton(onClick = { herramienta = true },
                               modifier = Modifier.testTag("fb-salt-cal-new")) {
                    Text(if (sal.calibration == null) "New calibration" else "Show calibration")
                }
            } else {
                HerramientaDeCalibracion(vm, sal, bloqueada)
                if (!bloqueada) {
                    TextButton(onClick = { herramienta = false }) { Text("Hide calibration") }
                }
            }
        }
    }
}

@Composable
private fun HerramientaDeCalibracion(vm: FieldbookViewModel, sal: SaltDilution, bloqueada: Boolean) {
    val actual = sal.calibration
    // Las cantidades son un BORRADOR hasta el OK, como el ancho del perfil: la tabla no
    // aparece y desaparece mientras se teclea "10" pasando por "1".
    val d = actual ?: SaltCalibration()
    var agua by remember(actual == null) { mutableStateOf<Double?>(d.waterVolumeMl) }
    var refVol by remember(actual == null) { mutableStateOf<Double?>(d.referenceVolumeMl) }
    var refSal by remember(actual == null) { mutableStateOf<Double?>(d.referenceSaltG) }
    var paso by remember(actual == null) { mutableStateOf<Double?>(d.incrementMl) }
    var puntos by remember(actual == null) { mutableStateOf<Double?>(d.points.toDouble()) }
    var confirmar by remember { mutableStateOf<SaltCalibration?>(null) }

    val borrador = run {
        val a = agua; val rv = refVol; val rs = refSal; val p = paso; val n = puntos?.toInt()
        if (a == null || rv == null || rs == null || p == null || n == null ||
            a <= 0 || rv <= 0 || rs <= 0 || p <= 0 || n < 1 || n > SaltDilutionMath.MAX_CALIBRATION_POINTS) null
        else SaltCalibration(a, rv, rs, p, n)
    }
    val cambia = actual == null || borrador == null ||
        borrador.copy(conductivities = actual.conductivities) != actual

    if (!bloqueada) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Water sample", agua, { agua = it }, Modifier.weight(1f), "mL", "fb-cal-water")
                NumberField("Reference solution", refVol, { refVol = it }, Modifier.weight(1f), "mL", "fb-cal-refvol")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Salt in reference", refSal, { refSal = it }, Modifier.weight(1f), "g", "fb-cal-refsalt")
                NumberField("Increment", paso, { paso = it }, Modifier.weight(1f), "mL", "fb-cal-step")
            }
            NumberField("Number of calibration points", puntos, { puntos = it },
                        Modifier.fillMaxWidth(), null, "fb-cal-points")
            if (cambia) {
                Button(onClick = {
                           val b = borrador ?: return@Button
                           // Cambiar el numero de puntos vacia la tabla: se pregunta si hay algo.
                           val pierde = actual != null && actual.points != b.points &&
                                        actual.conductivities.any { it != null }
                           if (pierde) confirmar = b else vm.setCalibration(b)
                       },
                       enabled = borrador != null,
                       modifier = Modifier.fillMaxWidth().testTag("fb-cal-ok")) {
                    Text(if (actual == null) "OK — build calibration table" else "OK — update table")
                }
            }
        }
    }

    if (actual != null) {
        TablaDeCalibracion(vm, actual, bloqueada)
    }

    confirmar?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmar = null },
            title = { Text("Start the table again?") },
            text = { Text("Changing the number of points empties the conductivities already " +
                          "entered in the calibration table.") },
            confirmButton = { TextButton(onClick = { vm.setCalibration(c); confirmar = null }) {
                Text("Rebuild", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmar = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TablaDeCalibracion(vm: FieldbookViewModel, c: SaltCalibration, bloqueada: Boolean) {
    val filas = remember(c) { SaltDilutionMath.calibrationRows(c) }
    val ajuste = remember(filas) { SaltDilutionMath.fit(filas) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Sample volume (mL)", "Salt conc. (g/L)", "Conductivity (µS/cm)").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
        filas.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatNumber(f.volumeMl), Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                     fontSize = 13.sp, textAlign = TextAlign.End)
                Text(Decimals.fixed(f.concentrationGL, 5), Modifier.weight(1f),
                     fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.End)
                CeldaNumero(f.conductivity, { vm.setCalibrationConductivity(f.index, it) },
                            onSalir = { vm.refitCalibration() }, soloLeer = bloqueada,
                            modifier = Modifier.weight(1f), tag = "fb-cal-ec-${f.index}")
            }
        }
        GraficoDeCalibracion(filas, ajuste,
                             Modifier.fillMaxWidth().height(160.dp).padding(top = 6.dp)
                                 .testTag("fb-cal-chart"))
        Text(ajuste?.let {
                 "Linear fit of ${it.n} points: ${Decimals.fixed(it.slope, 1)} µS/cm per g/L" +
                 (it.r2?.let { r -> ", R² = " + Decimals.fixed(r, 4) } ?: "") +
                 (it.calibrationFactor?.let { f -> " → calibration factor " + cal(f) } ?: "")
             } ?: "Enter at least two conductivities to fit the line.",
             style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.testTag("fb-cal-fit"))
        if (ajuste != null && ajuste.calibrationFactor == null) {
            Text("The conductivity does not rise with the salt: check the readings.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text("The factor is the inverse of the slope, in mg/L per µS/cm. It is updated when " +
             "you leave a conductivity cell.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Una casilla numerica de tabla que avisa al PERDER el foco.
 *
 * El valor se guarda en cada pulsacion, como en el resto de la libreta; lo que espera al foco
 * es la consecuencia --la recta y el factor-- para no recalcularla con cada digito.
 */
@Composable
private fun CeldaNumero(valor: Double?, onValor: (Double?) -> Unit, onSalir: () -> Unit,
                        soloLeer: Boolean, modifier: Modifier, tag: String) {
    var texto by remember { mutableStateOf(formatNumber(valor)) }
    var tuvoFoco by remember { mutableStateOf(false) }
    LaunchedEffect(valor) {
        if (parseNumber(texto) != valor && !(texto.isBlank() && valor == null)) texto = formatNumber(valor)
    }
    OutlinedTextField(
        value = texto,
        onValueChange = { t -> texto = t; onValor(if (t.isBlank()) null else parseNumber(t)) },
        singleLine = true,
        readOnly = soloLeer,
        isError = texto.isNotBlank() && parseNumber(texto) == null,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier.soloLectura(soloLeer).testTag(tag).onFocusChanged { f ->
            if (f.isFocused) tuvoFoco = true
            else if (tuvoFoco) { tuvoFoco = false; onSalir() }
        })
}

/** Conductividad frente a concentracion, con la recta ajustada. */
@Composable
private fun GraficoDeCalibracion(filas: List<SaltDilutionMath.CalibrationRow>,
                                 ajuste: SaltDilutionMath.Fit?, modifier: Modifier) {
    val medir = rememberTextMeasurer()
    val ejes = MaterialTheme.colorScheme.outline
    val punto = MaterialTheme.colorScheme.primary
    val recta = MaterialTheme.colorScheme.tertiary
    val estilo = TextStyle(fontSize = 9.sp, color = ejes)
    val pts = filas.mapNotNull { f -> f.conductivity?.let { f.concentrationGL to it } }
    Canvas(modifier) {
        if (pts.isEmpty()) return@Canvas
        val xMax = filas.maxOf { it.concentrationGL }.coerceAtLeast(1e-9)
        var yMin = pts.minOf { it.second }; var yMax = pts.maxOf { it.second }
        if (yMax <= yMin) { yMin -= 1; yMax += 1 }
        val m = (yMax - yMin) * 0.1; yMin -= m; yMax += m
        val ticks = Chart.yTicks(yMin, yMax, 4)
        val izq = (ticks.maxOfOrNull { medir.measure(it.label, estilo).size.width } ?: 0) + 10f
        val abajo = size.height - 16f
        fun x(c: Double) = izq + (c / xMax).toFloat() * (size.width - izq - 4f)
        fun y(v: Double) = abajo - ((v - yMin) / (yMax - yMin)).toFloat() * (abajo - 4f)
        ticks.forEach { t ->
            drawLine(ejes.copy(alpha = 0.25f), Offset(izq, y(t.value)), Offset(size.width, y(t.value)), 1f)
            val l = medir.measure(t.label, estilo)
            drawText(l, topLeft = Offset(izq - l.size.width - 6f, y(t.value) - l.size.height / 2f))
        }
        drawLine(ejes, Offset(izq, 4f), Offset(izq, abajo), 1.5f)
        drawLine(ejes, Offset(izq, abajo), Offset(size.width, abajo), 1.5f)
        val etiqueta = medir.measure("salt concentration, g/L (max ${Decimals.fixed(xMax, 4)})", estilo)
        drawText(etiqueta, topLeft = Offset(size.width - etiqueta.size.width, abajo + 2f))
        // LA RECTA, RECORTADA AL GRAFICO. El eje vertical sale de los puntos medidos, y con
        // un punto disparatado la recta ajustada pasa por fuera: sin recorte cruzaba la
        // pantalla entera por encima de la tabla.
        ajuste?.let { a ->
            clipRect(izq, 4f, size.width, abajo) {
                drawLine(recta, Offset(x(0.0), y(a.intercept)),
                         Offset(x(xMax), y(a.intercept + a.slope * xMax)), 2f)
            }
        }
        pts.forEach { (c, v) -> drawCircle(punto, 4f, Offset(x(c), y(v))) }
    }
}

// ------------------------------------- entrada -------------------------------------

/**
 * Las dos formas de meter conductividades: a mano, una por pulsacion, o desde un fichero.
 *
 * Sea cual sea, terminan en la MISMA lista de lecturas con hora, y todo lo que viene despues
 * --grafico, base, integral-- no sabe de donde salieron.
 */
@Composable
private fun Entrada(vm: FieldbookViewModel, sal: SaltDilution) {
    var modo by remember { mutableStateOf(sal.readingsSource) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = modo == ReadingsSource.MANUAL,
                       onClick = { modo = ReadingsSource.MANUAL },
                       label = { Text("Manual entry") },
                       modifier = Modifier.testTag("fb-salt-mode-manual"))
            FilterChip(selected = modo == ReadingsSource.IMPORTED,
                       onClick = { modo = ReadingsSource.IMPORTED },
                       label = { Text("Import values") },
                       modifier = Modifier.testTag("fb-salt-mode-import"))
        }
        when (modo) {
            ReadingsSource.MANUAL -> EntradaManual(vm, sal)
            ReadingsSource.IMPORTED -> Importar(vm, sal)
        }
    }
}

/**
 * Una casilla y un boton. La hora la pone el boton: es el instante en que se pulsa, que es lo
 * mas cerca que se puede estar del instante en que se leyo el conductimetro.
 *
 * El teclado NO se cierra al pulsar --ni con el boton ni con la tecla de aceptar del teclado--
 * y la casilla se vacia: durante el paso de la sal se lee cada cinco segundos, y cada toque
 * de mas es una lectura que se pierde.
 */
@Composable
private fun EntradaManual(vm: FieldbookViewModel, sal: SaltDilution) {
    var texto by remember { mutableStateOf("") }
    val valor = parseNumber(texto)
    fun meter() {
        val v = parseNumber(texto) ?: return
        vm.addConductivity(v)
        texto = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Conductivity") },
            suffix = { Text("µS/cm") },
            singleLine = true,
            isError = texto.isNotBlank() && valor == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal,
                                              imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { meter() }),
            modifier = Modifier.weight(1f).testTag("fb-salt-ec"))
        Button(onClick = { meter() }, enabled = valor != null,
               modifier = Modifier.testTag("fb-salt-enter")) { Text("Enter value") }
    }
    sal.readings.maxByOrNull { it.atEpochMillis }?.let { u ->
        Text(buildString {
                 append("Last: ${formatNumber(u.microSiemensPerCm)} µS/cm at ${horaSeg(u.atEpochMillis)}")
                 sal.injectionEpochMillis?.let { append("  (+${(u.atEpochMillis - it) / 1000} s)") }
                 append("  ·  ${sal.readings.size} reading(s)")
             },
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("fb-salt-last"))
    }
}

/**
 * Importar el fichero de un conductimetro con registro.
 *
 * SE ENSENA ANTES DE USARLO. Un fichero mal leido --mes por dia, mS por uS-- da un grafico
 * perfectamente creible, asi que se dice que se entendio del tiempo, de la columna y de la
 * unidad, y no se sustituye nada hasta que se acepta.
 */
@Composable
private fun Importar(vm: FieldbookViewModel, sal: SaltDilution) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var texto by remember { mutableStateOf<ByteArray?>(null) }
    var nombre by remember { mutableStateOf<String?>(null) }
    var columna by remember { mutableStateOf<Int?>(null) }
    var resultado by remember { mutableStateOf<ConductivityImport.Result?>(null) }
    var leyendo by remember { mutableStateOf(false) }

    fun analizar() {
        val t = texto ?: return
        leyendo = true
        alcance.launch {
            resultado = withContext(Dispatchers.Default) {
                ConductivityImport.parseBytes(t, ZoneId.systemDefault(), sal.injectionEpochMillis, columna)
            }
            leyendo = false
        }
    }

    val abrir = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        nombre = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                      null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        texto = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        columna = null
        resultado = if (texto == null) ConductivityImport.Result(emptyList(), emptyList(), null, "", 0,
                                                                 emptyList(), "The file could not be read.")
                    else null
        analizar()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { abrir.launch(arrayOf("text/*", "application/octet-stream",
                                                        "application/vnd.ms-excel", "*/*")) },
                       modifier = Modifier.testTag("fb-salt-import")) {
            Text("Choose a logger file (CSV or text)…")
        }
        if (sal.readingsSource == ReadingsSource.IMPORTED && sal.importedFile != null && resultado == null) {
            Text("Current readings come from “${sal.importedFile}”.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (leyendo) Text("Reading the file…", style = MaterialTheme.typography.bodySmall)
        resultado?.let { r ->
            Card(Modifier.fillMaxWidth().testTag("fb-salt-import-preview")) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(nombre ?: "File", style = MaterialTheme.typography.labelLarge)
                    if (r.error != null) {
                        Text(r.error!!, color = MaterialTheme.colorScheme.error,
                             style = MaterialTheme.typography.bodySmall,
                             modifier = Modifier.testTag("fb-salt-import-error"))
                    } else {
                        Text("${r.readings.size} readings, from ${horaSeg(r.readings.first().atEpochMillis)} " +
                             "to ${horaSeg(r.readings.last().atEpochMillis)}",
                             style = MaterialTheme.typography.bodySmall)
                        Text("Time: ${r.timeDescription}", style = MaterialTheme.typography.bodySmall)
                        if (r.skippedRows > 0)
                            Text("${r.skippedRows} row(s) without a time or a value were skipped.",
                                 style = MaterialTheme.typography.bodySmall)
                        r.warnings.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.error)
                        }
                        if (r.candidates.size > 1) {
                            Text("Conductivity column:", style = MaterialTheme.typography.labelMedium)
                            r.candidates.forEach { c ->
                                FilterChip(selected = c.index == r.column?.index,
                                           onClick = { columna = c.index; analizar() },
                                           label = { Text(c.header.take(40)) })
                            }
                        } else {
                            Text("Conductivity column: ${r.column?.header}",
                                 style = MaterialTheme.typography.bodySmall)
                        }
                        // RECORTE ALREDEDOR DE LA INYECCION. Un fichero de logger trae dias y a
                        // veces varias inyecciones; la nota es una medicion. Se ofrece --y se
                        // propone si el fichero dura mas de dos horas-- quedarse con un rato
                        // antes (para la base) y una hora despues.
                        val iny = sal.injectionEpochMillis
                        val cerca = remember(r, iny) {
                            iny?.let { ConductivityImport.aroundInjection(r.readings, it) } ?: emptyList()
                        }
                        val largo = r.readings.last().atEpochMillis - r.readings.first().atEpochMillis > 2 * 3_600_000L
                        var recortar by remember(r) { mutableStateOf(cerca.size >= 2 && largo) }
                        Text("Keep:", style = MaterialTheme.typography.labelMedium)
                        FilterChip(selected = !recortar, onClick = { recortar = false },
                                   label = { Text("The whole file (${r.readings.size})") },
                                   modifier = Modifier.testTag("fb-salt-import-all"))
                        FilterChip(selected = recortar, onClick = { recortar = true },
                                   enabled = cerca.size >= 2,
                                   label = { Text("15 min before to 60 min after the injection " +
                                                  "(${cerca.size})") },
                                   modifier = Modifier.testTag("fb-salt-import-around"))
                        if (cerca.size < 2) {
                            Text(if (iny == null) "Set the injection time to keep only this measurement."
                                 else "The injection time (${horaSeg(iny)}) is not inside the file: " +
                                      "check it in the setup above if you want to crop.",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val usar = if (recortar && cerca.size >= 2) cerca else r.readings
                        Button(onClick = {
                                   vm.importConductivity(usar, nombre)
                                   resultado = null; texto = null
                               },
                               modifier = Modifier.fillMaxWidth().testTag("fb-salt-import-use")) {
                            Text(if (sal.readings.isEmpty()) "Use these ${usar.size} readings"
                                 else "Replace the ${sal.readings.size} current readings with ${usar.size}")
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------- grafico -------------------------------------

/**
 * Conductividad frente al tiempo, con cursor, la base y la ventana de integracion.
 *
 * LA BASE SE ELIGE SOBRE EL GRAFICO y no se teclea: es la conductividad del agua ANTES de que
 * llegue la sal, y la manera fiable de saberla es mirar la curva y senalar el tramo plano del
 * principio. Un numero tecleado de memoria no tiene por que coincidir con lo que el mismo
 * conductimetro estaba leyendo ese dia.
 */
@Composable
private fun GraficoDeConductividad(vm: FieldbookViewModel, sal: SaltDilution, plot: TimePlotState,
                                   bloqueada: Boolean) {
    val lecturas = remember(sal.readings) { sal.readings.sortedBy { it.atEpochMillis } }
    if (lecturas.isEmpty()) {
        Text("No readings yet. The chart appears with the first one.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("fb-salt-chart-empty"))
        return
    }
    val t0 = lecturas.first().atEpochMillis
    val origen = remember(t0) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(t0), ZoneId.systemDefault())
    }
    val puntos = remember(lecturas) {
        lecturas.map { PlotPoint((it.atEpochMillis - t0) / 1000.0, it.microSiemensPerCm) }
    }
    val baseColor = MaterialTheme.colorScheme.tertiary
    val ventanaColor = MaterialTheme.colorScheme.secondary
    val inyColor = MaterialTheme.colorScheme.error
    fun x(ms: Long) = (ms - t0) / 1000.0

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TimePlotControls(plot, "fb-salt-chart")
        TimePlot(
            points = puntos, origin = origen, state = plot,
            hLines = listOfNotNull(sal.baseConductivity?.let { PlotHLine(it, baseColor, "base") }),
            vLines = listOfNotNull(
                sal.injectionEpochMillis?.let { PlotVLine(x(it), inyColor, "injection") },
                sal.windowStartMillis?.let { PlotVLine(x(it), ventanaColor, "start") },
                sal.windowEndMillis?.let { PlotVLine(x(it), ventanaColor, "end") }),
            showDots = true,
            modifier = Modifier.fillMaxWidth().height(230.dp).testTag("fb-salt-chart"))

        // Debajo del grafico, lo que marca el cursor.
        val i = plot.cursorX?.let { nearestIndex(puntos, it) }
        val sel = i?.let { lecturas[it] }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text("Time", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sel?.let { r -> horaSeg(r.atEpochMillis) +
                         (sal.injectionEpochMillis?.let { "  (+${(r.atEpochMillis - it) / 1000} s)" } ?: "") }
                         ?: "—",
                     fontFamily = FontFamily.Monospace,
                     modifier = Modifier.testTag("fb-salt-cursor-time"))
            }
            Column {
                Text("Conductivity", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sel?.let { formatNumber(it.microSiemensPerCm) + " µS/cm" } ?: "—",
                     fontFamily = FontFamily.Monospace,
                     modifier = Modifier.testTag("fb-salt-cursor-value"))
            }
        }
        if (sel == null && !plot.zoomMode) {
            Text("Drag sideways on the chart (zoom off) to place the cursor.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!bloqueada) {
            Button(onClick = { sel?.let { vm.setBaseConductivity(it.microSiemensPerCm) } },
                   enabled = sel != null,
                   modifier = Modifier.fillMaxWidth().testTag("fb-salt-set-base")) {
                Text("Set Base Conductivity")
            }
            // LA VENTANA: lo que entra en la integral. Opcional --sin ella entra todo-- y
            // pensada para los ficheros de logger, que traen horas antes y despues de la sal.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sel?.let { vm.setWindowStart(it.atEpochMillis) } },
                               enabled = sel != null, modifier = Modifier.weight(1f)
                                   .testTag("fb-salt-set-start")) { Text("Set start") }
                OutlinedButton(onClick = { sel?.let { vm.setWindowEnd(it.atEpochMillis) } },
                               enabled = sel != null, modifier = Modifier.weight(1f)
                                   .testTag("fb-salt-set-end")) { Text("Set end") }
                if (sal.windowStartMillis != null || sal.windowEndMillis != null) {
                    TextButton(onClick = { vm.setWindowStart(null); vm.setWindowEnd(null) },
                               modifier = Modifier.testTag("fb-salt-clear-window")) { Text("All") }
                }
            }
        }
        Text(buildString {
                 append("Base conductivity: ")
                 append(sal.baseConductivity?.let { formatNumber(it) + " µS/cm" } ?: "not set")
                 append("   ·   Integration: ")
                 if (sal.windowStartMillis == null && sal.windowEndMillis == null) append("all readings")
                 else {
                     append(sal.windowStartMillis?.let { horaSeg(it) } ?: "first")
                     append(" → ")
                     append(sal.windowEndMillis?.let { horaSeg(it) } ?: "last")
                 }
             },
             style = MaterialTheme.typography.bodyMedium,
             color = if (sal.baseConductivity == null) MaterialTheme.colorScheme.error
                     else Color.Unspecified,
             modifier = Modifier.testTag("fb-salt-base"))
    }
}

// ------------------------------------ resultado ------------------------------------

/**
 * El boton de calcular y el caudal, con el mismo aspecto que el del area-velocidad para que
 * los dos se lean igual cuando el perfil se midio por los dos metodos.
 */
@Composable
private fun Resultado(vm: FieldbookViewModel, sal: SaltDilution, bloqueada: Boolean) {
    val falta = remember(sal) { SaltDilutionMath.missing(sal) }
    val r = remember(sal) { SaltDilutionMath.compute(sal) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!bloqueada) {
            Button(onClick = { vm.calculateDischarge() },
                   enabled = sal.baseConductivity != null && falta.isEmpty(),
                   modifier = Modifier.fillMaxWidth().testTag("fb-salt-calculate")) {
                Text("Calculate Discharge")
            }
            if (falta.isNotEmpty()) {
                Text("Still missing: " + falta.joinToString(", ") + ".",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.testTag("fb-salt-missing"))
            }
        }
        val q = r.dischargeM3s.takeIf { sal.calculated }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Discharge", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(q?.let { Decimals.fixed(it, 3) + " m³/s" } ?: "—",
                             style = MaterialTheme.typography.headlineSmall,
                             fontFamily = FontFamily.Monospace,
                             modifier = Modifier.testTag("fb-salt-total"))
                    }
                    Text(when {
                             q != null -> Decimals.fixed(q * 1000.0, 1) + " L/s"
                             sal.calculated -> "cannot be computed"
                             else -> "not calculated"
                         },
                         style = MaterialTheme.typography.bodySmall,
                         color = if (q != null) MaterialTheme.colorScheme.onSurfaceVariant
                                 else MaterialTheme.colorScheme.error,
                         modifier = Modifier.padding(bottom = 4.dp).testTag("fb-salt-progress"))
                }
                if (sal.calculated && sal.baseConductivity != null) {
                    HorizontalDivider(Modifier.padding(vertical = 3.dp))
                    Linea("Σ (excess × time)", Decimals.fixed(r.sigma, 1) + " µS/cm·s")
                    Linea("Readings integrated", "${r.readings}")
                    Linea("Peak above base", r.peakExcess?.let { Decimals.fixed(it, 1) + " µS/cm" } ?: "—")
                    Linea("Salt passage", r.passageSeconds?.let { formatElapsed((it * 1000).toLong()) } ?: "—")
                    // LO QUE LA GUIA PIDE PARA FIARSE: un pico de 50 a 100 uS/cm sobre la base
                    // y un paso de menos de 15-20 minutos. Se dice, no se impide.
                    val avisos = buildList {
                        r.peakExcess?.let {
                            if (it < 50) add("the peak is under 50 µS/cm: more salt would help")
                            if (it > 100) add("the peak is over 100 µS/cm: less salt would do")
                        }
                        r.passageSeconds?.let { if (it > 20 * 60) add("the passage took over 20 min: pools, or too long a reach") }
                    }
                    Text(if (avisos.isEmpty()) "Within the guide's range for a good measurement."
                         else "Guide check: " + avisos.joinToString("; ") + ".",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Linea(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

// ------------------------------------- tabla -------------------------------------

/**
 * Las lecturas, una por fila. Plegada por defecto cuando vienen de un fichero: un logger trae
 * miles, y desplegadas empujarian el pie de la nota a cientos de pantallas.
 */
@Composable
private fun TablaDeLecturas(vm: FieldbookViewModel, sal: SaltDilution, bloqueada: Boolean) {
    if (sal.readings.isEmpty()) return
    var abierta by remember(sal.readingsSource) {
        mutableStateOf(sal.readingsSource == ReadingsSource.MANUAL)
    }
    var cuantas by remember { mutableStateOf(200) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { abierta = !abierta }, modifier = Modifier.testTag("fb-salt-table-toggle")) {
            Text((if (abierta) "Hide" else "Show") + " the ${sal.readings.size} readings" +
                 (sal.importedFile?.let { " (from “$it”)" } ?: ""))
        }
        if (!abierta) return@Column
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Time", "After inj. (s)", "µS/cm").forEachIndexed { k, t ->
                Text(t, Modifier.weight(if (k == 0) 1.6f else 1f),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     textAlign = if (k == 0) TextAlign.Start else TextAlign.End)
            }
            if (!bloqueada) Spacer(Modifier.width(36.dp))
        }
        // Las MAS RECIENTES ARRIBA: durante la medida se mira la ultima, que es la que se
        // acaba de teclear y donde se ve un error de dedo.
        val orden = sal.readings.sortedByDescending { it.atEpochMillis }
        orden.take(cuantas).forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(horaSeg(r.atEpochMillis), Modifier.weight(1.6f), fontFamily = FontFamily.Monospace,
                     fontSize = 13.sp)
                Text(sal.injectionEpochMillis?.let { ((r.atEpochMillis - it) / 1000).toString() } ?: "—",
                     Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                     textAlign = TextAlign.End)
                Text(formatNumber(r.microSiemensPerCm), Modifier.weight(1f),
                     fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.End)
                if (!bloqueada) {
                    IconButton(onClick = { vm.removeConductivity(r.atEpochMillis) },
                               modifier = Modifier.width(36.dp).height(32.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete reading",
                             Modifier.width(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (orden.size > cuantas) {
            TextButton(onClick = { cuantas += 500 }) {
                Text("Show ${minOf(500, orden.size - cuantas)} more")
            }
        }
    }
}
