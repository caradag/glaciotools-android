package cl.umag.glaciertemp.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.sensors.AlbedoRun
import cl.umag.glaciertemp.core.sensors.Compass
import cl.umag.glaciertemp.core.sensors.SensorReport
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Los sensores que el propio telefono lleva dentro.
 *
 * POR QUE ESTAN AQUI Y NO EN LA LIBRETA. Ninguno de estos numeros se anota por si mismo:
 * se miran en el momento --como esta de inclinada la superficie, hacia donde mira esta
 * grieta, cuanto ha bajado la presion desde el desayuno-- y quien quiera guardarlos los
 * copia y los pega en una nota. Meterlos en la libreta habria obligado a inventar un tipo
 * de entrada para cada sensor.
 *
 * TODO LLEVA BOTON DE COPIAR. Es lo unico que convierte una lectura en un dato: sin el hay
 * que transcribir a mano tres cifras con guantes, y ahi es donde se cambian los digitos.
 */
@Composable
fun SensorsScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        ToolBar("Onboard sensors", onBack = onBack)

        // Desplazables: cuatro nombres no caben en el ancho de un telefono, y partirlos en
        // dos filas robaria altura a la unica pantalla donde el dato es una cifra grande.
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("Tilt") }, modifier = Modifier.testTag("sn-tab-tilt"))
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("Compass") }, modifier = Modifier.testTag("sn-tab-compass"))
            Tab(selected = tab == 2, onClick = { tab = 2 },
                text = { Text("Pressure") }, modifier = Modifier.testTag("sn-tab-pressure"))
            Tab(selected = tab == 3, onClick = { tab = 3 },
                text = { Text("Light") }, modifier = Modifier.testTag("sn-tab-light"))
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> TiltTab()
                1 -> CompassTab()
                2 -> PressureTab()
                else -> LightTab()
            }
        }
    }
}

// ------------------------------- lo comun a las pestanas -------------------------------

private val CUANDO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun ahora(): String = CUANDO.format(Date())

private fun copiar(ctx: Context, texto: String) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cb.setPrimaryClip(ClipData.newPlainText("GlacioTools", texto))
}

/**
 * Se suscribe a un sensor mientras la pestana esta a la vista.
 *
 * SE DA DE BAJA AL SALIR, y no es un detalle: un acelerometro a la maxima frecuencia
 * despierta la CPU varias veces por segundo, y en una campana de una semana con el telefono
 * como unica libreta, la bateria es el recurso que de verdad se acaba.
 */
@Composable
private fun sensorValues(tipo: Int, periodoUs: Int = SensorManager.SENSOR_DELAY_UI):
        State<FloatArray?> {
    val ctx = LocalContext.current
    val valores = remember(tipo) { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(tipo) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(tipo)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val oyente = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { valores.value = e.values.copyOf() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(oyente, sensor, periodoUs)
        onDispose { sm.unregisterListener(oyente) }
    }
    return valores
}

@Composable
private fun hay(tipo: Int): Boolean {
    val ctx = LocalContext.current
    return remember(tipo) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.getDefaultSensor(tipo) != null
    }
}

/**
 * Cuando el telefono no trae el sensor.
 *
 * SE DICE CUAL FALTA Y SE DEJA DE HABLAR DEL TEMA. Muchos telefonos no llevan barometro, y
 * una pestana en blanco se lee como "esta roto" -- que lleva a reiniciar la app en mitad
 * del terreno buscando un fallo que no existe.
 */
