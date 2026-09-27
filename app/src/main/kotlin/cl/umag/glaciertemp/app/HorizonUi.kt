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
fun HorizonMapperScreen(onDone: (HorizonProfile, Int, Int) -> Unit, onCancel: () -> Unit) {
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

    val bins = remember { HorizonBins() }
    val R = remember { FloatArray(9) }
    val v by sensorRotation()

    var azimut by remember { mutableStateOf<Double?>(null) }
    var elevacion by remember { mutableStateOf(0.0) }
    var ladeo by remember { mutableStateOf(0.0) }
    var cubiertos by remember { mutableIntStateOf(0) }
    var muestras by remember { mutableIntStateOf(0) }
    // La media del sector ANTES de esta visita: es contra lo que se compara al volver a pasar.
    var referencia by remember { mutableStateOf<Double?>(null) }

    // El sector en el que se esta y lo que se lleva acumulado en ESTA visita. Se vuelca al
    // salir del sector: mientras se esta dentro, comparar contra uno mismo no diria nada.
    var sectorActual by remember { mutableIntStateOf(-1) }
    val enCurso = remember { ArrayList<Pair<Double, Double>>() }

    LaunchedEffect(v) {
        val vec = v ?: return@LaunchedEffect
        android.hardware.SensorManager.getRotationMatrixFromVector(R, vec)
        val (az, el) = ViewDirection.of(R)
        azimut = az
        elevacion = el
        ladeo = ViewDirection.roll(R)

        val b = bins.binOf(az)
        if (b != sectorActual) {
            // Se cambio de sector: lo acumulado se vuelca y el nuevo estrena referencia.
            enCurso.forEach { (a, e) -> bins.add(a, e) }
            enCurso.clear()
            sectorActual = b
            referencia = bins.mean(b)
            cubiertos = bins.covered()
        }
        enCurso += az to el
        muestras++
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VistaDeCamara(Modifier.fillMaxSize())
            Cruz(diferencia = referencia?.let { it - elevacion },
                 modifier = Modifier.fillMaxSize())
            Lectura(azimut, elevacion, ladeo, cubiertos, bins.total(),
                    Modifier.align(Alignment.TopStart).padding(12.dp))
        }

        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(progress = { cubiertos.toFloat() / bins.total() },
                                    modifier = Modifier.fillMaxWidth())
            Text(if (abs(ladeo) > 10)
                     "Hold the phone upright — it is tilted ${"%.0f".format(abs(ladeo))}°"
                 else "Turn slowly, keeping the crosshair on the skyline.",
                 style = MaterialTheme.typography.bodySmall,
                 color = if (abs(ladeo) > 10) MaterialTheme.colorScheme.error
                         else MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("hz-hint"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                           enCurso.forEach { (a, e) -> bins.add(a, e) }
                           enCurso.clear()
                           bins.profile()?.let { onDone(it, bins.covered(), muestras) }
                       },
                       enabled = cubiertos > 0,
                       modifier = Modifier.weight(1f).height(56.dp).testTag("hz-finish")) {
                    Text("Finish", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = onCancel,
                               modifier = Modifier.height(56.dp).testTag("hz-cancel")) {
                    Text("Cancel")
                }
            }
        }
    }
}

private fun tienePermisoCamara(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** El vector de rotacion, a la frecuencia mas alta: se esta girando y la vista lo sigue. */
@Composable
private fun sensorRotation(): State<FloatArray?> {
    val ctx = LocalContext.current
    val valores = remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val s = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || s == null) return@DisposableEffect onDispose { }
        val oyente = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                valores.value = e.values.copyOf()
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(oyente, s, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(oyente) }
    }
    return valores
}

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
    val aviso = Color(0xFFFFC107)
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
            flecha(c, arriba = d > 0, largo = brazo * 1.5f, color = aviso)
        }
        diferencia?.takeIf { abs(it) >= 0.3 }?.let { d ->
            Text("%+.1f°".format(d),
                 color = aviso,
                 fontWeight = FontWeight.Bold,
                 fontFamily = FontFamily.Monospace,
                 fontSize = 22.sp,
                 modifier = Modifier.align(Alignment.Center)
                     .offset(x = 56.dp, y = if (d > 0) (-44).dp else 44.dp)
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
                    cubiertos: Int, total: Int, modifier: Modifier = Modifier) {
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
            Text("$cubiertos/$total sectors",
                 color = Color.White.copy(alpha = 0.8f),
                 style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.testTag("hz-progress"))
        }
    }
}
