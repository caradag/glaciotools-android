package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.umag.glaciertemp.core.geo.Axis
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cuanto se ha acercado y desplazado el usuario un grafico. */
@Stable
class ChartView {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    fun reset() { zoom = 1f; panX = 0f; panY = 0f }
}

@Composable
fun rememberChartView(): ChartView = remember { ChartView() }

/**
 * Gestos sobre un grafico: dos dedos acercan, uno arrastra.
 *
 * [bloquearY] existe para el grafico de altitud, donde arrastrar en vertical no significa
 * nada util: el eje vertical son metros sobre el elipsoide y moverse por el solo saca la
 * linea de la mediana de la pantalla, que es la referencia contra la que se lee todo.
 */
private fun Modifier.gestosDeGrafico(v: ChartView, bloquearY: Boolean = false): Modifier =
    this.pointerInput(Unit) {
        detectTransformGestures { _, pan, gestureZoom, _ ->
            val nuevo = (v.zoom * gestureZoom).coerceIn(1f, 200f)
            // El desplazamiento se guarda en unidades de DATO y no de pantalla, para que
            // acercarse no lo multiplique: si no, cada pellizco daria un salto lateral.
            v.panX += pan.x / (size.width * v.zoom)
            if (!bloquearY) v.panY += pan.y / (size.height * v.zoom)
            v.zoom = nuevo
        }
    }

private fun DrawScope.texto(
    m: TextMeasurer, s: String, x: Float, y: Float, color: Color, sp: Float = 10f,
    centrarX: Boolean = false, centrarY: Boolean = false,
) {
    val r = m.measure(s, TextStyle(fontSize = sp.sp, color = color))
    drawText(r, topLeft = Offset(
        if (centrarX) x - r.size.width / 2f else x,
        if (centrarY) y - r.size.height / 2f else y))
}

/** Un paso de retícula que sea un numero redondo: 1, 2, 5, 10, 20, 50... */
private fun pasoBonito(bruto: Double): Double {
    if (bruto <= 0 || !bruto.isFinite()) return 1.0
    val pot = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(bruto)))
    val n = bruto / pot
    return pot * when {
        n < 1.5 -> 1.0
        n < 3.5 -> 2.0
        n < 7.5 -> 5.0
        else -> 10.0
    }
}

private fun metros(v: Double): String = when {
    abs(v) >= 100 -> "${v.roundToInt()} m"
    abs(v) >= 10 -> "%.0f m".format(java.util.Locale.ROOT, v)
    abs(v) >= 1 -> "%.1f m".format(java.util.Locale.ROOT, v)
    else -> "%.2f m".format(java.util.Locale.ROOT, v)
}

/**
 * La nube de posiciones, en metros y A ESCALA IGUAL en los dos ejes.
 *
 * Lo de la escala igual no es estetica. Una nube de GNSS tiene forma --suele ser mas larga
 * en una direccion que en otra, segun donde esten los satelites-- y estirar un eje para
 * llenar la pantalla inventa una anisotropia que no existe o esconde la que si. Quien mira
 * esto lo mira para juzgar si la medida es buena; un grafico deformado le hace juzgar mal.
 *
 * El origen es la ESTIMACION, no el primer punto ni el centro del recuadro: asi los numeros
 * de los ejes se leen como "a cuantos metros de la estimacion", que es la unica pregunta que
 * se le hace a este grafico. Los anillos llevan escrita su distancia por el mismo motivo.
 */
