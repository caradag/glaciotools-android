package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.umag.glaciertemp.core.BoardCalibration
import cl.umag.glaciertemp.core.BoardCalibration.Plan
import cl.umag.glaciertemp.core.Chart
import cl.umag.glaciertemp.core.Decimals

/**
 * Calibracion de la bateria y de los pines analogicos con tantos puntos como se quiera.
 *
 * Solo en modo avanzado: escribe en la EEPROM de la placa y cambia como se interpreta todo lo
 * que mida desde entonces. Lo que se hace aqui con un boton es lo mismo que se hacia a mano en
 * el terminal (V, A0, A01=..) pero con una recta ajustada a todas las lecturas en vez de
 * fiarlo todo a dos.
 */
@Composable
internal fun CalibrationCard(vm: DeviceViewModel, s: UiState) {
    val cal = s.calibration
    val canales = BoardCalibration.channels(s.info?.signature)
    val canal = canales.firstOrNull { it.key == cal.channel } ?: canales.first()
    val nombre = cal.names[canal.key]
    val puntos = cal.points[canal.key].orEmpty()
    val ajuste = remember(puntos) { BoardCalibration.fit(puntos) }
    val plan = remember(ajuste, puntos) { ajuste?.let { BoardCalibration.plan(it, puntos) } }
    val proto = s.info?.protocol ?: 0
    var real by remember { mutableStateOf("") }
    val valor = parseNumber(real)
    val unidad = canal.unit?.let { " $it" } ?: ""

    Card(Modifier.fillMaxWidth().testTag("cal-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            Text("Put the sensor at a known value, type it and press Get raw: the board reads " +
                 "its ADC exactly as in a normal measurement. Repeat over the whole range; the " +
                 "line is fitted to all the points.",
                 style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Channel", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                var abierto by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { abierto = true }, enabled = !s.busy,
                                   modifier = Modifier.testTag("cal-channel")) {
                        Text(etiqueta(canal, nombre))
                    }
                    DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                        canales.forEach { c ->
                            DropdownMenuItem(text = { Text(etiqueta(c, cal.names[c.key])) },
                                             onClick = { abierto = false; vm.setCalibrationChannel(c.key) },
                                             modifier = Modifier.testTag("cal-channel-${c.key}"))
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = real, onValueChange = { real = it },
                    label = { Text("Real value" + (canal.unit?.let { " ($it)" } ?: "")) },
                    singleLine = true,
                    isError = real.isNotBlank() && valor == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("cal-real"))
                Button(onClick = { valor?.let { vm.calibrationGetRaw(it) } },
                       enabled = !s.busy && valor != null,
                       modifier = Modifier.testTag("cal-get-raw")) { Text("Get raw") }
            }

            if (puntos.isNotEmpty()) {
                GraficoDeCalibracionPlaca(puntos, ajuste,
                    Modifier.fillMaxWidth().height(200.dp).testTag("cal-plot"))
                Text(ajuste?.let {
                         "raw = ${Decimals.fixed(it.intercept, 2)} + ${Decimals.fixed(it.slope, 3)} × real" +
                         "\nRMSE ${Decimals.fixed(it.rmse, 4)}$unidad  ·  n = ${it.n}"
                     } ?: "Two different real values are needed to fit a line.",
                     fontFamily = FontFamily.Monospace,
                     style = MaterialTheme.typography.bodySmall,
                     modifier = Modifier.testTag("cal-fit"))

                // La tabla, para poder quitar un punto malo: la lectura que se tomo con el
                // sensor aun bajando pesa en la recta lo mismo que las buenas.
                puntos.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val residuo = ajuste?.let { a -> (p.raw - a.rawAt(p.real)) / a.slope }
                        Text("${num(p.real)}$unidad  →  ${p.raw}" +
                             (residuo?.let { "    (${if (it >= 0) "+" else ""}${Decimals.fixed(it, 3)})" } ?: ""),
                             fontFamily = FontFamily.Monospace,
                             style = MaterialTheme.typography.bodySmall,
                             modifier = Modifier.weight(1f).testTag("cal-point-$i"))
                        IconButton(onClick = { vm.removeCalibrationPoint(i) }, enabled = !s.busy,
                                   modifier = Modifier.size(32.dp).testTag("cal-remove-$i")) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove point",
                                 modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Text("In brackets, how far each point is from the line, in real units.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonCopiar({
                        BoardCalibration.clipboard(canal, nombre, puntos, ajuste, plan,
                            s.info?.displayId,
                            java.time.LocalDateTime.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    }, "cal-copy")
                    TextButton(onClick = { vm.clearCalibrationPoints() }, enabled = !s.busy,
                               modifier = Modifier.testTag("cal-clear")) { Text("Clear") }
                }

                HorizontalDivider()
                when {
                    proto < BoardCalibration.PAIR_PROTOCOL ->
                        Text("Sending the calibration needs firmware 3.16 or newer (this board " +
                             "has ${s.info?.firmware}). Older firmware would store the count it " +
                             "reads at that moment instead of the one sent.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error,
                             modifier = Modifier.testTag("cal-old-firmware"))
                    plan is Plan.Problem ->
                        Text(plan.message, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error,
                             modifier = Modifier.testTag("cal-problem"))
                    plan is Plan.Ok ->
                        Text("Sends two points of the line, at the ends of the measured range: " +
                             "${BoardCalibration.command(canal, 1, plan.first)} and " +
                             BoardCalibration.command(canal, 2, plan.second) + ".",
                             style = MaterialTheme.typography.bodySmall,
                             modifier = Modifier.testTag("cal-plan"))
                }
                Button(onClick = { vm.requestSendCalibration() },
                       enabled = !s.busy && plan is Plan.Ok && proto >= BoardCalibration.PAIR_PROTOCOL,
                       modifier = Modifier.testTag("cal-send")) { Text("Send calibration to board") }
                cal.sendResult?.let {
                    Text((if (cal.sendOk) "Stored:\n" else "NOT confirmed:\n") + it,
                         fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall,
                         color = if (cal.sendOk) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.error,
                         modifier = Modifier.testTag("cal-result"))
                }
            }

            // La consola se cierra sola a los dos minutos del ultimo comando, y bajar un sensor
            // de una marca a la siguiente puede tardar mas. Decirlo evita creer que se rompio.
            Text("The board leaves the console 2 min after the last command. For a slow " +
                 "series, send S10 in the Terminal to keep it open 10 min. Points are kept if " +
                 "you have to reconnect.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (cal.confirmSend && plan is Plan.Ok) {
        AlertDialog(
            onDismissRequest = { vm.cancelSendCalibration() },
            title = { Text("Write calibration?") },
            text = {
                Text("This replaces the calibration of ${etiqueta(canal, nombre)} stored on the " +
                     "board, and changes every value it records from now on.\n\n" +
                     "${BoardCalibration.command(canal, 1, plan.first)}\n" +
                     BoardCalibration.command(canal, 2, plan.second))
            },
            confirmButton = {
                TextButton(onClick = { vm.sendCalibration() },
                           modifier = Modifier.testTag("cal-send-confirm")) { Text("Write") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelSendCalibration() }) { Text("Cancel") }
            },
        )
    }
}

