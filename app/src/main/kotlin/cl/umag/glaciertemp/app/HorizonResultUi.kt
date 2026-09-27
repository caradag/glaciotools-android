package cl.umag.glaciertemp.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.sensors.HorizonProfile
import cl.umag.glaciertemp.core.sensors.Solar
import cl.umag.glaciertemp.core.sensors.SolarPanel
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Las tres curvas del sol que se dibujan sobre el horizonte. */
private data class Camino(val etiqueta: String, val color: Color, val puntos: List<Solar.Position>)

/**
 * El horizonte medido, con el sol encima y lo que de ahi se deduce.
 *
 * EL GRAFICO ES EL RESULTADO, no un adorno. Un numero de cielo visible no dice a que hora
 * asoma el sol por encima del cerro; la curva del solsticio de invierno cruzando el perfil
 * medido lo dice de un vistazo, y ademas se puede comprobar mirando por la ventana.
 */
@Composable
fun HorizonResultScreen(
    profile: HorizonProfile,
    sectores: Int,
    muestras: Int,
    latitude: Double?,
    longitude: Double?,
    onRedo: () -> Unit,
    onBack: () -> Unit,
) {
    val zona = ZoneId.systemDefault()
    val hoy = remember { LocalDate.now(zona) }

    val caminos = remember(latitude, longitude, hoy) {
        if (latitude == null || longitude == null) emptyList()
        else {
            val (invierno, verano) = Solar.solstices(hoy.year, latitude)
            listOf(
                Camino("Today", Color(0xFFFF9800),
                       Solar.dayTrack(latitude, longitude, hoy, zona, 5)),
                Camino(invierno.label, Color(0xFF3F51B5),
                       Solar.dayTrack(latitude, longitude, invierno.date, zona, 5)),
                Camino(verano.label, Color(0xFFE91E63),
                       Solar.dayTrack(latitude, longitude, verano.date, zona, 5)),
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        GraficoDeHorizonte(profile, caminos,
                           Modifier.fillMaxWidth().height(260.dp).testTag("hz-chart"))

        if (caminos.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                caminos.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(12.dp)) { drawCircle(c.color) }
                        Spacer(Modifier.width(4.dp))
                        Text(c.etiqueta, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        HorizontalDivider()

        Cifra("Sky visible", "%.1f %%".format(profile.skyFraction() * 100), "hz-sky")
        Text("Fraction of the sky dome that is not behind terrain. " +
             "The highest obstruction is %.0f° at %.0f° (%s).".format(
                 profile.maxElevation(), profile.highestAzimuth(),
                 cl.umag.glaciertemp.core.sensors.Compass.cardinal(profile.highestAzimuth())),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Measured $sectores of ${360 / profile.binDeg} sectors, $muestras samples." +
             if (sectores < 360 / profile.binDeg)
                 "  The gaps were filled by interpolating between neighbours."
             else "",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("hz-coverage"))

        if (latitude == null || longitude == null) {
            HorizontalDivider()
            Text("No position, so the sun paths and the panel figures cannot be computed. " +
                 "They depend on the latitude: the same horizon means very different things " +
                 "at 53° south and at the equator.",
                 style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("hz-nopos"))
        } else {
            val (invierno, _) = remember(hoy, latitude) { Solar.solstices(hoy.year, latitude) }
            HorizontalDivider()
            Text("Solar panel", style = MaterialTheme.typography.titleMedium)
            PanelPara("Today", Solar.dayTrack(latitude, longitude, hoy, zona, 5), profile, "hoy")
            PanelPara(invierno.label,
                      Solar.dayTrack(latitude, longitude, invierno.date, zona, 5),
                      profile, "inv")
            Text("Clear-sky direct beam only, integrated over the day with an air-mass " +
                 "correction. No diffuse light, no clouds, no snow reflection. Good for " +
                 "choosing between two orientations at this spot; not an energy forecast.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRedo, modifier = Modifier.testTag("hz-redo")) { Text("Measure again") }
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("hz-back")) { Text("Done") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PanelPara(titulo: String, track: List<Solar.Position>,
                      profile: HorizonProfile, tag: String) {
    val mejor = remember(track, profile) { SolarPanel.best(track, profile) }
    val horas = remember(track, profile) { SolarPanel.sunHours(track, profile, 5) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall)
            if (mejor == null) {
                Text("The sun does not clear the horizon on this day.",
                     style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.testTag("hz-panel-$tag"))
            } else {
                Text("Face %d° (%s), tilt %d° from horizontal".format(
                         mejor.azimuthDeg,
                         cl.umag.glaciertemp.core.sensors.Compass.cardinal(mejor.azimuthDeg.toDouble()),
                         mejor.tiltDeg),
                     style = MaterialTheme.typography.bodyMedium,
                     fontFamily = FontFamily.Monospace,
                     modifier = Modifier.testTag("hz-panel-$tag"))
                Text("%.1f h of direct sun, %.1f h lost behind terrain".format(
                         horas.first, horas.second),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                val perdida = mejor.shadingLoss() * 100
                Text("Terrain costs %s of what an open site would get here.".format(
                         if (perdida < 10.0) "%.1f %%".format(perdida)
                         else "%.0f %%".format(perdida)),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Cifra(rotulo: String, valor: String, tag: String) {
    Column {
        Text(rotulo, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valor, style = MaterialTheme.typography.headlineMedium,
             fontFamily = FontFamily.Monospace,
             modifier = Modifier.testTag(tag))
    }
}

/**
 * Elevacion contra azimut, con el horizonte relleno y el sol por encima.
 *
 * EL HORIZONTE VA RELLENO y no como una linea: lo de abajo es roca y lo de arriba es cielo,
 * y con dos lineas sueltas hay que pararse a pensar cual es cual. Asi, donde la curva del sol
 * entra en la zona rellena es donde el sol esta detras del cerro, y se ve sin leer nada.
 */
@Composable
private fun GraficoDeHorizonte(profile: HorizonProfile, caminos: List<Camino>,
                               modifier: Modifier = Modifier) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val relleno = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val borde = MaterialTheme.colorScheme.onSurface
    val fondo = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    val medidor = androidx.compose.ui.text.rememberTextMeasurer()
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    val estiloEje = MaterialTheme.typography.labelSmall.copy(color = ejes)
    val estiloCardinal = MaterialTheme.typography.labelMedium.copy(
        color = ejes, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

    // El eje vertical llega a donde haga falta: con un cerro a 60 grados, fijarlo en 30
    // cortaria justo lo que se ha ido a medir.
    val maxEl = maxOf(profile.maxElevation(),
                      caminos.flatMap { it.puntos }.maxOfOrNull { it.elevation } ?: 0.0,
                      10.0).let { (it / 10).toInt() * 10 + 10 }.coerceAtMost(90)
    val minEl = minOf(profile.minElevation(), 0.0).let { (it / 10).toInt() * 10 - 10 }
        .coerceAtLeast(-90)

    Canvas(modifier) {
        val izq = 42f * densidad
        val abajo = 26f * densidad
        val arriba = 8f * densidad
        val w = size.width - izq
        val h = size.height - abajo - arriba

        fun x(az: Double) = izq + (az / 360.0).toFloat() * w
        fun y(el: Double) = arriba + ((maxEl - el) / (maxEl - minEl)).toFloat() * h

        drawRect(fondo, Offset(izq, arriba), androidx.compose.ui.geometry.Size(w, h))

        // Rejilla de elevacion cada 15 grados.
        var e = ((minEl / 15) * 15)
        while (e <= maxEl) {
            val yy = y(e.toDouble())
            drawLine(ejes.copy(alpha = if (e == 0) 0.7f else 0.25f),
                     Offset(izq, yy), Offset(size.width, yy), if (e == 0) 2f else 1f)
            val r = medidor.measure("$e°", estiloEje)
            drawText(r, topLeft = Offset(2f * densidad, yy - r.size.height / 2f))
            e += 15
        }

        // Los cuatro puntos cardinales, que es lo que ancla el grafico a la realidad.
        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W", 360 to "N").forEach { (az, n) ->
            val xx = x(az.toDouble())
            drawLine(ejes.copy(alpha = 0.5f), Offset(xx, arriba), Offset(xx, arriba + h), 1.5f)
            val r = medidor.measure(n, estiloCardinal)
            drawText(r, topLeft = Offset(xx - r.size.width / 2f,
                                         size.height - r.size.height.toFloat()))
        }

        // El horizonte, relleno hacia abajo.
        val p = Path()
        p.moveTo(x(0.0), y(profile.elevationAt(0.0)))
        var az = 0.0
        while (az <= 360.0) {
            p.lineTo(x(az), y(profile.elevationAt(az.coerceAtMost(359.9))))
            az += profile.binDeg.toDouble()
        }
        val relleno2 = Path().apply {
            addPath(p)
            lineTo(x(360.0), arriba + h)
            lineTo(x(0.0), arriba + h)
            close()
        }
        drawPath(relleno2, relleno)
        drawPath(p, borde, style = Stroke(width = 3f))

        // El sol. Se parte la curva cuando salta de 360 a 0, o cruzaria el grafico entero.
        caminos.forEach { c ->
            val visibles = c.puntos.filter { it.elevation > minEl }
            var anterior: Solar.Position? = null
            visibles.forEach { q ->
                val a = anterior
                if (a != null && kotlin.math.abs(q.azimuth - a.azimuth) < 180.0) {
                    drawLine(c.color, Offset(x(a.azimuth), y(a.elevation)),
                             Offset(x(q.azimuth), y(q.elevation)), 3f)
                }
                anterior = q
            }
        }
    }
}