@Composable
fun ScatterPlot(
    puntos: List<Pair<Double, Double>>,
    centroX: Double,
    centroY: Double,
    vista: ChartView,
    modifier: Modifier = Modifier,
) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val nube = MaterialTheme.colorScheme.primary
    val marca = MaterialTheme.colorScheme.error
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val medidor = rememberTextMeasurer()

    // Margen para las etiquetas de los ejes: sin el, los numeros del borde quedan cortados.
    val margenIzq = 42f
    val margenAbajo = 22f

    Canvas(modifier.gestosDeGrafico(vista)) {
        drawRect(fondo, size = size)
        if (puntos.isEmpty()) return@Canvas

        val ancho = size.width - margenIzq
        val alto = size.height - margenAbajo
        if (ancho <= 0 || alto <= 0) return@Canvas

        val alcance = puntos.maxOf { max(abs(it.first - centroX), abs(it.second - centroY)) }
        val radioBase = max(alcance * 1.15, 1.0)
        val radio = radioBase / vista.zoom
        val lado = min(ancho, alto)
        val escala = (lado / 2f) / radio.toFloat()          // pixeles por metro, igual en X e Y

        // El centro se desplaza con el arrastre, en unidades de dato.
        val cx = margenIzq + ancho / 2f + vista.panX * ancho
        val cy = alto / 2f + vista.panY * alto

        clipRect(left = margenIzq, top = 0f, right = size.width, bottom = alto) {
            // Anillos concentricos CON su distancia escrita. Un anillo sin etiqueta obliga a
            // adivinar la escala, que es justo lo que uno necesita saber aqui.
            val paso = pasoBonito(radio / 3.0)
            var d = paso
            while (d <= radio * 1.5) {
                val rad = (d * escala).toFloat()
                drawCircle(ejes.copy(alpha = 0.30f), rad, Offset(cx, cy), style = Stroke(1f))
                // La etiqueta va en diagonal, donde estorba menos a la nube.
                val k = 0.7071f
                texto(medidor, metros(d), cx + rad * k + 2f, cy - rad * k - 12f,
                      ejes.copy(alpha = 0.9f), 9f)
                d += paso
            }
            drawLine(ejes.copy(alpha = 0.45f), Offset(margenIzq, cy), Offset(size.width, cy), 1f)
            drawLine(ejes.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, alto), 1f)

            puntos.forEach { (x, y) ->
                val px = cx + ((x - centroX) * escala).toFloat()
                // El norte hacia ARRIBA: la pantalla crece hacia abajo y el northing hacia el
                // norte, asi que sin el signo el grafico sale reflejado.
                val py = cy - ((y - centroY) * escala).toFloat()
                drawCircle(nube.copy(alpha = 0.55f), 3f, Offset(px, py))
            }

            val b = 9f
            drawLine(marca, Offset(cx - b, cy), Offset(cx + b, cy), 2.5f)
            drawLine(marca, Offset(cx, cy - b), Offset(cx, cy + b), 2.5f)
            drawCircle(marca, b * 0.55f, Offset(cx, cy), style = Stroke(2f))
        }

        // Los nombres de los ejes, fuera del area recortada.
        texto(medidor, "East (m)", margenIzq + ancho / 2f, alto + 6f, ejes, 10f,
              centrarX = true)
        rotate(-90f, Offset(12f, alto / 2f)) {
            texto(medidor, "North (m)", 12f, alto / 2f, ejes, 10f,
                  centrarX = true, centrarY = true)
        }
        // Y las cifras de los ejes, referidas a la estimacion.
        val pasoEje = pasoBonito(radio / 2.0)
        listOf(-pasoEje, pasoEje).forEach { v ->
            val px = cx + (v * escala).toFloat()
            if (px > margenIzq + 14 && px < size.width - 14) {
                texto(medidor, metros(v), px, alto + 6f, ejes.copy(alpha = 0.8f), 9f,
                      centrarX = true)
            }
            val py = cy - (v * escala).toFloat()
            if (py > 10 && py < alto - 10) {
                texto(medidor, metros(v), margenIzq - 4f - 30f, py, ejes.copy(alpha = 0.8f),
                      9f, centrarY = true)
            }
        }
    }
}

/**
 * La altitud contra el NUMERO DE MUESTRA, con la estimacion y la banda de una sigma.
 *
 * Contra el numero de muestra y no contra el tiempo: un punto medido en dos visitas
 * separadas por una semana dejaria, en un eje temporal, un desierto vacio con dos rayas
 * pegadas a los extremos. Lo que se quiere ver es la secuencia de lecturas, y para eso el
 * indice reparte el espacio entre lo que hay dato.
 *
 * La altitud va aparte de la nube porque su error no se parece al horizontal: un GNSS la
 * estima peor, por un factor de dos o tres, y un eje comun aplastaria la parte horizontal.
 */
