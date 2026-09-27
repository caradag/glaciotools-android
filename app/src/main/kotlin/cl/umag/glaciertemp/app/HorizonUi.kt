package cl.umag.glaciertemp.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cl.umag.glaciertemp.core.sensors.HorizonFeedback
import cl.umag.glaciertemp.core.sensors.HorizonBins
import cl.umag.glaciertemp.core.sensors.HorizonProfile
import cl.umag.glaciertemp.core.sensors.ViewDirection
import kotlin.math.abs

/**
 * El mapeador de horizonte: girar sobre uno mismo apuntando la cruz al horizonte.
 *
 * DE QUE SIRVE. En un valle de montana, lo que decide si un registrador con panel solar va a
 * sobrevivir el invierno no es la latitud sino a que hora asoma el sol por encima del cerro
 * de enfrente. Eso no esta en ningun mapa con la resolucion que hace falta, y medirlo con
 * brujula y clinometro punto a punto lleva media hora. Aqui se hace dando una vuelta sobre
 * uno mismo.
 *
 * POR QUE LA CAMARA. Sin imagen habria que apuntar el borde del telefono a ojo, que es justo
 * lo que hace que dos personas midan dos horizontes distintos. Con la imagen y una cruz, lo
 * que se apunta es lo que se ve.
 *
 * SE USA LA CAMARA TRASERA. La frontal mira a quien sostiene el telefono, asi que con ella la
 * tarea seria imposible: se estaria apuntando al horizonte de detras.
 */
