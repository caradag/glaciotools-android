package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import cl.umag.glaciertemp.core.DownloadMetadata
import cl.umag.glaciertemp.core.BoardClock
import cl.umag.glaciertemp.core.Sampling
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import cl.umag.glaciertemp.core.Chart
import cl.umag.glaciertemp.core.LogFormat
import cl.umag.glaciertemp.core.Record
import cl.umag.glaciertemp.core.Series
import cl.umag.glaciertemp.core.Stats

@Composable
fun ChartCard(records: List<Record>, signature: Int,
              metadata: DownloadMetadata? = null) {
    val channels = remember(signature) { LogFormat.fields(signature).map { it.name } }
    var selected by remember(signature) { mutableStateOf(channels.firstOrNull() ?: "") }
    if (channels.isEmpty() || records.isEmpty()) return

    val series = remember(records, selected) {
        runCatching { Chart.series(records, signature, selected) }.getOrNull()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chart", style = MaterialTheme.typography.titleMedium)

            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                channels.forEach { name ->
                    FilterChip(
                        selected = name == selected,
                        onClick = { selected = name },
                        label = { Text(name) },
                        modifier = Modifier.testTag("chip-$name"),
                    )
                }
            }

            if (series == null || series.isEmpty) {
                Text("No valid readings in $selected", Modifier.testTag("chart-empty"),
                     style = MaterialTheme.typography.bodySmall)
            } else {
                // Zoom y desplazamiento con los dedos. El desplazamiento se acota a la
                // ventana visible para que no se pueda arrastrar el grafico fuera de vista.
                var zoom by remember(series.channel) { mutableFloatStateOf(1f) }
                var pan by remember(series.channel) { mutableFloatStateOf(0f) }
                // El grafico NO puede tragarse el arrastre vertical: si lo hace, la pagina
                // deja de poder desplazarse mas alla de el. Solo se consume el pellizco --que
                // siempre lleva dos dedos-- y el arrastre de un dedo cuando ya hay zoom, que
                // es cuando el usuario espera mover la vista y no la pagina.
                val gestos = Modifier.pointerInput(series.channel) {
                    awaitPointerEventScope {
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedos = evento.changes.count { it.pressed }
                            val zoomChange = evento.calculateZoom()
                            val panChange = evento.calculatePan()
                            val pellizco = dedos >= 2
                            if (!pellizco && zoom <= 1f) continue   // se deja pasar

                            val nuevoZoom = (zoom * zoomChange).coerceIn(1f, 200f)
                            val nuevoPan = pan - panChange.x / (size.width * nuevoZoom)
                            zoom = nuevoZoom
                            pan = nuevoPan.coerceIn(0f, 1f - 1f / nuevoZoom)
                            evento.changes.forEach { it.consume() }
                        }
                    }
                }
                SeriesPlot(series, zoom, pan,
                           Modifier.fillMaxWidth().height(220.dp).then(gestos).testTag("chart"))
                if (zoom > 1f) {
                    TextButton(onClick = { zoom = 1f; pan = 0f },
                               modifier = Modifier.testTag("chart-reset")) {
                        Text("Reset view (%.0fx)".format(zoom))
                    }
                }
                Text(
                    buildString {
                        append("${records.size} records")
                        // Perder puntos por huecos no es lo mismo que reducir la serie.
                        if (series.isReduced) {
                            append("  ·  ${series.samples.size} columns of ${series.bucket} " +
                                   "records (min/max)")
                        }
                        if (series.gaps.isNotEmpty()) {
                            append("  ·  ${series.gaps.size} gaps with no reading")
                        }
                    },
                    Modifier.testTag("chart-caption"),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Sin esta frase la banda parece una media movil o un margen de error, que
                // es justo lo que NO es.
                if (series.isReduced) {
                    Text("The band is the min-max range of the ${series.bucket} records in each " +
                         "column; the line is their midpoint. No smoothing: there is no " +
                         "room for one pixel per record.",
                         Modifier.testTag("chart-legend"),
                         style = MaterialTheme.typography.bodySmall)
                }

                StatsBlock(records, signature, selected, metadata)
            }
        }
    }
}