@Composable
fun AltitudePlot(
    alturas: List<Double>,
    resumen: Axis?,
    vista: ChartView,
    modifier: Modifier = Modifier,
) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val linea = MaterialTheme.colorScheme.primary
    val marca = MaterialTheme.colorScheme.error
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val banda = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    val medidor = rememberTextMeasurer()

    val margenIzq = 46f
    val margenAbajo = 22f

    Canvas(modifier.gestosDeGrafico(vista, bloquearY = true)) {
        drawRect(fondo, size = size)
        if (alturas.isEmpty() || resumen == null) return@Canvas
        val ancho = size.width - margenIzq
        val alto = size.height - margenAbajo
        if (ancho <= 0 || alto <= 0) return@Canvas

        val centro = resumen.estimate
        val minA = alturas.min()
        val maxA = alturas.max()
        // Al menos un metro de ventana: si todas las lecturas fueran iguales, un rango cero
        // daria una division por cero y un grafico donde el ruido de un centimetro parece
        // una montana.
        val medio = max(max(maxA - centro, centro - minA) * 1.15, 1.0)
        val y0 = centro - medio
        val y1 = centro + medio
        fun py(v: Double) = (alto * (1 - (v - y0) / (y1 - y0))).toFloat()

        // El eje horizontal es el indice, y el zoom y el arrastre se aplican sobre el.
        val n = alturas.size
        val ventana = max(n / vista.zoom, 2f)
        val centroIdx = (n / 2f) - vista.panX * n
        val i0 = (centroIdx - ventana / 2f)
        val i1 = (centroIdx + ventana / 2f)
        fun px(i: Int) = margenIzq + ancho * ((i - i0) / (i1 - i0))

        clipRect(left = margenIzq, top = 0f, right = size.width, bottom = alto) {
            if (resumen.sd > 0) {
                val arriba = py(centro + resumen.sd)
                val abajo = py(centro - resumen.sd)
                drawRect(banda, Offset(margenIzq, arriba),
                         androidx.compose.ui.geometry.Size(ancho, abajo - arriba))
            }
            alturas.forEachIndexed { i, a ->
                val x = px(i)
                if (x >= margenIzq - 4 && x <= size.width + 4) {
                    drawCircle(linea.copy(alpha = 0.55f), 2.5f, Offset(x, py(a)))
                }
            }
            drawLine(marca, Offset(margenIzq, py(centro)), Offset(size.width, py(centro)), 2.5f)
        }

        // Etiquetas del eje vertical: la estimacion y una sigma a cada lado.
        texto(medidor, "%.1f".format(java.util.Locale.ROOT, centro),
              margenIzq - 4f - 40f, py(centro), marca, 9f, centrarY = true)
        if (resumen.sd > 0) {
            listOf(centro + resumen.sd, centro - resumen.sd).forEach { v ->
                val y = py(v)
                if (y > 8 && y < alto - 8) {
                    texto(medidor, "%.1f".format(java.util.Locale.ROOT, v),
                          margenIzq - 4f - 40f, y, ejes.copy(alpha = 0.8f), 9f, centrarY = true)
                }
            }
        }
        rotate(-90f, Offset(10f, alto / 2f)) {
            texto(medidor, "Altitude (m)", 10f, alto / 2f, ejes, 10f,
                  centrarX = true, centrarY = true)
        }

        // Y del horizontal: primer y ultimo indice visibles, mas el nombre del eje.
        texto(medidor, "${max(i0.roundToInt() + 1, 1)}", margenIzq + 2f, alto + 6f, 
              ejes.copy(alpha = 0.8f), 9f)
        texto(medidor, "${min(i1.roundToInt(), n)}", size.width - 24f, alto + 6f,
              ejes.copy(alpha = 0.8f), 9f)
        texto(medidor, "Sample number", margenIzq + ancho / 2f, alto + 6f, ejes, 10f,
              centrarX = true)
    }
}

/** Un numero grande con su nombre encima. Se lee de lejos, con el aparato en la mano. */
@Composable
fun BigReading(
    nombre: String,
    valor: String,
    unidad: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(nombre, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(valor, style = MaterialTheme.typography.headlineSmall,
                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            if (unidad.isNotEmpty()) {
                Text(" $unidad", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
