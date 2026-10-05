package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.umag.glaciertemp.core.Chart
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Un punto de una serie temporal: segundos desde el origen del grafico, y el valor.
 *
 * [lo] y [hi] coinciden salvo cuando el punto resume varios registros (la reduccion min/max
 * del grafico de la placa); entonces el punto es una banda.
 */
data class PlotPoint(val x: Double, val lo: Double, val hi: Double = lo) {
    val mid: Double get() = (lo + hi) / 2.0
}

/**
 * Lo que se esta mirando de un grafico de tiempo: la ventana, el modo y el cursor.
 *
 * TODO EN UNIDADES DE DATOS --segundos y el valor de la serie-- y no en fracciones de la
 * pantalla. El grafico de GPS guardaba el arrastre como fraccion del ancho y lo aplicaba en
 * otra escala, y el contenido se movia a la decima parte que el dedo. Con la ventana en
 * unidades de datos, un arrastre de N pixeles es N * (ancho de la ventana / ancho en pixeles),
 * y no hay ninguna otra escala por medio.
 *
 * Los ejes se acercan POR SEPARADO. Una curva de conductividad es un pico de un par de minutos
 * en una hora de registro, y lo que se quiere es estirar el tiempo sin aplastar el pico, o
 * mirar de cerca los ultimos microsiemens de la cola sin perder el tramo.
 */
@Stable
class TimePlotState {
    /** null = el rango entero de los datos. */
    var x0 by mutableStateOf<Double?>(null)
    var x1 by mutableStateOf<Double?>(null)
    var y0 by mutableStateOf<Double?>(null)
    var y1 by mutableStateOf<Double?>(null)

    /**
     * Con el zoom activo los dedos mueven y estiran el grafico. Sin el, un dedo pone el
     * cursor. Son excluyentes a proposito: el mismo arrastre no puede significar las dos
     * cosas, y adivinar cual se queria es justo lo que hace que un grafico "no obedezca".
     */
    var zoomMode by mutableStateOf(false)

    /** Donde esta el cursor, en segundos desde el origen. Se engancha al dato mas cercano. */
    var cursorX by mutableStateOf<Double?>(null)

    val zoomed: Boolean get() = x0 != null || y0 != null

    fun reset() { x0 = null; x1 = null; y0 = null; y1 = null }
}

/** El dato mas cercano a un instante, por tiempo. Null si no hay datos. */
fun nearestIndex(points: List<PlotPoint>, x: Double): Int? {
    if (points.isEmpty()) return null
    var lo = 0; var hi = points.lastIndex
    while (lo < hi) {
        val m = (lo + hi) / 2
        if (points[m].x < x) lo = m + 1 else hi = m
    }
    return if (lo > 0 && abs(points[lo - 1].x - x) <= abs(points[lo].x - x)) lo - 1 else lo
}

/** Una linea horizontal de referencia: la base, por ejemplo. */
data class PlotHLine(val y: Double, val color: Color, val label: String? = null)

/** Una linea vertical de referencia: el principio o el final de la integral. */
data class PlotVLine(val x: Double, val color: Color, val label: String? = null)

/**
 * El interruptor del zoom y el boton de volver a verlo todo, encima de un grafico.
 *
 * Con el zoom APAGADO el grafico deja pasar el arrastre vertical a la pagina, para que se
 * pueda seguir desplazando por encima de el; con el zoom ENCENDIDO se queda con todos los
 * gestos, que es lo que se espera al haberlo encendido.
 */
@Composable
fun TimePlotControls(state: TimePlotState, tag: String, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.zoomMode,
                   onClick = { state.zoomMode = !state.zoomMode },
                   label = { Text(if (state.zoomMode) "Zoom on" else "Zoom off") },
                   modifier = Modifier.testTag("$tag-zoom"))
        if (state.zoomed) {
            TextButton(onClick = { state.reset() },
                       modifier = Modifier.testTag("$tag-reset")) { Text("Show all") }
        }
        extra()
    }
}

/**
 * Un grafico de una serie temporal con zoom por ejes y cursor.
 *
 * @param origin la hora del x = 0, para escribir las marcas en horas de reloj.
 * @param cuts indices donde la serie se corta (un hueco sin datos): no se unen con una recta.
 * @param autoY rango vertical a usar mientras el usuario no haya acercado el eje vertical. Lo
 *   pasa quien llama porque depende de lo que este a la vista (la placa reduce por ventana).
 */