/** Minimo, maximo y periodo cubierto, calculados sobre los registros crudos. */
@Composable
private fun StatsBlock(records: List<Record>, signature: Int, channel: String,
                       metadata: DownloadMetadata? = null) {
    val st = remember(records, channel) {
        runCatching { Stats.channel(records, signature, channel) }.getOrNull()
    } ?: return
    // Del propio log y no de la configuracion de la placa: el intervalo pudo cambiar durante
    // el despliegue, y lo que interesa es lo que de verdad se registro.
    val muestreo = remember(records) { runCatching { Sampling.of(records) }.getOrNull() }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatRow("Minimum", "${st.format(st.min)}   ${Stats.formatInstant(st.minAt)}",
                "stat-min")
        StatRow("Maximum", "${st.format(st.max)}   ${Stats.formatInstant(st.maxAt)}",
                "stat-max")
        StatRow("Mean", st.format(st.mean), "stat-mean")
        StatRow("Start", Stats.formatInstant(st.first), "stat-first")
        StatRow("End", Stats.formatInstant(st.last), "stat-last")
        StatRow("Duration", Stats.formatSpan(st.first, st.last), "stat-span")
        // El desfase del reloj al descargar describe el conjunto de datos igual que Coverage:
        // dice cuanto hay que desconfiar de las marcas de tiempo. Con el sentido escrito en
        // palabras, porque un "+37 s" a secas se interpreta al reves la mitad de las veces.
        // Solo aparece si los datos vienen de una descarga; un fichero abierto no lo trae.
        metadata?.offsetDescription()?.let {
            StatRow("Board clock offset", it, "stat-clock-offset")
        }
        // La posicion desde donde se descargo, con el MISMO texto que va a la cabecera del
        // CSV: las dos vistas salen de DownloadMetadata y no pueden divergir. Solo aparece
        // si los datos vienen de una descarga; un fichero abierto no la trae.
        metadata?.positionDescription()?.let {
            StatRow("Downloaded from", it, "stat-position")
            metadata.positionDetail()?.let { d -> StatRow("", d, "stat-position-detail") }
        }
        if (metadata?.position == null) {
            metadata?.positionNote?.let {
                StatRow("Downloaded from", "no position -- $it", "stat-position")
            }
        }
        muestreo?.let { m ->
            StatRow("Sample interval", BoardClock.format(m.typicalSeconds), "stat-interval")
            StatRow("Largest gap", BoardClock.format(m.maxGapSeconds), "stat-maxgap")
            if (m.missing > 0) {
                StatRow("Missing samples",
                        "${m.missing} in ${m.gaps} gap" + (if (m.gaps == 1) "" else "s"),
                        "stat-missing-samples")
            }
            StatRow("Coverage", "%.1f %% of the period".format(m.coverage), "stat-coverage")
        }
        if (st.missing > 0) {
            StatRow("Missing", "${st.missing} of ${st.count + st.missing}", "stat-missing")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(96.dp),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SeriesPlot(
    series: Series,
    zoom: Float,
    pan: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val band = line.copy(alpha = 0.28f)
    val axis = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(fontSize = 9.sp, color = axis)

    Canvas(modifier) {
        val ticks = Chart.yTicks(series.yMin, series.yMax)
        val labelW = ticks.maxOfOrNull {
            measurer.measure(it.label, labelStyle).size.width.toFloat()
        } ?: 0f
        val left = labelW + 10f
        val bottom = size.height - 18f
        val plot = Rect(left, 6f, size.width, bottom)
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        fun yOf(v: Double): Float {
            val f = (v - series.yMin) / (series.yMax - series.yMin)
            return plot.bottom - (f.toFloat() * plot.height)
        }
        // La X sale del TIEMPO de cada muestra, no de su indice: con el indice, una noche
        // entera sin registrar ocupaba lo mismo que un intervalo de muestreo y el hueco no
        // se veia. zoom y pan son la ventana que el usuario tiene abierta.
        val fracciones = series.xFractions
        fun xOf(i: Int): Float {
            val f = fracciones.getOrElse(i) { 0f }
            return plot.left + plot.width * ((f - pan) * zoom)
        }

        ticks.forEach { t ->
            val y = yOf(t.value)
            drawLine(axis.copy(alpha = 0.25f), Offset(plot.left, y), Offset(plot.right, y), 1f)
            val l = measurer.measure(t.label, labelStyle)
            drawText(l, topLeft = Offset(plot.left - l.size.width - 6f, y - l.size.height / 2f))
        }
        drawLine(axis, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), 1.5f)
        drawLine(axis, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1.5f)

        clipRect(plot.left, plot.top, plot.right, plot.bottom) {
            // Los huecos parten la serie en tramos: unirlos dibujaria datos inexistentes.
            val cuts = series.gaps.toSet()
            var start = 0
            while (start < series.samples.size) {
                var end = start + 1
                while (end < series.samples.size && end !in cuts) end++
                drawSegment(series, start, end, ::xOf, ::yOf, line, band)
                start = end
            }
        }

        // Marcas temporales, calculadas sobre la ventana visible y no sobre el total.
        val t0 = series.samples.first().time
        val t1 = series.samples.last().time
        val totalSec = java.time.Duration.between(t0, t1).seconds
        val desde = t0.plusSeconds((totalSec * pan).toLong())
        val hasta = t0.plusSeconds((totalSec * (pan + 1f / zoom)).toLong().coerceAtMost(totalSec))
        val labels = Chart.timeTicks(desde, hasta)
        labels.forEachIndexed { k, s2 ->
            val l = measurer.measure(s2, labelStyle)
            val x = plot.left + plot.width * k / (labels.size - 1).coerceAtLeast(1)
            val tx = (x - l.size.width / 2f).coerceIn(0f, size.width - l.size.width)
            drawText(l, topLeft = Offset(tx, bottom + 3f))
        }
    }
}

/** Banda min/max cuando hubo reduccion; linea simple cuando cada punto es un registro. */
private fun DrawScope.drawSegment(
    s: Series, from: Int, to: Int,
    xOf: (Int) -> Float, yOf: (Double) -> Float,
    line: Color, band: Color,
) {
    if (to - from <= 0) return
    val hasBand = (from until to).any { s.samples[it].hi > s.samples[it].lo }
    if (hasBand) {
        val p = Path()
        p.moveTo(xOf(from), yOf(s.samples[from].hi))
        for (i in from until to) p.lineTo(xOf(i), yOf(s.samples[i].hi))
        for (i in to - 1 downTo from) p.lineTo(xOf(i), yOf(s.samples[i].lo))
        p.close()
        drawPath(p, band)
    }
    if (to - from == 1) {
        val x = xOf(from)
        drawCircle(line, 2.5f, Offset(x, yOf(s.samples[from].lo)))
        return
    }
    val p = Path()
    p.moveTo(xOf(from), yOf((s.samples[from].lo + s.samples[from].hi) / 2))
    for (i in from + 1 until to) {
        p.lineTo(xOf(i), yOf((s.samples[i].lo + s.samples[i].hi) / 2))
    }
    drawPath(p, line, style = Stroke(width = 2f))
}
