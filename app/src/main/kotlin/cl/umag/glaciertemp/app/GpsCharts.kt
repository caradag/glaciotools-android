package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.geo.Dispersion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * La nube de posiciones, en metros y A ESCALA IGUAL en los dos ejes.
 *
 * Lo de la escala igual no es estetica. Una nube de GPS tiene forma --suele ser mas larga en
 * una direccion que en otra, segun donde esten los satelites-- y estirar un eje para llenar
 * la pantalla inventa una anisotropia que no existe o esconde la que si. Quien mira esto lo
 * mira para juzgar si la medida es buena; un grafico deformado le hace juzgar mal.
 *
 * El origen es la MEDIANA, no el primer punto ni el centro del recuadro: asi los numeros de
 * los ejes se leen directamente como "a cuantos metros de la estimacion", que es la unica
 * pregunta que se le hace a este grafico.
 */
@Composable
fun ScatterPlot(
    puntos: List<Pair<Double, Double>>,
    medianaX: Double,
    medianaY: Double,
    modifier: Modifier = Modifier,
) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val nube = MaterialTheme.colorScheme.primary
    val marca = MaterialTheme.colorScheme.error
    val fondo = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier) {
        val r = Rect(0f, 0f, size.width, size.height)
        drawRect(fondo, size = size)
        if (puntos.isEmpty()) return@Canvas

        // Radio en metros: lo que haga falta para que quepa el punto mas alejado, con un
        // minimo para que dos lecturas identicas no den una division por cero ni un grafico
        // con un zoom absurdo.
        val alcance = puntos.maxOf {
            max(abs(it.first - medianaX), abs(it.second - medianaY))
        }
        val radio = max(alcance * 1.15, 1.0)
        val lado = min(r.width, r.height)
        val escala = (lado / 2f) / radio.toFloat()          // pixeles por metro, igual en X e Y
        val cx = r.width / 2f
        val cy = r.height / 2f

        // Retícula cada metro redondo, para poder contar metros a ojo.
        val paso = pasoBonito(radio)
        var d = paso
        while (d <= radio) {
            val rad = (d * escala).toFloat()
            drawCircle(ejes.copy(alpha = 0.25f), rad, Offset(cx, cy), style = Stroke(1f))
            d += paso
        }
        drawLine(ejes.copy(alpha = 0.4f), Offset(0f, cy), Offset(r.width, cy), 1f)
        drawLine(ejes.copy(alpha = 0.4f), Offset(cx, 0f), Offset(cx, r.height), 1f)

        puntos.forEach { (x, y) ->
            val px = cx + ((x - medianaX) * escala).toFloat()
            // El norte hacia ARRIBA: la pantalla crece hacia abajo y el northing hacia el
            // norte, asi que sin el signo el grafico sale reflejado y la nube parece otra.
            val py = cy - ((y - medianaY) * escala).toFloat()
            drawCircle(nube.copy(alpha = 0.55f), 3f, Offset(px, py))
        }

        // La mediana, con una marca que no se confunde con una muestra mas.
        val b = 9f
        drawLine(marca, Offset(cx - b, cy), Offset(cx + b, cy), 2.5f)
        drawLine(marca, Offset(cx, cy - b), Offset(cx, cy + b), 2.5f)
        drawCircle(marca, b * 0.55f, Offset(cx, cy), style = Stroke(2f))
    }
}

/**
 * La altitud contra el tiempo, con la mediana y la banda de una desviacion tipica.
 *
 * La altitud va aparte de la nube porque su error no se parece al horizontal: un GNSS la
 * estima peor, por un factor de dos o tres, y mezclarla en el mismo grafico obligaria a un
 * eje comun en el que la parte horizontal quedaria aplastada.
 */
@Composable
fun AltitudePlot(
    tiempos: List<Long>,
    alturas: List<Double>,
    resumen: Dispersion?,
    modifier: Modifier = Modifier,
) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val linea = MaterialTheme.colorScheme.primary
    val marca = MaterialTheme.colorScheme.error
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val banda = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)

    Canvas(modifier) {
        drawRect(fondo, size = size)
        if (alturas.isEmpty() || resumen == null) return@Canvas

        val minA = alturas.min()
        val maxA = alturas.max()
        // Al menos dos metros de ventana: si todas las lecturas son iguales, un rango cero
        // daria una division por cero y, peor, un grafico donde el ruido de un centimetro
        // parece una montana.
        val centro = resumen.median
        val medio = max(max(maxA - centro, centro - minA) * 1.15, 1.0)
        val y0 = centro - medio
        val y1 = centro + medio

        fun py(v: Double) = (size.height * (1 - (v - y0) / (y1 - y0))).toFloat()

        val t0 = tiempos.min()
        val t1 = tiempos.max()
        fun px(t: Long) =
            if (t1 == t0) size.width / 2f
            else (size.width * (t - t0).toDouble() / (t1 - t0)).toFloat()

        // La banda de una sigma, que es donde caen dos de cada tres lecturas.
        if (resumen.sd > 0) {
            val arriba = py(centro + resumen.sd)
            val abajo = py(centro - resumen.sd)
            drawRect(banda, Offset(0f, arriba),
                     androidx.compose.ui.geometry.Size(size.width, abajo - arriba))
        }
        drawLine(ejes.copy(alpha = 0.4f), Offset(0f, size.height / 2), 
                 Offset(size.width, size.height / 2), 1f)

        alturas.forEachIndexed { i, a ->
            drawCircle(linea.copy(alpha = 0.55f), 2.5f, Offset(px(tiempos[i]), py(a)))
        }
        // La mediana, de lado a lado: es la referencia contra la que se lee todo lo demas.
        drawLine(marca, Offset(0f, py(centro)), Offset(size.width, py(centro)), 2.5f)
    }
}

/** Un paso de retícula que sea un numero redondo: 1, 2, 5, 10, 20, 50... */
private fun pasoBonito(radio: Double): Double {
    val bruto = radio / 3.0
    val pot = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(bruto)))
    val n = bruto / pot
    return pot * when {
        n < 1.5 -> 1.0
        n < 3.5 -> 2.0
        n < 7.5 -> 5.0
        else -> 10.0
    }
}

/** Una cifra con su nombre encima, del tamano del titulo. Se lee de lejos. */
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