@Composable
fun HorizonMapperScreen(onDone: (HorizonProfile, Int, Int) -> Unit, onCancel: () -> Unit,
                        location: LocationSource? = null) {
    val ctx = LocalContext.current
    var permiso by remember { mutableStateOf(tienePermisoCamara(ctx)) }
    val pedir = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { permiso = it }
    LaunchedEffect(Unit) { if (!permiso) pedir.launch(Manifest.permission.CAMERA) }

    if (!permiso) {
        Column(Modifier.fillMaxSize().padding(24.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The horizon mapper needs the camera.",
                 style = MaterialTheme.typography.titleMedium)
            Text("It shows the live image so you can aim the crosshair at the skyline. " +
                 "Nothing is recorded or stored: only the angles are kept.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { pedir.launch(Manifest.permission.CAMERA) },
                   modifier = Modifier.testTag("hz-permission")) { Text("Allow") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
        return
    }

    // AZIMUT VERDADERO, NO MAGNETICO. Se corrige aqui, en la entrada, para que el grafico,
    // el recorrido del sol, el panel y lo que se copia hablen todos del mismo norte. Sin
    // posicion no hay declinacion y se dice: presentar lo magnetico como verdadero seria el
    // error que esto viene a evitar.
    val declinacion = rememberDeclination(location)
    val declinacionRef = rememberUpdatedState(declinacion)

    // AVISO DE CALIBRACION AL ENTRAR. Un barrido entero con la brujula mal calibrada sale
    // torcido de una forma que el factor de apantallamiento NO perdona --no es una rotacion
    // rigida, es una deformacion-- y hasta ahora nada lo decia.
    val precision = rememberCompassAccuracy()
    var avisoCalibracion by remember { mutableStateOf(false) }
    var yaAvisado by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(precision) {
        if (!yaAvisado && precision.isPoor()) { avisoCalibracion = true; yaAvisado = true }
    }

    val bins = remember { HorizonBins() }

    var azimut by remember { mutableStateOf<Double?>(null) }
    var elevacion by remember { mutableStateOf(0.0) }
    var ladeo by remember { mutableStateOf(0.0) }
    var cubiertos by remember { mutableIntStateOf(0) }
    var muestras by remember { mutableIntStateOf(0) }
    var referencia by remember { mutableStateOf<Double?>(null) }
    var deprisa by remember { mutableStateOf(false) }
    var hz by remember { mutableDoubleStateOf(0.0) }

    // NO SE REGISTRA HASTA QUE SE DICE. Antes acumulaba desde que se abria la pantalla, asi
    // que los primeros sectores se llenaban con el telefono todavia en la mano, a la altura
    // del pecho y apuntando a cualquier sitio. Los numeros y la cruz se ven igual antes de
    // empezar, que es lo que permite encuadrar y comprobar que el telefono esta derecho.
    var registrando by rememberSaveable { mutableStateOf(false) }
    var huecos by remember { mutableStateOf(false) }
    val registrandoRef = rememberUpdatedState(registrando)

    // EL MUESTREO VIVE EN EL OYENTE DEL SENSOR, no en un LaunchedEffect.
    //
    // Estaba en un LaunchedEffect que dependia del vector de rotacion, o sea que se ejecutaba
    // al RECOMPONER. Compose agrupa los cambios de estado al ritmo de los fotogramas, y con
    // la vista de camara encima ese ritmo baja: entre dos fotogramas el azimut podia saltar
    // mas de cinco grados y un sector entero se quedaba SIN UNA SOLA MUESTRA. En la segunda
    // vuelta esos sectores no tenian con que comparar y no salia flecha, que es justo lo que
    // se vio en terreno. Aqui el oyente corre a la frecuencia del sensor, decenas de veces
    // por segundo, independiente de lo que tarde en dibujarse la camara.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }

        val R = FloatArray(9)
        var sectorActual = -1
        var azAnterior = Double.NaN
        var tAnterior = 0L
        var velocidad = 0.0
        var contadas = 0
        var desde = 0L

        val oyente = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(R, e.values)
                val (azMag, el) = ViewDirection.of(R)
                val az = cl.umag.glaciertemp.core.sensors.Compass.normalize(
                    azMag + (declinacionRef.value ?: 0.0))
                azimut = az
                elevacion = el
                ladeo = ViewDirection.roll(R)

                val ahora = android.os.SystemClock.elapsedRealtime()
                // Frecuencia real de muestreo, medida y no supuesta: es la mitad de la cuenta
                // que decide si se esta girando demasiado rapido.
                contadas++
                if (desde == 0L) desde = ahora
                else if (ahora - desde >= 1000) {
                    hz = contadas * 1000.0 / (ahora - desde); contadas = 0; desde = ahora
                }

                if (!azAnterior.isNaN() && tAnterior > 0 && ahora > tAnterior) {
                    val giro = abs(cl.umag.glaciertemp.core.sensors.Angles.wrap(az - azAnterior))
                    // Suavizado: un solo salto de ruido no debe encender el aviso.
                    velocidad = 0.7 * velocidad + 0.3 * (giro * 1000.0 / (ahora - tAnterior))
                }
                azAnterior = az
                tAnterior = ahora

                if (!registrandoRef.value) return

                deprisa = HorizonFeedback.tooFast(velocidad, bins.binDeg,
                                                  if (hz > 0) hz else 50.0)

                val b = bins.binOf(az)
                if (b != sectorActual) {
                    // La referencia se congela AL ENTRAR en el sector: es la media de las
                    // pasadas anteriores, antes de que esta la mueva.
                    sectorActual = b
                    referencia = bins.mean(b)
                }
                bins.add(az, el)
                cubiertos = bins.covered()
                muestras++
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(oyente, sensor,
                            android.hardware.SensorManager.SENSOR_DELAY_FASTEST)
        onDispose { sm.unregisterListener(oyente) }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VistaDeCamara(Modifier.fillMaxSize())
            Cruz(diferencia = if (registrando) referencia?.let { it - elevacion } else null,
                 modifier = Modifier.fillMaxSize())
            Lectura(azimut, elevacion, ladeo, cubiertos, bins.total(), registrando,
                    declinacion, precision,
                    Modifier.align(Alignment.TopStart).padding(12.dp))
        }

        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (registrando) {
                LinearProgressIndicator(progress = { cubiertos.toFloat() / bins.total() },
                                        modifier = Modifier.fillMaxWidth())
            }
            val problema = deprisa || abs(ladeo) > 10
            Text(when {
                     deprisa -> "Slower — at this speed whole sectors get no samples at all."
                     abs(ladeo) > 10 ->
                         "Hold the phone upright — it is tilted ${"%.0f".format(abs(ladeo))}°"
                     !registrando ->
                         "Aim the crosshair at the skyline, then press Start. " +
                         "Nothing is recorded until you do."
                     else -> "Turn slowly, keeping the crosshair on the skyline."
                 },
                 style = MaterialTheme.typography.bodySmall,
                 color = if (problema) MaterialTheme.colorScheme.error
                         else MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("hz-hint"))
            if (precision.isPoor()) {
                TextButton(onClick = { avisoCalibracion = true },
                           modifier = Modifier.testTag("hz-calib")) {
                    Text("Compass accuracy is ${precision.label} — how to improve it")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!registrando) {
                    Button(onClick = { registrando = true },
                           modifier = Modifier.weight(1f).height(56.dp).testTag("hz-start")) {
                        Text("Start", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(onClick = {
                               // UN PERFIL INCOMPLETO NO SE ENTREGA. Un sector sin medir no
                               // es un sector a cero grados, y rellenarlo interpolando daria
                               // un grafico de aspecto impecable con un trozo inventado
                               // dentro que nadie podria distinguir despues. Se dice que
                               // falta y donde, para ir a rellenarlo.
                               if (cubiertos < bins.total()) huecos = true
                               else bins.profile()?.let { onDone(it, bins.covered(), muestras) }
                           },
                           enabled = cubiertos > 0,
                           modifier = Modifier.weight(1f).height(56.dp).testTag("hz-finish")) {
                        Text("Finish", style = MaterialTheme.typography.titleMedium)
                    }
                }
                OutlinedButton(onClick = onCancel,
                               modifier = Modifier.height(56.dp).testTag("hz-cancel")) {
                    Text("Cancel")
                }
            }
        }
    }

    if (avisoCalibracion) DialogoDeCalibracion(precision) { avisoCalibracion = false }

    if (huecos) {
        val faltan = BooleanArray(bins.total()) { bins.samples(it) > 0 }
        val tramos = HorizonFeedback.gaps(faltan, bins.binDeg)
        AlertDialog(
            onDismissRequest = { huecos = false },
            modifier = Modifier.testTag("hz-gaps-dialog"),
            title = { Text("Incomplete profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${bins.total() - cubiertos} of ${bins.total()} sectors were never " +
                         "measured, so there is no horizon to draw. A sector with no data is " +
                         "not a sector at zero degrees.")
                    Text("Missing: ${HorizonFeedback.describeGaps(tramos)}",
                         style = MaterialTheme.typography.bodyMedium,
                         fontFamily = FontFamily.Monospace,
                         modifier = Modifier.testTag("hz-gaps-list"))
                    Text("Turn back over those bearings, more slowly. What you have already " +
                         "measured is kept.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { huecos = false },
                           modifier = Modifier.testTag("hz-gaps-continue")) {
                    Text("Keep measuring")
                }
            },
            dismissButton = {
                TextButton(onClick = { huecos = false; onCancel() }) { Text("Discard") }
            })
    }
}

private fun tienePermisoCamara(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** La imagen en vivo de la camara trasera. Solo se mira: no se guarda ni se graba nada. */
@Composable
private fun VistaDeCamara(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dueno = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier.testTag("hz-preview"),
        factory = { c ->
            val vista = PreviewView(c).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val futuro = ProcessCameraProvider.getInstance(c)
            futuro.addListener({
                runCatching {
                    val proveedor = futuro.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(vista.surfaceProvider) }
                    proveedor.unbindAll()
                    proveedor.bindToLifecycle(dueno, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }
            }, ContextCompat.getMainExecutor(c))
            vista
        })
}

/**
 * La cruz, y la flecha que dice cuanto se aparta de lo ya medido en este sector.
 *
 * LA FLECHA APUNTA A DONDE ESTABA EL HORIZONTE, no a donde hay que mover el telefono, y esas
 * dos cosas son la misma: si en la pasada anterior el horizonte quedo mas arriba, la flecha
 * sube y hay que subir. El numero al lado es la diferencia en grados, que es lo que permite
 * distinguir "me desvie un poco" de "esto es otro cerro".
 */
@Composable
private fun Cruz(diferencia: Double?, modifier: Modifier = Modifier) {
    val blanco = Color.White
    val sombra = Color.Black.copy(alpha = 0.55f)
    // COLOR POR BANDA Y LARGO PROPORCIONAL. Girando con el telefono a un brazo, lo que se
    // distingue de un vistazo es el color; el largo afina dentro de la banda, para que 6
    // grados y 25 no se vean iguales aunque los dos sean rojos.
    val color = when (diferencia?.let { HorizonFeedback.band(it) }) {
        HorizonFeedback.Band.FINE -> Color(0xFF4CAF50)
        HorizonFeedback.Band.OFF -> Color(0xFFFF9800)
        HorizonFeedback.Band.WAY_OFF -> Color(0xFFF44336)
        null -> Color.Transparent
    }
    androidx.compose.foundation.layout.Box(modifier) {
        Canvas(Modifier.fillMaxSize().testTag("hz-cross")) {
            val c = Offset(size.width / 2, size.height / 2)
            val brazo = size.minDimension * 0.16f
            // Doble trazo: una cruz blanca sola desaparece sobre nieve, que es el fondo que
            // va a tener la mitad de las veces.
            fun cruz(color: Color, grosor: Float) {
                drawLine(color, Offset(c.x - brazo, c.y), Offset(c.x - brazo * 0.2f, c.y), grosor)
                drawLine(color, Offset(c.x + brazo * 0.2f, c.y), Offset(c.x + brazo, c.y), grosor)
                drawLine(color, Offset(c.x, c.y - brazo), Offset(c.x, c.y - brazo * 0.2f), grosor)
                drawLine(color, Offset(c.x, c.y + brazo * 0.2f), Offset(c.x, c.y + brazo), grosor)
                drawCircle(color, radius = brazo * 0.12f, center = c, style = Stroke(grosor))
            }
            cruz(sombra, 9f)
            cruz(blanco, 4f)

            // La linea del horizonte de la cruz, para ver si el telefono esta derecho.
            drawLine(sombra, Offset(0f, c.y), Offset(size.width, c.y), 3f)
            drawLine(blanco.copy(alpha = 0.35f), Offset(0f, c.y), Offset(size.width, c.y), 1.5f)

            val d = diferencia ?: return@Canvas
            if (abs(d) < 0.3) return@Canvas
            // Positivo: el horizonte medido estaba MAS ARRIBA que donde se apunta ahora.
            flecha(c, arriba = d > 0,
                   largo = brazo * HorizonFeedback.lengthFactor(d).toFloat(),
                   color = color)
        }
        diferencia?.takeIf { abs(it) >= 0.3 }?.let { d ->
            Text("%+.1f°".format(d),
                 color = color,
                 fontWeight = FontWeight.Bold,
                 fontFamily = FontFamily.Monospace,
                 fontSize = 22.sp,
                 modifier = Modifier.align(Alignment.Center)
                     .offset(x = 62.dp,
                             y = ((if (d > 0) -1.0 else 1.0) *
                                  (24.0 + 26.0 * HorizonFeedback.lengthFactor(d))).dp)
                     .testTag("hz-delta"))
        }
    }
}

private fun DrawScope.flecha(centro: Offset, arriba: Boolean, largo: Float, color: Color) {
    val signo = if (arriba) -1f else 1f
    val punta = Offset(centro.x, centro.y + signo * largo)
    drawLine(Color.Black.copy(alpha = 0.55f), centro, punta, 11f)
    drawLine(color, centro, punta, 6f)
    val ala = largo * 0.28f
    val p = Path().apply {
        moveTo(punta.x, punta.y)
        lineTo(punta.x - ala * 0.6f, punta.y - signo * ala)
        lineTo(punta.x + ala * 0.6f, punta.y - signo * ala)
        close()
    }
    drawPath(p, Color.Black.copy(alpha = 0.55f))
    drawPath(p, color)
}

/** Los numeros, arriba a la izquierda y con sombra: el fondo es la imagen de la camara. */
@Composable
private fun Lectura(azimut: Double?, elevacion: Double, ladeo: Double,
                    cubiertos: Int, total: Int, registrando: Boolean,
                    declinacion: Double?, precision: CompassAccuracy,
                    modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.small,
            modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(azimut?.let {
                     cl.umag.glaciertemp.core.sensors.Compass.format(it, 1).padStart(6, '0') +
                     "  " + cl.umag.glaciertemp.core.sensors.Compass.cardinal(it)
                 } ?: "—",
                 color = Color.White, fontFamily = FontFamily.Monospace,
                 style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.testTag("hz-azimuth"))
            Text("elev %+.1f°".format(elevacion),
                 color = Color.White, fontFamily = FontFamily.Monospace,
                 style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("hz-elevation"))
            Text(if (registrando) "$cubiertos/$total sectors" else "not recording",
                 color = if (registrando) Color.White.copy(alpha = 0.8f)
                         else Color(0xFFFFC107),
                 style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.testTag("hz-progress"))
            Text((declinacion?.let { "true N  ·  decl " + Declination.describe(it) }
                      ?: "magnetic N  ·  no position") +
                 "  ·  compass " + precision.label,
                 color = if (declinacion != null && !precision.isPoor())
                             Color.White.copy(alpha = 0.8f) else Color(0xFFFFC107),
                 style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.testTag("hz-north"))
        }
    }
}
