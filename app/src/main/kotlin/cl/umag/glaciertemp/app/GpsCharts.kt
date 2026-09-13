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

/**
 * Cuanto se ha acercado y desplazado el usuario un grafico.
 *
 * [panX] y [panY] estan en FRACCIONES DEL RANGO COMPLETO de datos, no en pixeles ni en
 * unidades de dato. Esa eleccion es la que hace que el arrastre siga al dedo exactamente,
 * este el grafico como este de acercado: con el zoom la ventana visible se estrecha, y un
 * desplazamiento medido en pixeles de pantalla tiene que traducirse a una fraccion MENOR
 * del rango cuanto mas cerca se este. De ahi el `/ zoom` en el gesto.
 */
@Stable
class ChartView {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    fun reset() { zoom = 1f; panX = 0f; panY = 0f }
    val moved: Boolean get() = zoom > 1.01f || panX != 0f || panY != 0f
}

@Composable
fun rememberChartView(): ChartView = remember { ChartView() }

/**
 * Gestos sobre un grafico: dos dedos acercan, uno arrastra.
 *
 * El signo es negativo porque lo que se mueve es la VENTANA sobre los datos, no los datos:
 * arrastrar el dedo a la derecha tiene que traer hacia la derecha lo que se ve, es decir,
 * correr la ventana hacia la izquierda.
 */
private fun Modifier.gestosDeGrafico(v: ChartView): Modifier =
    this.pointerInput(Unit) {
        detectTransformGestures { _, pan, gestureZoom, _ ->
            // El zoom se aplica ANTES de traducir el arrastre, para que un gesto que hace
            // las dos cosas a la vez use la escala con la que el dedo acaba, no la de antes.
            v.zoom = (v.zoom * gestureZoom).coerceIn(1f, 200f)
            v.panX -= pan.x / (size.width * v.zoom)
            v.panY -= pan.y / (size.height * v.zoom)
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

        // El arrastre se convierte a PIXELES aqui, y no a unidades de dato.
        //
        // Es lo unico que da un seguimiento exacto del dedo: el gesto guarda
        // `pan / (ancho * zoom)`, asi que multiplicar por `ancho * zoom` devuelve los mismos
        // pixeles que se arrastraron, sea cual sea el zoom y sea cual sea la escala del
        // grafico. Pasando por unidades de dato hay que dividir por la escala, y la escala
        // depende del lado CORTO del recuadro -- con lo que el contenido se movia mas
        // despacio que el dedo en cuanto el grafico no era cuadrado.
        val dxPx = -vista.panX * size.width * vista.zoom
        val dyPx = -vista.panY * size.height * vista.zoom

        // Donde cae la ESTIMACION en pantalla. Los anillos y la cruz van con ella.
        val ex = margenIzq + ancho / 2f + dxPx
        val ey = alto / 2f + dyPx

        clipRect(left = margenIzq, top = 0f, right = size.width, bottom = alto) {
            // Anillos concentricos CON su distancia escrita. Un anillo sin etiqueta obliga a
            // adivinar la escala, que es justo lo que uno necesita saber aqui.
            val paso = pasoBonito(radio / 3.0)
            var d = paso
            while (d <= radio * 3.0) {
                val rad = (d * escala).toFloat()
                drawCircle(ejes.copy(alpha = 0.30f), rad, Offset(ex, ey), style = Stroke(1f))
                // La etiqueta va en diagonal, donde estorba menos a la nube.
                val k = 0.7071f
                texto(medidor, metros(d), ex + rad * k + 2f, ey - rad * k - 12f,
                      ejes.copy(alpha = 0.9f), 9f)
                d += paso
            }
            drawLine(ejes.copy(alpha = 0.45f), Offset(margenIzq, ey), Offset(size.width, ey), 1f)
            drawLine(ejes.copy(alpha = 0.45f), Offset(ex, 0f), Offset(ex, alto), 1f)

            puntos.forEach { (x, y) ->
                val px = ex + ((x - centroX) * escala).toFloat()
                // El norte hacia ARRIBA: la pantalla crece hacia abajo y el northing hacia el
                // norte, asi que sin el signo el grafico sale reflejado.
                val py = ey - ((y - centroY) * escala).toFloat()
                drawCircle(nube.copy(alpha = 0.55f), 3f, Offset(px, py))
            }

            val b = 9f
            drawLine(marca, Offset(ex - b, ey), Offset(ex + b, ey), 2.5f)
            drawLine(marca, Offset(ex, ey - b), Offset(ex, ey + b), 2.5f)
            drawCircle(marca, b * 0.55f, Offset(ex, ey), style = Stroke(2f))
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
            val px = ex + (v * escala).toFloat()
            if (px > margenIzq + 14 && px < size.width - 14) {
                texto(medidor, metros(v), px, alto + 6f, ejes.copy(alpha = 0.8f), 9f,
                      centrarX = true)
            }
            val py = ey - (v * escala).toFloat()
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

    Canvas(modifier.gestosDeGrafico(vista)) {
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
        val medioBase = max(max(maxA - centro, centro - minA) * 1.15, 1.0)
        // El pellizco acerca en los DOS ejes. En altitud el eje vertical es lo que de verdad
        // se quiere mirar de cerca --la dispersion son decimetros sobre un rango de metros--
        // y dejarlo fijo obligaba a mirar el ruido desde demasiado lejos.
        val medio = medioBase / vista.zoom
        val y0 = centro - medio
        val y1 = centro + medio
        // Igual que en la nube: el arrastre son pixeles, para que siga al dedo exactamente.
        val dxPx = -vista.panX * size.width * vista.zoom
        val dyPx = -vista.panY * size.height * vista.zoom
        fun py(v: Double) = (alto * (1 - (v - y0) / (y1 - y0))).toFloat() + dyPx

        // El eje horizontal es el indice de muestra.
        val n = alturas.size
        val ventana = max(n / vista.zoom, 2f)
        val i0 = (n / 2f) - ventana / 2f
        val i1 = (n / 2f) + ventana / 2f
        fun px(i: Int) = margenIzq + ancho * ((i - i0) / (i1 - i0)) + dxPx

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
            val yLinea = py(centro)
            if (yLinea >= -2 && yLinea <= alto + 2) {
                drawLine(marca, Offset(margenIzq, yLinea), Offset(size.width, yLinea), 2.5f)
            }
        }

        // Etiquetas del eje vertical: la estimacion y una sigma a cada lado, solo si caen
        // dentro. Con el zoom pueden quedarse fuera, y escribirlas pegadas al borde diria
        // que la linea esta ahi cuando no esta.
        val yEst = py(centro)
        if (yEst > 8 && yEst < alto - 8) {
            texto(medidor, "%.1f".format(java.util.Locale.ROOT, centro),
                  margenIzq - 4f - 40f, yEst, marca, 9f, centrarY = true)
        }
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

        // Y del horizontal: primer y ultimo indice visibles, mas el nombre del eje. Se
        // deducen de la transformacion en vez de leerse de i0/i1, que ya no incluyen el
        // arrastre -- escribirlos directamente diria un indice que no es el que se ve.
        val porPixel = (i1 - i0) / ancho
        val visibleIni = (i0 - dxPx * porPixel).roundToInt() + 1
        val visibleFin = (i0 + (i1 - i0) - dxPx * porPixel).roundToInt()
        texto(medidor, "${max(visibleIni, 1)}", margenIzq + 2f, alto + 6f,
              ejes.copy(alpha = 0.8f), 9f)
        texto(medidor, "${min(visibleFin, n)}", size.width - 28f, alto + 6f,
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