@Composable
private fun SinSensor(nombre: String) {
    Column(Modifier.fillMaxSize().padding(24.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This phone has no $nombre.", style = MaterialTheme.typography.titleMedium)
        Text("Nothing is broken — the hardware simply is not there. " +
             "The other tabs still work.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** La cifra grande, que es lo que se lee a un metro y con el telefono en la otra mano. */
@Composable
private fun Cifra(rotulo: String, valor: String, tag: String) {
    Column {
        Text(rotulo, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valor, style = MaterialTheme.typography.displaySmall,
             fontFamily = FontFamily.Monospace,
             modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun BotonCopiar(texto: () -> String, tag: String) {
    val ctx = LocalContext.current
    var copiado by remember { mutableStateOf(false) }
    LaunchedEffect(copiado) { if (copiado) { delay(1500); copiado = false } }
    OutlinedButton(onClick = { copiar(ctx, texto()); copiado = true },
                   modifier = Modifier.testTag(tag)) {
        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (copiado) "Copied" else "Copy")
    }
}

/**
 * Yaw, pitch y roll a partir del vector de rotacion.
 *
 * SE USA ROTATION_VECTOR y no el acelerometro crudo: el sistema ya combina acelerometro,
 * giroscopo y magnetometro, y el resultado no se va con cada sacudida. Sostener un telefono
 * con una mano sobre un glaciar es exactamente el caso en que el filtrado importa.
 */
private fun anglesDe(v: FloatArray): Triple<Double, Double, Double> {
    val m = FloatArray(9)
    SensorManager.getRotationMatrixFromVector(m, v)
    val o = FloatArray(3)
    SensorManager.getOrientation(m, o)
    val yaw = Compass.normalize(Math.toDegrees(o[0].toDouble()))
    val pitch = Math.toDegrees(o[1].toDouble())
    val roll = Math.toDegrees(o[2].toDouble())
    return Triple(yaw, pitch, roll)
}

// ------------------------------------ inclinometro ------------------------------------

@Composable
private fun TiltTab() {
    if (!hay(Sensor.TYPE_ROTATION_VECTOR)) return SinSensor("orientation sensor")
    val v by sensorValues(Sensor.TYPE_ROTATION_VECTOR)
    val a = v?.let { anglesDe(it) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cifra("Yaw (azimuth)",
              a?.let { Compass.format(it.first, 1) + "  " + Compass.cardinal(it.first) } ?: "—",
              "sn-yaw")
        Cifra("Pitch", a?.let { "%.1f°".format(it.second) } ?: "—", "sn-pitch")
        Cifra("Roll", a?.let { "%.1f°".format(it.third) } ?: "—", "sn-roll")

        BotonCopiar({ a?.let { SensorReport.tilt(it.first, it.second, it.third, ahora()) }
                      ?: "" }, "sn-tilt-copy")

        Text("Pitch is the tilt along the phone's long axis and roll across it. " +
             "Laid flat on a surface, both read its slope. " +
             "Yaw needs the magnetometer, so it drifts near metal.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --------------------------------------- brujula ---------------------------------------

@Composable
private fun CompassTab() {
    if (!hay(Sensor.TYPE_ROTATION_VECTOR)) return SinSensor("compass")
    val v by sensorValues(Sensor.TYPE_ROTATION_VECTOR)
    val campo by sensorValues(Sensor.TYPE_MAGNETIC_FIELD)
    val rumbo = v?.let { anglesDe(it).first }
    val uT = campo?.let { sqrt((it[0]*it[0] + it[1]*it[1] + it[2]*it[2]).toDouble()) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Rosa(rumbo)

        Cifra("Heading",
              rumbo?.let { Compass.format(it) + "  " + Compass.cardinal(it) } ?: "—",
              "sn-heading")
        uT?.let {
            Text("Magnetic field %.1f µT".format(it),
                 style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("sn-field"))
        }

        BotonCopiar({ rumbo?.let { SensorReport.compass(it, uT, ahora()) } ?: "" },
                    "sn-compass-copy")

        // El aviso del campo va con NUMEROS porque "cerca de metal" no es accionable si uno
        // esta de pie sobre un trineo: 25-65 uT es lo normal en la superficie terrestre, y
        // ver 200 dice sin ambiguedad que la lectura no vale.
        Text("This is magnetic north, not true north. Earth's field is 25–65 µT at the " +
             "surface; a much larger reading means something nearby is magnetic — a ski " +
             "pole, a sled, the vehicle — and the heading is wrong.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** La rosa. Gira la aguja, no el circulo: el que mira es el que gira, no el norte. */
@Composable
private fun Rosa(rumbo: Double?) {
    val linea = MaterialTheme.colorScheme.onSurfaceVariant
    val norte = MaterialTheme.colorScheme.error
    val sur = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(200.dp).testTag("sn-rose")) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 8f
        drawCircle(linea, radius = r, center = c, style = Stroke(width = 3f))
        // Las marcas cada 30 grados dan escala sin llenarlo de numeros.
        for (g in 0 until 360 step 30) {
            val rad = Math.toRadians(g.toDouble() - 90.0)
            val largo = if (g % 90 == 0) 18f else 10f
            drawLine(linea,
                     Offset(c.x + (r - largo) * cos(rad).toFloat(),
                            c.y + (r - largo) * sin(rad).toFloat()),
                     Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()),
                     strokeWidth = 3f)
        }
        if (rumbo == null) return@Canvas
        // La aguja apunta al norte magnetico: si el telefono mira a rumbo R, el norte esta a
        // -R en la pantalla.
        val rad = Math.toRadians(-rumbo - 90.0)
        val punta = Offset(c.x + (r - 22f) * cos(rad).toFloat(),
                           c.y + (r - 22f) * sin(rad).toFloat())
        val cola = Offset(c.x - (r - 22f) * cos(rad).toFloat(),
                          c.y - (r - 22f) * sin(rad).toFloat())
        drawLine(norte, c, punta, strokeWidth = 10f)
        drawLine(sur, c, cola, strokeWidth = 6f)
        drawCircle(linea, radius = 8f, center = c)
    }
}

// -------------------------------------- barometro --------------------------------------

@Composable
private fun PressureTab() {
    if (!hay(Sensor.TYPE_PRESSURE)) return SinSensor("barometer")
    val v by sensorValues(Sensor.TYPE_PRESSURE)
    val hPa = v?.getOrNull(0)?.toDouble()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cifra("Pressure", hPa?.let { "%.2f hPa".format(it) } ?: "—", "sn-pressure")

        BotonCopiar({ hPa?.let { SensorReport.pressure(it, ahora()) } ?: "" },
                    "sn-pressure-copy")

        // Se dice lo que NO es, porque es el error que invita a cometer: el barometro no
        // sabe la altura sin una referencia a nivel del mar, y esa referencia cambia con el
        // tiempo mas que la propia altura durante una jornada.
        Text("Raw sensor pressure, not corrected to sea level. It cannot give an altitude " +
             "on its own: that needs a sea-level reference, which over a day changes more " +
             "than most of what you would be measuring. What it is good for is the change: " +
             "a steady fall over hours is weather coming.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------- luz y medida de albedo -------------------------------

/** En que punto de la medida de albedo estamos. */
private enum class Albedo { PARADO, AVISO_ARRIBA, ARRIBA, AVISO_ABAJO, ABAJO, RESULTADO }

@Composable
private fun LightTab() {
    if (!hay(Sensor.TYPE_LIGHT)) return SinSensor("light sensor")
    // Lo mas rapido que de: durante los cinco segundos de medida se promedia todo lo que
    // llegue, y con pocas muestras una nube que pasa desplaza la media entera.
    val v by sensorValues(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_FASTEST)
    val lux = v?.getOrNull(0)?.toDouble()
    val luxAhora = rememberUpdatedState(lux)

    val beeper = remember { GpsTimeBeeper() }
    var fase by remember { mutableStateOf(Albedo.PARADO) }
    var cuenta by remember { mutableIntStateOf(0) }
    var midiendo by remember { mutableStateOf(false) }
    var incidente by remember { mutableStateOf<Double?>(null) }
    var reflejada by remember { mutableStateOf<Double?>(null) }

    /**
     * Cuenta atras, medida y pitidos de una de las dos mitades.
     *
     * SE PROMEDIA TODO EL RATO en vez de tomar el valor al final. El sensor de luz de un
     * telefono da saltos --cambia de escala de ganancia, y la mano tiembla-- y un unico
     * instante puede caer justo en uno de esos saltos. Cinco segundos de media es lo que
     * hace que repetir la medida dos veces de lo mismo.
     */
    LaunchedEffect(fase) {
        if (fase != Albedo.ARRIBA && fase != Albedo.ABAJO) return@LaunchedEffect

        midiendo = false
        for (s in AlbedoRun.PREP_SECONDS downTo 1) {
            cuenta = s
            AlbedoRun.beep(midiendo = false, ultimo = false).let { beeper.beep(it.hz, it.ms) }
            delay(1000)
        }

        midiendo = true
        var suma = 0.0
        var n = 0
        for (s in AlbedoRun.HOLD_SECONDS downTo 1) {
            cuenta = s
            AlbedoRun.beep(midiendo = true, ultimo = false).let { beeper.beep(it.hz, it.ms) }
            repeat(10) {
                delay(100)
                luxAhora.value?.let { suma += it; n++ }
            }
        }
        AlbedoRun.beep(midiendo = true, ultimo = true).let { beeper.beep(it.hz, it.ms) }

        val media = if (n > 0) suma / n else (luxAhora.value ?: 0.0)
        midiendo = false
        cuenta = 0
        if (fase == Albedo.ARRIBA) { incidente = media; fase = Albedo.AVISO_ABAJO }
        else { reflejada = media; fase = Albedo.RESULTADO }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Cifra("Illuminance", lux?.let { AlbedoRun.fmt(it) + " lx" } ?: "—", "sn-lux")

        BotonCopiar({ lux?.let { SensorReport.light(it, ahora()) } ?: "" }, "sn-light-copy")

        HorizontalDivider()

        if (fase == Albedo.ARRIBA || fase == Albedo.ABAJO) {
            Medicion(hacia = if (fase == Albedo.ARRIBA) "up" else "down",
                     midiendo = midiendo, cuenta = cuenta,
                     onCancel = { fase = Albedo.PARADO; cuenta = 0; midiendo = false })
        } else {
            Button(onClick = {
                       incidente = null; reflejada = null; fase = Albedo.AVISO_ARRIBA
                   },
                   modifier = Modifier.testTag("sn-albedo-start")) {
                Text("Calculate albedo")
            }
        }

        if (fase == Albedo.RESULTADO && incidente != null && reflejada != null) {
            Resultado(incidente!!, reflejada!!)
        }

        Text("Albedo is the fraction of light the surface sends back. Fresh snow is around " +
             "0.8, old snow 0.5–0.7, bare ice 0.3–0.4, and dirty or debris-covered ice can " +
             "be under 0.2.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (fase == Albedo.AVISO_ARRIBA) Instrucciones(
        titulo = "Point the phone up",
        cuerpo = "Hold the phone flat with the screen facing the sky, as clear of " +
                 "obstructions as you can — away from your body, from the sled and from " +
                 "anything that casts a shadow.\n\n" +
                 "You get ${AlbedoRun.PREP_SECONDS} seconds to get into position, with a " +
                 "beep each second. Then ${AlbedoRun.HOLD_SECONDS} more on a lower tone: " +
                 "hold still through those. A long beep means done.",
        onOk = { fase = Albedo.ARRIBA })

    if (fase == Albedo.AVISO_ABAJO) Instrucciones(
        titulo = "Now point it down",
        cuerpo = "Same thing, screen facing the surface, held at the same height and in " +
                 "the same spot. Keep your own shadow off the patch you are measuring.\n\n" +
                 "Same ${AlbedoRun.PREP_SECONDS} seconds to get into position, then " +
                 "${AlbedoRun.HOLD_SECONDS} holding still.",
        onOk = { fase = Albedo.ABAJO })
}

/**
 * Lo que se ve mientras corre la medida.
 *
 * LA PANTALLA NO ES LO QUE MANDA AQUI: el telefono esta mirando al cielo o al suelo, o sea
 * que nadie la esta viendo. Manda el sonido. Esto es para el que mira por encima del hombro
 * y para saber, al recoger el telefono, que no se cancelo a medias.
 */
@Composable
private fun Medicion(hacia: String, midiendo: Boolean, cuenta: Int, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (midiendo) "Measuring — hold still (facing $hacia)"
                 else "Get into position (facing $hacia)",
                 style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.testTag("sn-albedo-state"))
            Text("$cuenta", style = MaterialTheme.typography.displayMedium,
                 fontFamily = FontFamily.Monospace,
                 color = if (midiendo) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("sn-albedo-count"))
            TextButton(onClick = onCancel, modifier = Modifier.testTag("sn-albedo-cancel")) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun Resultado(incidente: Double, reflejada: Double) {
    val a = AlbedoRun.albedo(incidente, reflejada)
    Card(Modifier.fillMaxWidth().testTag("sn-albedo-result")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Albedo", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(a?.let { AlbedoRun.fmtAlbedo(it) } ?: "—",
                 style = MaterialTheme.typography.displaySmall,
                 fontFamily = FontFamily.Monospace,
                 modifier = Modifier.testTag("sn-albedo-value"))
            Text("Incident (up): ${AlbedoRun.fmt(incidente)} lx",
                 style = MaterialTheme.typography.bodyMedium)
            Text("Reflected (down): ${AlbedoRun.fmt(reflejada)} lx",
                 style = MaterialTheme.typography.bodyMedium)
            AlbedoRun.warning(incidente, reflejada)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("sn-albedo-warning"))
            }
            Text("The phone's light sensor measures the visible band and is not calibrated " +
                 "like a pyranometer. Good for comparing surfaces under the same light; " +
                 "not a broadband albedo.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotonCopiar({ SensorReport.albedo(incidente, reflejada, ahora()) },
                        "sn-albedo-copy")
        }
    }
}

@Composable
private fun Instrucciones(titulo: String, cuerpo: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        modifier = Modifier.testTag("sn-albedo-dialog"),
        title = { Text(titulo) },
        text = { Text(cuerpo) },
        confirmButton = {
            TextButton(onClick = onOk, modifier = Modifier.testTag("sn-albedo-ok")) {
                Text("OK")
            }
        },
    )
}
