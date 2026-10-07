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
import cl.umag.glaciertemp.core.LogDecoder
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
                DeviceChart(records, signature, selected)
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
                    Text("The band is the min-max range of the records in each column; the " +
                         "line is their midpoint. No smoothing: there is no room for one pixel " +
                         "per record. Zooming in recomputes the columns over what is visible, " +
                         "down to single records.",
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
            metadata.withGeoid().positionDetail()?.let { d -> StatRow("", d, "stat-position-detail") }
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

/**
 * El grafico de la placa: zoom por ejes, cursor y la serie REDUCIDA SOBRE LO QUE SE VE.
 *
 * Antes la serie se reducia una sola vez a 480 columnas sobre el log entero y el zoom
 * ampliaba esas columnas: con 700.000 registros, acercar 200 veces mostraba dos columnas y
 * media del mismo tamano que antes, y ni un registro mas de detalle. Ahora cada ventana se
 * vuelve a reducir con los registros que caen dentro, asi que acercar llega hasta el registro
 * suelto. Es barato: la reduccion es un recorrido lineal y la ventana se busca por biseccion.
 */
@Composable
private fun DeviceChart(todos: List<Record>, signature: Int, channel: String) {
    val estado = remember(channel, todos) { TimePlotState() }
    // Ordenados por hora y sin los registros con el reloj sin poner (ver Chart.chartRecords).
    val preparados = remember(todos) { Chart.chartRecords(todos) }
    val records = preparados.records
    if (records.isEmpty()) return
    val origen = records.first().time
    // Segundos de cada registro desde el primero, CON fraccion: una captura continua lleva un
    // registro cada ~140 ms, y en segundos enteros siete caian en la misma x y el cursor no
    // podia separarlos. Sirven para buscar la ventana y el registro senalado por biseccion.
    val segundos = remember(records) {
        DoubleArray(records.size) {
            java.time.Duration.between(origen, records[it].time).toMillis() / 1000.0
        }
    }
    // La hora del cursor con la resolucion de los datos (minuto, segundo o milisegundo).
    val formatoHora = remember(records) { Stats.instantFormat(records) }
    val total = segundos.last().coerceAtLeast(1.0)
    val x0 = estado.x0 ?: 0.0
    val x1 = estado.x1 ?: total

    val serie = remember(records, channel, x0, x1) {
        fun buscar(s: Double): Int {
            var lo = 0; var hi = segundos.size
            while (lo < hi) { val m = (lo + hi) / 2; if (segundos[m] < s) lo = m + 1 else hi = m }
            return lo
        }
        // Un registro de mas a cada lado, para que la linea llegue hasta el borde.
        val a = (buscar(x0) - 1).coerceAtLeast(0)
        val b = (buscar(x1) + 1).coerceAtMost(records.size)
        runCatching { Chart.series(records.subList(a, b), signature, channel) }.getOrNull()
    }
    val puntos = remember(serie) {
        serie?.samples?.map {
            PlotPoint(java.time.Duration.between(origen, it.time).toMillis() / 1000.0, it.lo, it.hi)
        } ?: emptyList()
    }
    val idx = remember(signature, channel) {
        LogFormat.fields(signature).indexOfFirst { it.name == channel }
    }
    val decimales = remember(signature, channel) {
        LogFormat.fields(signature).getOrNull(idx)?.decimals ?: 2
    }

    // El alcance vertical del registro entero, para acotar el zoom.
    val serieCompleta = remember(records, channel) {
        runCatching { Chart.series(records, signature, channel) }.getOrNull()
    }

    TimePlotControls(estado, "chart")
    TimePlot(points = puntos, origin = origen, state = estado,
             cuts = serie?.gaps?.toSet() ?: emptySet(),
             autoY = serie?.let { it.yMin to it.yMax },
             xLimits = 0.0 to total,
             yLimits = serieCompleta?.let { it.yMin to it.yMax },
             modifier = Modifier.fillMaxWidth().height(220.dp).testTag("chart"))
    if (preparados.farDated > 0) {
        Text("${preparados.farDated} record(s) dated far from the rest (the board clock was " +
             "not set yet) are left out of the chart. They are still in the data and the CSV.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.error,
             modifier = Modifier.testTag("chart-far-dated"))
    }
    if (preparados.reordered) {
        Text("The log is not in time order (the board clock was changed while logging); the " +
             "chart shows it sorted by time.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // EL VALOR DEL REGISTRO DE VERDAD, no el de la columna. Con la serie reducida el punto
    // dibujado es el centro de una banda que resume cientos de registros; lo que se quiere
    // leer al senalar es un dato, asi que se busca el registro valido mas cercano en el log.
    val cursor = estado.cursorX
    if (cursor != null && idx >= 0) {
        val reg = remember(cursor, records, idx) {
            var lo = 0; var hi = segundos.size
            while (lo < hi) { val m = (lo + hi) / 2; if (segundos[m] < cursor) lo = m + 1 else hi = m }
            // El mas cercano CON lectura: un registro sin valor en este canal no se senala.
            (0 until 2000).asSequence().flatMap { d -> sequenceOf(lo - d, lo + d - 1) }
                .filter { it in records.indices && records[it].values.getOrNull(idx) != null }
                .minByOrNull { kotlin.math.abs(segundos[it] - cursor) }
        }
        reg?.let { i ->
            val r = records[i]
            Text("${formatoHora.format(r.time)}   $channel = " +
                 LogDecoder.formatValue(r.values[idx], decimales) + " " + LogFormat.unitOf(channel),
                 style = MaterialTheme.typography.bodyMedium,
                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                 modifier = Modifier.testTag("chart-cursor"))
        }
    } else if (!estado.zoomMode) {
        Text("Drag sideways on the chart to read a value. Turn zoom on to stretch the axes.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