@Composable
fun TimePlot(
    points: List<PlotPoint>,
    origin: LocalDateTime,
    state: TimePlotState,
    modifier: Modifier = Modifier,
    cuts: Set<Int> = emptySet(),
    autoY: Pair<Double, Double>? = null,
    hLines: List<PlotHLine> = emptyList(),
    vLines: List<PlotVLine> = emptyList(),
    showDots: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val band = line.copy(alpha = 0.28f)
    val axis = MaterialTheme.colorScheme.outline
    val cursorColor = MaterialTheme.colorScheme.tertiary
    val labelStyle = TextStyle(fontSize = 9.sp, color = axis)

    // Los extremos de los datos, para acotar el zoom y para "verlo todo".
    val fullX0 = points.firstOrNull()?.x ?: 0.0
    val fullX1 = (points.lastOrNull()?.x ?: 1.0).let { if (it <= fullX0) fullX0 + 1.0 else it }
    val dataY = autoY ?: run {
        if (points.isEmpty()) 0.0 to 1.0 else {
            var lo = points.minOf { it.lo }; var hi = points.maxOf { it.hi }
            (hLines.map { it.y }).forEach { lo = minOf(lo, it); hi = maxOf(hi, it) }
            if (hi <= lo) { lo -= 0.5; hi += 0.5 }
            val m = (hi - lo) * 0.08
            (lo - m) to (hi + m)
        }
    }

    fun vx0() = state.x0 ?: fullX0
    fun vx1() = state.x1 ?: fullX1
    fun vy0() = state.y0 ?: dataY.first
    fun vy1() = state.y1 ?: dataY.second

    // Lo que ocupan las etiquetas del eje vertical se calcula al dibujar; los gestos
    // necesitan el rectangulo del grafico, asi que se guarda aqui.
    var plotRect by androidx.compose.runtime.remember { mutableStateOf(Rect.Zero) }

    val gestos = if (state.zoomMode) {
        Modifier.pointerInput(points, dataY) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    val vivos = ev.changes.filter { it.pressed && it.previousPressed }
                    val r = plotRect
                    if (vivos.isEmpty() || r.width <= 0f || r.height <= 0f) continue
                    var x0 = vx0(); var x1 = vx1(); var y0 = vy0(); var y1 = vy1()

                    if (vivos.size >= 2) {
                        val a = vivos[0]; val b = vivos[1]
                        val antes = b.previousPosition - a.previousPosition
                        val ahora = b.position - a.position
                        // CADA EJE POR SU LADO: los dedos separados en horizontal estiran
                        // el tiempo, en vertical el valor. Por debajo de 40 px de
                        // separacion en un eje, ese eje no se toca: dos dedos casi
                        // alineados en vertical darian una razon horizontal disparatada.
                        val cx = (a.position.x + b.position.x) / 2f
                        val cy = (a.position.y + b.position.y) / 2f
                        if (abs(antes.x) > 40f && abs(ahora.x) > 40f) {
                            val f = (abs(antes.x) / abs(ahora.x)).toDouble()
                            val anclaX = x0 + (cx - r.left) / r.width * (x1 - x0)
                            x0 = anclaX - (anclaX - x0) * f; x1 = anclaX + (x1 - anclaX) * f
                        }
                        if (abs(antes.y) > 40f && abs(ahora.y) > 40f) {
                            val f = (abs(antes.y) / abs(ahora.y)).toDouble()
                            val anclaY = y1 - (cy - r.top) / r.height * (y1 - y0)
                            y0 = anclaY - (anclaY - y0) * f; y1 = anclaY + (y1 - anclaY) * f
                        }
                    }
                    // El desplazamiento: el del centro de los dedos que hay apoyados.
                    val dx = vivos.map { it.position.x - it.previousPosition.x }.average()
                    val dy = vivos.map { it.position.y - it.previousPosition.y }.average()
                    val sx = (x1 - x0) / r.width
                    val sy = (y1 - y0) / r.height
                    x0 -= dx * sx; x1 -= dx * sx
                    y0 += dy * sy; y1 += dy * sy

                    // Nunca mas alla de un segundo de ancho ni de lo que hay mas un margen.
                    if (x1 - x0 < 1.0) { val c = (x0 + x1) / 2; x0 = c - 0.5; x1 = c + 0.5 }
                    val full = fullX1 - fullX0
                    if (x1 - x0 > full * 1.5) { x0 = fullX0 - full * 0.25; x1 = fullX1 + full * 0.25 }
                    state.x0 = x0; state.x1 = x1
                    if (y1 - y0 > 1e-9) { state.y0 = y0; state.y1 = y1 }
                    ev.changes.forEach { it.consume() }
                }
            }
        }
    } else {
        fun mover(px: Float) {
            val r = plotRect
            if (r.width <= 0f || points.isEmpty()) return
            val x = vx0() + (px - r.left) / r.width * (vx1() - vx0())
            nearestIndex(points, x)?.let { state.cursorX = points[it].x }
        }
        // SOLO EL ARRASTRE HORIZONTAL mueve el cursor. El vertical sigue siendo de la
        // pagina: este grafico vive dentro de pantallas que se desplazan, y uno que se
        // traga el dedo deja la mitad de abajo inalcanzable.
        Modifier
            .pointerInput(points) { detectTapGestures { mover(it.x) } }
            .pointerInput(points) {
                detectHorizontalDragGestures { ch, _ -> mover(ch.position.x); ch.consume() }
            }
    }

    Canvas(modifier.then(gestos)) {
        val y0 = vy0(); val y1 = vy1(); val x0 = vx0(); val x1 = vx1()
        val ticks = Chart.yTicks(y0, y1)
        val labelW = ticks.maxOfOrNull { measurer.measure(it.label, labelStyle).size.width.toFloat() } ?: 0f
        val left = labelW + 10f
        val bottom = size.height - 18f
        val r = Rect(left, 6f, size.width - 4f, bottom)
        if (r.width <= 0f || r.height <= 0f) return@Canvas
        plotRect = r

        fun xOf(x: Double) = r.left + ((x - x0) / (x1 - x0)).toFloat() * r.width
        fun yOf(v: Double) = r.bottom - ((v - y0) / (y1 - y0)).toFloat() * r.height

        ticks.forEach { t ->
            val y = yOf(t.value)
            drawLine(axis.copy(alpha = 0.25f), Offset(r.left, y), Offset(r.right, y), 1f)
            val l = measurer.measure(t.label, labelStyle)
            drawText(l, topLeft = Offset(r.left - l.size.width - 6f, y - l.size.height / 2f))
        }

        // Marcas de tiempo REDONDAS sobre la ventana visible.
        val desde = origin.plusNanos((x0 * 1e9).toLong())
        val hasta = origin.plusNanos((x1 * 1e9).toLong())
        val nMarcas = (r.width / 140f).toInt().coerceIn(2, 6)
        Chart.roundTimeTicks(desde, hasta, nMarcas).forEach { t ->
            val seg = java.time.Duration.between(origin, t.time).toNanos() / 1e9
            val x = xOf(seg)
            drawLine(axis.copy(alpha = 0.18f), Offset(x, r.top), Offset(x, r.bottom), 1f)
            val l = measurer.measure(t.label, labelStyle)
            val tx = (x - l.size.width / 2f).coerceIn(0f, size.width - l.size.width)
            drawText(l, topLeft = Offset(tx, bottom + 3f))
        }
        drawLine(axis, Offset(r.left, r.top), Offset(r.left, r.bottom), 1.5f)
        drawLine(axis, Offset(r.left, r.bottom), Offset(r.right, r.bottom), 1.5f)

        clipRect(r.left, r.top, r.right, r.bottom) {
            hLines.forEach { h ->
                val y = yOf(h.y)
                drawLine(h.color, Offset(r.left, y), Offset(r.right, y), 2f,
                         pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                h.label?.let {
                    val l = measurer.measure(it, labelStyle.copy(color = h.color))
                    drawText(l, topLeft = Offset(r.right - l.size.width - 4f, y - l.size.height - 2f))
                }
            }
            vLines.forEach { v ->
                val x = xOf(v.x)
                drawLine(v.color, Offset(x, r.top), Offset(x, r.bottom), 2f)
                v.label?.let {
                    val l = measurer.measure(it, labelStyle.copy(color = v.color))
                    drawText(l, topLeft = Offset(x + 3f, r.top + 2f))
                }
            }

            // Los tramos entre cortes, con banda donde hubo reduccion.
            var inicio = 0
            while (inicio < points.size) {
                var fin = inicio + 1
                while (fin < points.size && fin !in cuts) fin++
                val conBanda = (inicio until fin).any { points[it].hi > points[it].lo }
                if (conBanda) {
                    val p = Path()
                    p.moveTo(xOf(points[inicio].x), yOf(points[inicio].hi))
                    for (i in inicio until fin) p.lineTo(xOf(points[i].x), yOf(points[i].hi))
                    for (i in fin - 1 downTo inicio) p.lineTo(xOf(points[i].x), yOf(points[i].lo))
                    p.close()
                    drawPath(p, band)
                }
                if (fin - inicio == 1) {
                    drawCircle(line, 3f, Offset(xOf(points[inicio].x), yOf(points[inicio].mid)))
                } else {
                    val p = Path()
                    p.moveTo(xOf(points[inicio].x), yOf(points[inicio].mid))
                    for (i in inicio + 1 until fin) p.lineTo(xOf(points[i].x), yOf(points[i].mid))
                    drawPath(p, line, style = Stroke(width = 2f))
                }
                inicio = fin
            }
            if (showDots && points.size <= 400) {
                points.forEach { drawCircle(line, 2.5f, Offset(xOf(it.x), yOf(it.mid))) }
            }

            // El cursor: una cruz que pasa por el dato.
            state.cursorX?.let { cx ->
                nearestIndex(points, cx)?.let { i ->
                    val p = points[i]
                    val x = xOf(p.x); val y = yOf(p.mid)
                    drawLine(cursorColor, Offset(x, r.top), Offset(x, r.bottom), 1.5f)
                    drawLine(cursorColor, Offset(r.left, y), Offset(r.right, y), 1.5f)
                    drawCircle(cursorColor, 5f, Offset(x, y), style = Stroke(2f))
                }
            }
        }
    }
}