private fun etiqueta(c: BoardCalibration.Channel, nombre: String?): String =
    c.label + (nombre?.let { " · $it" } ?: "")

private fun num(v: Double): String =
    Decimals.fixed(v, 4).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }

/** Cuentas frente a valor real, con la recta ajustada. */
@Composable
private fun GraficoDeCalibracionPlaca(puntos: List<BoardCalibration.Point>,
                                     ajuste: BoardCalibration.Fit?, modifier: Modifier) {
    val medir = rememberTextMeasurer()
    val ejes = MaterialTheme.colorScheme.outline
    val punto = MaterialTheme.colorScheme.primary
    val recta = MaterialTheme.colorScheme.tertiary
    val estilo = TextStyle(fontSize = 9.sp, color = ejes)
    Canvas(modifier) {
        if (puntos.isEmpty()) return@Canvas
        fun rango(a: Double, b: Double): Pair<Double, Double> {
            var lo = a; var hi = b
            if (hi <= lo) { lo -= 1; hi += 1 }
            val m = (hi - lo) * 0.08
            return (lo - m) to (hi + m)
        }
        val (x0, x1) = rango(puntos.minOf { it.real }, puntos.maxOf { it.real })
        val (y0, y1) = rango(puntos.minOf { it.raw }.toDouble(), puntos.maxOf { it.raw }.toDouble())
        val tx = Chart.yTicks(x0, x1, 4)
        val ty = Chart.yTicks(y0, y1, 4)
        val izq = (ty.maxOfOrNull { medir.measure(it.label, estilo).size.width } ?: 0) + 10f
        val abajo = size.height - 30f
        fun x(v: Double) = izq + ((v - x0) / (x1 - x0)).toFloat() * (size.width - izq - 4f)
        fun y(v: Double) = abajo - ((v - y0) / (y1 - y0)).toFloat() * (abajo - 4f)
        ty.forEach { t ->
            drawLine(ejes.copy(alpha = 0.25f), Offset(izq, y(t.value)), Offset(size.width, y(t.value)), 1f)
            val l = medir.measure(t.label, estilo)
            drawText(l, topLeft = Offset(izq - l.size.width - 6f, y(t.value) - l.size.height / 2f))
        }
        tx.forEach { t ->
            drawLine(ejes.copy(alpha = 0.25f), Offset(x(t.value), 4f), Offset(x(t.value), abajo), 1f)
            val l = medir.measure(t.label, estilo)
            drawText(l, topLeft = Offset(x(t.value) - l.size.width / 2f, abajo + 2f))
        }
        drawLine(ejes, Offset(izq, 4f), Offset(izq, abajo), 1.5f)
        drawLine(ejes, Offset(izq, abajo), Offset(size.width, abajo), 1.5f)
        val rx = medir.measure("real value", estilo)
        drawText(rx, topLeft = Offset(size.width - rx.size.width, abajo + 14f))
        val ry = medir.measure("raw counts", estilo)
        drawText(ry, topLeft = Offset(izq + 6f, 4f))
        // Recortada al grafico: con un punto disparatado la recta pasa por fuera.
        ajuste?.let { a ->
            clipRect(izq, 4f, size.width, abajo) {
                drawLine(recta, Offset(x(x0), y(a.rawAt(x0))), Offset(x(x1), y(a.rawAt(x1))), 2f)
            }
        }
        puntos.forEach { p -> drawCircle(punto, 4f, Offset(x(p.real), y(p.raw.toDouble()))) }
    }
}
