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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import cl.umag.glaciertemp.core.sensors.GreatCircle
import cl.umag.glaciertemp.core.sensors.PressurePlace
import cl.umag.glaciertemp.core.sensors.PressureSample
import cl.umag.glaciertemp.core.sensors.PressureReport
import cl.umag.glaciertemp.core.sensors.PressureStore
import cl.umag.glaciertemp.core.sensors.PressureTrend
import cl.umag.glaciertemp.core.sensors.AlbedoRun
import cl.umag.glaciertemp.core.sensors.Angles
import cl.umag.glaciertemp.core.sensors.Reversal
import cl.umag.glaciertemp.core.sensors.Scalars
import cl.umag.glaciertemp.core.sensors.Tilt
import cl.umag.glaciertemp.core.sensors.Compass
import cl.umag.glaciertemp.core.sensors.SensorReport
import cl.umag.glaciertemp.core.geomag.MagneticResult
import cl.umag.glaciertemp.core.geomag.Reliability
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
fun SensorsScreen(onBack: () -> Unit, location: LocationSource? = null,
                  pressureStore: PressureStore? = null) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // El mapeo de horizonte se lleva la pantalla entera: la imagen de la camara no cabe
    // debajo de una fila de pestanas, y ademas se esta girando sobre uno mismo.
    var horizonte by remember { mutableStateOf<Horizonte?>(null) }
    var mapeando by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = mapeando || horizonte != null) {
        if (mapeando) mapeando = false else horizonte = null
    }

    if (mapeando) {
        HorizonMapperScreen(
            onDone = { perfil, sectores, muestras ->
                horizonte = Horizonte(perfil, sectores, muestras); mapeando = false
            },
            onCancel = { mapeando = false },
            location = location)
        return
    }

    val h = horizonte
    if (h != null) {
        Column(Modifier.fillMaxSize()) {
            ToolBar("Horizon", atras = "Sensors", onBack = { horizonte = null })
            // La posicion se busca al entrar, sin bloquear: el sol depende de la latitud, y
            // sin ella el grafico se dibuja igual pero sin sus curvas, diciendolo.
            var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
            LaunchedEffect(Unit) {
                pos = runCatching { location?.lastKnownFix() }.getOrNull()
                    ?.let { it.latitude to it.longitude }
            }
            HorizonResultScreen(h.perfil, h.sectores, h.muestras,
                                pos?.first, pos?.second,
                                onRedo = { horizonte = null; mapeando = true },
                                onBack = { horizonte = null })
        }
        return
    }

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
                0 -> TiltTab(onMapHorizon = { mapeando = true }, location = location)
                1 -> CompassTab(location = location)
                2 -> PressureTab(store = pressureStore, location = location)
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
internal fun BotonCopiar(texto: () -> String, tag: String) {
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
    // El pitch se pasa a ELEVACION DE LA VISTA: cero con el telefono vertical. Ver
    // Tilt.viewElevation -- la referencia de Android es el aparato tumbado, que no es
    // ninguna postura de trabajo.
    val pitch = Tilt.viewElevation(Math.toDegrees(o[1].toDouble()))
    val roll = Math.toDegrees(o[2].toDouble())
    return Triple(yaw, pitch, roll)
}

/**
 * Cuenta atras, medida y pitidos. La MISMA para inclinacion y para albedo.
 *
 * POR QUE EXISTE ESTA FUNCION. Las dos medidas tienen el mismo problema: hay que apuntar el
 * telefono a donde no se puede mirar la pantalla --al cielo, al suelo, a una pared de hielo
 * por encima de la cabeza-- y entonces la unica guia posible es el oido. Sacarla aqui evita
 * que las dos pestanas acaben con cuentas atras que suenan distinto, que seria justo lo que
 * confunde a quien ya aprendio una de ellas.
 *
 * @param muestrear se llama diez veces por segundo durante la medida, y cada llamada anota
 *        una muestra. Se promedia el tramo entero en vez de leer al final porque los
 *        sensores dan saltos y la mano tiembla.
 */
private suspend fun secuenciaDeMedida(
    beeper: GpsTimeBeeper,
    onTick: (segundos: Int, midiendo: Boolean) -> Unit,
    muestrear: () -> Unit,
) {
    for (s in AlbedoRun.PREP_SECONDS downTo 1) {
        onTick(s, false)
        AlbedoRun.beep(midiendo = false, ultimo = false).let { beeper.beep(it.hz, it.ms) }
        delay(1000)
    }
    for (s in AlbedoRun.HOLD_SECONDS downTo 1) {
        onTick(s, true)
        AlbedoRun.beep(midiendo = true, ultimo = false).let { beeper.beep(it.hz, it.ms) }
        repeat(10) { delay(100); muestrear() }
    }
    AlbedoRun.beep(midiendo = true, ultimo = true).let { beeper.beep(it.hz, it.ms) }
    onTick(0, false)
}

// ------------------------------------ inclinometro ------------------------------------

/** Lo que deja una medida de cinco segundos de inclinacion. */
private data class Inclinacion(
    val yawM: Double?, val yawMd: Double?,
    val pitchM: Double?, val pitchMd: Double?,
    val rollM: Double?, val rollMd: Double?,
    val muestras: Int,
)

/** Lo que deja un mapeo de horizonte terminado. */
private data class Horizonte(
    val perfil: cl.umag.glaciertemp.core.sensors.HorizonProfile,
    val sectores: Int,
    val muestras: Int,
)

@Composable
private fun TiltTab(onMapHorizon: () -> Unit, location: LocationSource? = null) {
    val campoMag = rememberMagneticField(location)
    val declinacion = campoMag?.declination
    if (!hay(Sensor.TYPE_ROTATION_VECTOR)) return SinSensor("orientation sensor")
    val v by sensorValues(Sensor.TYPE_ROTATION_VECTOR)
    // El yaw se pasa a norte REAL aqui: lo que se lee y lo que se copia son lo mismo.
    val a = v?.let { anglesDe(it) }?.let { (y, p, r) ->
        Triple(Compass.normalize(y + (declinacion ?: 0.0)), p, r)
    }
    val ahoraRef = rememberUpdatedState(a)

    val beeper = remember { GpsTimeBeeper() }
    var corriendo by remember { mutableStateOf(false) }
    var cuenta by remember { mutableIntStateOf(0) }
    var midiendo by remember { mutableStateOf(false) }
    var medida by remember { mutableStateOf<Inclinacion?>(null) }
    // La segunda posicion, con el telefono girado media vuelta sobre la superficie.
    var segunda by remember { mutableStateOf<Inclinacion?>(null) }

    LaunchedEffect(corriendo) {
        if (!corriendo) return@LaunchedEffect
        val yaws = ArrayList<Double>()
        val pitches = ArrayList<Double>()
        val rolls = ArrayList<Double>()
        secuenciaDeMedida(
            beeper,
            onTick = { s, m -> cuenta = s; midiendo = m },
            muestrear = {
                ahoraRef.value?.let { (y, p, r) -> yaws += y; pitches += p; rolls += r }
            })
        val nueva = Inclinacion(
            Angles.mean(yaws), Angles.median(yaws),
            Angles.mean(pitches), Angles.median(pitches),
            Angles.mean(rolls), Angles.median(rolls),
            yaws.size)
        if (medida == null) medida = nueva else segunda = nueva
        midiendo = false
        cuenta = 0
        corriendo = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cifra(if (declinacion != null) "Yaw (azimuth, true N)" else "Yaw (azimuth, magnetic N)",
              a?.let { Compass.format(it.first, 1) + "  " + Compass.cardinal(it.first) } ?: "—",
              "sn-yaw")
        NorteYDeclinacion(campoMag)
        FilaDePrecision(rememberCompassAccuracy())
        Cifra("Pitch", a?.let { "%.1f°".format(it.second) } ?: "—", "sn-pitch")
        Cifra("Roll", a?.let { "%.1f°".format(it.third) } ?: "—", "sn-roll")

        BotonCopiar({ a?.let { SensorReport.tilt(it.first, it.second, it.third, ahora()) }
                      ?: "" }, "sn-tilt-copy")

        HorizontalDivider()

        if (corriendo) {
            Medicion(hacia = null, midiendo = midiendo, cuenta = cuenta,
                     onCancel = { corriendo = false; cuenta = 0; midiendo = false })
        } else {
            Button(onClick = { medida = null; segunda = null; corriendo = true },
                   modifier = Modifier.testTag("sn-tilt-measure")) {
                Text("Measure for ${AlbedoRun.HOLD_SECONDS} s")
            }
            Text("For when the phone has to point where you cannot see the screen — flat on " +
                 "a slope out of reach, or against an ice wall above your head. " +
                 "${AlbedoRun.PREP_SECONDS} s to place it, then ${AlbedoRun.HOLD_SECONDS} s " +
                 "of measurement, both counted out in beeps. A long beep means done.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        medida?.let { primera ->
            ResultadoInclinacion(primera)
            val dos = segunda
            if (dos == null && !corriendo) {
                // SEGUNDA POSICION, opcional. Cancela el sesgo del sensor sin calibrar nada.
                // EL EJE SE DICE: girar sobre otro no cancela el sesgo, lo mezcla.
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                           verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Second position (optional)",
                             style = MaterialTheme.typography.titleSmall)
                        Text("Turn the phone 180° WITHOUT LIFTING IT: keep it flat against " +
                             "the same surface and spin it in place, so the end that pointed " +
                             "away now points towards you. Which way you spin it does not " +
                             "matter.",
                             style = MaterialTheme.typography.bodyMedium)
                        Text("The sensor's bias turns with the phone while the surface stays " +
                             "put, so combining the two readings cancels it and shows how " +
                             "big it was.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { corriendo = true },
                                   modifier = Modifier.testTag("sn-tilt-second")) {
                                Text("Measure second position")
                            }
                            OutlinedButton(onClick = { segunda = primera.copy(muestras = -1) },
                                           modifier = Modifier.testTag("sn-tilt-skip")) {
                                Text("Keep just this one")
                            }
                        }
                    }
                }
            } else if (dos != null && dos.muestras >= 0) {
                ResultadoEnDosPosiciones(primera, dos)
            }
        }

        HorizontalDivider()

        // EL MAPEADOR DE HORIZONTE VIVE AQUI porque es el mismo sensor: apuntar la camara a
        // un sitio y quedarse con su elevacion. La diferencia es que en vez de una direccion
        // se recorren las trescientas sesenta.
        OutlinedButton(onClick = onMapHorizon,
                       modifier = Modifier.fillMaxWidth().testTag("sn-horizon")) {
            Text("Horizon mapper")
        }
        Text("Turn once on the spot with the camera on the skyline and it records the " +
             "elevation of the terrain all around. Then it draws the sun's path over it, " +
             "and works out where a solar panel should point.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Pitch is the tilt along the phone's long axis and roll across it. " +
             "Pitch is 0 with the phone upright and −90 with it face up, so it reads " +
             "directly as the elevation of whatever the camera points at. " +
             "Yaw needs the magnetometer, so it drifts near metal.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultadoInclinacion(m: Inclinacion) {
    Card(Modifier.fillMaxWidth().testTag("sn-tilt-result")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Measured over ${AlbedoRun.HOLD_SECONDS} s  ·  ${m.muestras} samples",
                 style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilaMediaMediana("Yaw", m.yawM?.let { Compass.normalize(it) },
                             m.yawMd?.let { Compass.normalize(it) }, "sn-tilt-yaw-r")
            FilaMediaMediana("Pitch", m.pitchM, m.pitchMd, "sn-tilt-pitch-r")
            FilaMediaMediana("Roll", m.rollM, m.rollMd, "sn-tilt-roll-r")
            Tilt.warning(m.pitchMd ?: m.pitchM)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("sn-tilt-gimbal"))
            }
            // Las dos cifras estan para COMPARARLAS: si se separan, algo se movio.
            Text("Mean and median together: close means the phone held still; far apart " +
                 "means something moved during the measurement.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotonCopiar({
                SensorReport.tiltMeasured(m.yawM, m.yawMd, m.pitchM, m.pitchMd,
                                          m.rollM, m.rollMd, m.muestras,
                                          AlbedoRun.HOLD_SECONDS, ahora())
            }, "sn-tilt-result-copy")
        }
    }
}

@Composable
private fun FilaMediaMediana(rotulo: String, media: Double?, mediana: Double?, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(rotulo, style = MaterialTheme.typography.bodyMedium,
             modifier = Modifier.width(64.dp))
        Text("mean " + (media?.let { "%.1f°".format(it) } ?: "—") +
             "   median " + (mediana?.let { "%.1f°".format(it) } ?: "—"),
             style = MaterialTheme.typography.bodyMedium,
             fontFamily = FontFamily.Monospace,
             modifier = Modifier.testTag(tag))
    }
}

// --------------------------------------- brujula ---------------------------------------

@Composable
private fun CompassTab(location: LocationSource? = null) {
    val campoMag = rememberMagneticField(location)
    val declinacion = campoMag?.declination
    if (!hay(Sensor.TYPE_ROTATION_VECTOR)) return SinSensor("compass")
    val v by sensorValues(Sensor.TYPE_ROTATION_VECTOR)
    val campo by sensorValues(Sensor.TYPE_MAGNETIC_FIELD)
    val rumbo = v?.let { Compass.normalize(anglesDe(it).first + (declinacion ?: 0.0)) }
    val uT = campo?.let { sqrt((it[0]*it[0] + it[1]*it[1] + it[2]*it[2]).toDouble()) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Rosa(rumbo)

        Cifra(if (declinacion != null) "Heading (true N)" else "Heading (magnetic N)",
              rumbo?.let { Compass.format(it) + "  " + Compass.cardinal(it) } ?: "—",
              "sn-heading")
        NorteYDeclinacion(campoMag)
        FilaDePrecision(rememberCompassAccuracy())
        uT?.let {
            Text("Magnetic field %.1f µT".format(it),
                 style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("sn-field"))
        }

        BotonCopiar({
            rumbo?.let {
                SensorReport.compass(it, uT, ahora(), trueNorth = campoMag != null,
                                     declination = campoMag?.declination,
                                     declinationRate = campoMag?.declinationRate,
                                     model = campoMag?.model)
            } ?: ""
        }, "sn-compass-copy")

        // El aviso del campo va con NUMEROS porque "cerca de metal" no es accionable si uno
        // esta de pie sobre un trineo: 25-65 uT es lo normal en la superficie terrestre, y
        // ver 200 dice sin ambiguedad que la lectura no vale.
        Text("Earth's field is 25–65 µT at the surface; a much larger reading means " +
             "something nearby is magnetic — a ski pole, a sled, the vehicle — and the " +
             "heading is wrong no matter how well the compass is calibrated.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        DeclinationCalculator(location)
    }
}

/**
 * Que norte se esta usando y con que correccion.
 *
 * SE DICE SIEMPRE, tambien cuando no hay posicion. Un azimut sin decir de que norte habla es
 * el dato que despues no se puede usar: la diferencia son mas de diez grados en Patagonia, y
 * quien lo lea dentro de un ano no tiene forma de saber cual era.
 */
@Composable
private fun NorteYDeclinacion(campo: MagneticResult?) {
    Text(campo?.let {
             "True north. Magnetic declination " + Declination.describe(it.declination) +
             ", " + Declination.describeRate(it.declinationRate) + " (" + it.model + ")" +
             ", added to what the sensor reports."
         } ?: "Magnetic north: no position yet, so the declination is unknown and nothing " +
              "has been corrected.",
         style = MaterialTheme.typography.bodySmall,
         color = if (campo != null) MaterialTheme.colorScheme.onSurfaceVariant
                 else MaterialTheme.colorScheme.error,
         modifier = Modifier.testTag("sn-declination"))
    // Cerca del polo magnetico la componente horizontal se hace pequena: la aguja apunta
    // mal y la declinacion cambia mucho en pocos kilometros. Umbrales del informe de WMM.
    when (campo?.reliability) {
        Reliability.CAUTION -> Text(
            "Near the magnetic pole (horizontal field %.0f nT): the declination changes quickly "
                .format(campo.horizontal) + "with position and compass headings are less reliable.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("sn-declination-warning"))
        Reliability.UNRELIABLE -> Text(
            "At the magnetic pole (horizontal field %.0f nT): the declination and compass "
                .format(campo.horizontal) + "headings are unreliable here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("sn-declination-warning"))
        else -> {}
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
private fun PressureTab(store: PressureStore? = null, location: LocationSource? = null) {
    if (!hay(Sensor.TYPE_PRESSURE)) return SinSensor("barometer")
    val v by sensorValues(Sensor.TYPE_PRESSURE)
    val hPa = v?.getOrNull(0)?.toDouble()

    var lugares by remember { mutableStateOf(store?.list() ?: emptyList()) }
    var creando by remember { mutableStateOf(false) }
    var avisoLejos by remember { mutableStateOf<Pair<PressurePlace, Double>?>(null) }
    val alcance = rememberCoroutineScope()

    /** Toma una lectura, con la posicion si se puede, y la guarda. */
    fun guardar(lugar: PressurePlace, presion: Double) {
        val st = store ?: return
        alcance.launch {
            val fix = runCatching { location?.lastKnownFix() }.getOrNull()
            st.append(lugar.id, PressureSample(
                System.currentTimeMillis(), presion,
                fix?.latitude, fix?.longitude, fix?.altitudeMetres))
            lugares = st.list()
        }
    }

    /** Comprueba que se esta donde se dijo antes de anadir la lectura. */
    fun tomar(lugar: PressurePlace) {
        val presion = hPa ?: return
        val st = store ?: return
        alcance.launch {
            val fix = runCatching { location?.lastKnownFix() }.getOrNull()
            val ref = lugar.reference()
            val rLat = ref?.latitude
            val rLon = ref?.longitude
            val d = if (fix != null && rLat != null && rLon != null)
                GreatCircle.metres(fix.latitude, fix.longitude, rLat, rLon)
            else null
            if (d != null && d > DISTANCIA_MAXIMA_M) {
                avisoLejos = lugar to d
            } else {
                st.append(lugar.id, PressureSample(
                    System.currentTimeMillis(), presion,
                    fix?.latitude, fix?.longitude, fix?.altitudeMetres))
                lugares = st.list()
            }
        }
    }

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

        HorizontalDivider()
        Text("Places", style = MaterialTheme.typography.titleMedium)
        // UN REGISTRO POR LUGAR, y no uno solo para todo. La presion cae unos 12 hPa por cada
        // cien metros de altura: mezclando lecturas del campamento y de una estacion mas
        // alta, la serie mide la cuesta y no el tiempo. Manteniendo la altura constante, el
        // cambio SI es meteorologia, y eso vale igual a nivel del mar que a mil metros.
        Text("One series per place, each always sampled from the same spot. Pressure drops " +
             "about 12 hPa per 100 m of height, so mixing readings from camp and from a " +
             "station higher up measures the walk, not the weather.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { creando = true }, modifier = Modifier.testTag("sn-place-new")) {
            Text("New place")
        }

        if (lugares.isEmpty()) {
            Text("No places yet. Create one where you will come back to — camp, a stake " +
                 "field, a weather station — and take a reading whenever you pass.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("sn-places-empty"))
        }

        lugares.forEach { lugar ->
            BloqueDeLugar(
                lugar = lugar,
                presionActual = hPa,
                onTake = { tomar(lugar) },
                onRename = { nuevo -> store?.rename(lugar.id, nuevo); lugares = store?.list() ?: emptyList() },
                onDelete = { store?.delete(lugar.id); lugares = store?.list() ?: emptyList() })
        }
        Spacer(Modifier.height(8.dp))
    }

    if (creando) NombreDeLugar(
        titulo = "New place", inicial = "",
        onDone = { nombre ->
            store?.create(nombre)
            lugares = store?.list() ?: emptyList()
            creando = false
        },
        onCancel = { creando = false })

    avisoLejos?.let { (lugar, d) ->
        AlertDialog(
            onDismissRequest = { avisoLejos = null },
            modifier = Modifier.testTag("sn-place-far"),
            title = { Text("You are not at “${lugar.name}”") },
            text = {
                Text("This is %.0f m from where the series was started. Pressure drops about "
                         .format(d) +
                     "12 hPa per 100 m of height, so a reading taken somewhere else does not " +
                     "compare with the rest and will look like a weather change that did not " +
                     "happen.")
            },
            confirmButton = {
                TextButton(onClick = {
                               hPa?.let { guardar(lugar, it) }
                               avisoLejos = null
                           },
                           modifier = Modifier.testTag("sn-place-far-save")) {
                    Text("Add it anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { avisoLejos = null }) { Text("Cancel") }
            })
    }
}

/** A partir de aqui ya no se esta en el mismo sitio: 100 m son unos 12 hPa. */
private const val DISTANCIA_MAXIMA_M = 100.0


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
    var incidente by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var reflejada by remember { mutableStateOf<Pair<Double, Double>?>(null) }

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

        val muestras = ArrayList<Double>()
        secuenciaDeMedida(
            beeper,
            onTick = { s, m -> cuenta = s; midiendo = m },
            muestrear = { luxAhora.value?.let { muestras += it } })

        val media = Scalars.mean(muestras) ?: luxAhora.value ?: 0.0
        val mediana = Scalars.median(muestras) ?: media
        midiendo = false
        cuenta = 0
        if (fase == Albedo.ARRIBA) { incidente = media to mediana; fase = Albedo.AVISO_ABAJO }
        else { reflejada = media to mediana; fase = Albedo.RESULTADO }
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

        val inc = incidente
        val ref = reflejada
        if (fase == Albedo.RESULTADO && inc != null && ref != null) Resultado(inc, ref)

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
private fun Medicion(hacia: String?, midiendo: Boolean, cuenta: Int, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val donde = hacia?.let { " (facing $it)" } ?: ""
            Text(if (midiendo) "Measuring — hold still$donde"
                 else "Get into position$donde",
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
private fun Resultado(incidente: Pair<Double, Double>, reflejada: Pair<Double, Double>) {
    val a = AlbedoRun.albedo(incidente.first, reflejada.first)
    val am = AlbedoRun.albedo(incidente.second, reflejada.second)
    Card(Modifier.fillMaxWidth().testTag("sn-albedo-result")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Albedo", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(a?.let { AlbedoRun.fmtAlbedo(it) } ?: "—",
                 style = MaterialTheme.typography.displaySmall,
                 fontFamily = FontFamily.Monospace,
                 modifier = Modifier.testTag("sn-albedo-value"))
            Text("from means  ·  from medians " +
                 (am?.let { AlbedoRun.fmtAlbedo(it) } ?: "—"),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("sn-albedo-median"))
            Text("Incident (up): mean ${AlbedoRun.fmt(incidente.first)} lx, " +
                 "median ${AlbedoRun.fmt(incidente.second)} lx",
                 style = MaterialTheme.typography.bodyMedium)
            Text("Reflected (down): mean ${AlbedoRun.fmt(reflejada.first)} lx, " +
                 "median ${AlbedoRun.fmt(reflejada.second)} lx",
                 style = MaterialTheme.typography.bodyMedium)
            AlbedoRun.warning(incidente.first, reflejada.first)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("sn-albedo-warning"))
            }
            AlbedoRun.unstable(a, am)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("sn-albedo-unstable"))
            }
            Text("The phone's light sensor measures the visible band and is not calibrated " +
                 "like a pyranometer. Good for comparing surfaces under the same light; " +
                 "not a broadband albedo.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotonCopiar({ SensorReport.albedo(incidente.first, incidente.second,
                                              reflejada.first, reflejada.second, ahora()) },
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


/**
 * Lo que sale de combinar las dos posiciones.
 *
 * CADA ANGULO SE COMBINA DE SU MANERA, y equivocarse aqui da casi cero en vez del valor:
 * el pitch y el roll son del APARATO y cambian de signo al girarlo, asi que el valor sale de
 * la semidiferencia; el yaw es un rumbo y la segunda lectura viene girada media vuelta. Ver
 * Reversal, que es donde esta la aritmetica y sus pruebas.
 */
@Composable
private fun ResultadoEnDosPosiciones(a: Inclinacion, b: Inclinacion) {
    val yaw = if (a.yawMd != null && b.yawMd != null)
        Reversal.heading(a.yawMd, b.yawMd) else null
    // El pitch que se enseña es la ELEVACION DE VISTA, medida desde la vertical: lleva un
    // desplazamiento de 90 grados que hay que quitar antes de combinar. Tratarla como una
    // magnitud del aparato --como se hizo primero-- da -90 de sesgo con cualquier
    // inclinacion.
    val pitch = if (a.pitchMd != null && b.pitchMd != null)
        Reversal.viewElevation(a.pitchMd, b.pitchMd) else null
    val roll = if (a.rollMd != null && b.rollMd != null)
        Reversal.device(a.rollMd, b.rollMd) else null

    Card(Modifier.fillMaxWidth().testTag("sn-tilt-two")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Two positions, bias removed", style = MaterialTheme.typography.titleSmall)
            FilaCorregida("Yaw", yaw?.let { Compass.normalize(it.value) }, yaw?.bias, "sn-two-yaw")
            FilaCorregida("Pitch", pitch?.value, pitch?.bias, "sn-two-pitch")
            FilaCorregida("Roll", roll?.value, roll?.bias, "sn-two-roll")
            Reversal.warning(listOfNotNull(pitch?.bias, roll?.bias))?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("sn-two-warn"))
            }
            Text("The bias column is what the two positions disagreed by, halved. It is the " +
                 "sensor's own offset, and it is gone from the value on the left.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilaCorregida(rotulo: String, valor: Double?, sesgo: Double?, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(rotulo, style = MaterialTheme.typography.bodyMedium,
             modifier = Modifier.width(56.dp))
        Text((valor?.let { "%.1f°".format(it) } ?: "—") +
             "     bias " + (sesgo?.let { "%+.2f°".format(it) } ?: "—"),
             style = MaterialTheme.typography.bodyMedium,
             fontFamily = FontFamily.Monospace,
             modifier = Modifier.testTag(tag))
    }
}

// ------------------------- el registro de presion de un lugar -------------------------

/**
 * Un lugar: su nombre, su grafico y el boton de tomar lectura.
 *
 * LA TENDENCIA VA ANTES QUE EL GRAFICO. Saber que hay 985 hPa no dice nada sin conocer la
 * altura del sitio; saber que han caido cuatro en seis horas dice que viene un frente, y eso
 * es lo que se viene a mirar. El grafico esta para ver la FORMA de la caida, que es otra
 * pregunta.
 */
@Composable
private fun BloqueDeLugar(
    lugar: PressurePlace,
    presionActual: Double?,
    onTake: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var renombrar by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().testTag("sn-place")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lugar.name.ifBlank { "(unnamed place)" },
                     style = MaterialTheme.typography.titleSmall,
                     modifier = Modifier.weight(1f).testTag("sn-place-name"))
                TextButton(onClick = { renombrar = true },
                           contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Rename")
                }
                TextButton(onClick = { borrar = true },
                           contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            val ultima = lugar.samples.lastOrNull()
            Text(ultima?.let { "%.2f hPa  ·  %s".format(it.hPa, cuandoCorto(it.epochMillis)) }
                     ?: "No readings yet",
                 style = MaterialTheme.typography.bodyMedium,
                 fontFamily = FontFamily.Monospace,
                 modifier = Modifier.testTag("sn-place-last"))

            PressureTrend.describe(lugar.samples)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.testTag("sn-place-trend"))
            }
            PressureTrend.warning(lugar.samples)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error,
                     modifier = Modifier.testTag("sn-place-warn"))
            }

            if (lugar.samples.size >= 2) {
                GraficoDePresion(lugar.samples,
                                 Modifier.fillMaxWidth().height(140.dp)
                                     .testTag("sn-place-chart"))
                Text("Drag across the chart to read a reading.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (lugar.samples.size == 1) {
                Text("One reading. Take another later and the change appears here.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTake, enabled = presionActual != null,
                       modifier = Modifier.testTag("sn-place-take")) { Text("Take reading") }
                Text("${lugar.samples.size} reading" + (if (lugar.samples.size == 1) "" else "s"),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f))
                if (lugar.samples.isNotEmpty()) {
                    BotonCopiar({ PressureReport.clipboardText(lugar, geoid = { la, lo -> Geoids.tagAt(la, lo) }) }, "sn-place-copy")
                }
            }
        }
    }

    if (renombrar) NombreDeLugar(
        titulo = "Rename place", inicial = lugar.name,
        onDone = { onRename(it); renombrar = false },
        onCancel = { renombrar = false })

    if (borrar) {
        AlertDialog(
            onDismissRequest = { borrar = false },
            modifier = Modifier.testTag("sn-place-delete"),
            title = { Text("Delete “${lugar.name}”?") },
            text = { Text("Its ${lugar.samples.size} reading(s) go with it. A pressure " +
                          "series cannot be measured again afterwards.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); borrar = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { borrar = false }) { Text("Keep") } })
    }
}

@Composable
private fun NombreDeLugar(titulo: String, inicial: String,
                          onDone: (String) -> Unit, onCancel: () -> Unit) {
    var texto by remember { mutableStateOf(inicial) }
    val foco = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("sn-place-dialog"),
        title = { Text(titulo) },
        text = {
            OutlinedTextField(
                value = texto, onValueChange = { texto = it },
                label = { Text("Name") },
                placeholder = { Text("Base camp") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(foco).testTag("sn-place-field"))
        },
        confirmButton = {
            TextButton(onClick = { onDone(texto.trim()) },
                       enabled = texto.isNotBlank(),
                       modifier = Modifier.testTag("sn-place-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

/**
 * Presion contra tiempo.
 *
 * EL EJE VERTICAL SE AJUSTA A LO QUE HAY, con un minimo de cuatro hectopascales de rango. Una
 * escala fija de 950 a 1050 dibujaria cualquier serie como una raya horizontal, y lo que se
 * viene a ver es justamente la pendiente. El minimo evita el problema contrario: con lecturas
 * casi iguales, el ruido de decimas se dibujaria como una montana rusa.
 */
@Composable
private fun GraficoDePresion(muestras: List<PressureSample>, modifier: Modifier = Modifier) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val linea = MaterialTheme.colorScheme.primary
    val fondo = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val medidor = androidx.compose.ui.text.rememberTextMeasurer()
    val estilo = MaterialTheme.typography.labelSmall.copy(color = ejes)
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density

    val orden = remember(muestras) { muestras.sortedBy { it.epochMillis } }
    if (orden.size < 2) return

    // EL CURSOR DA LA LECTURA CONCRETA. La curva dice la forma --si la caida se acelera-- y
    // eso ya es la mitad del dato; la otra mitad es "cuanto marcaba el martes por la tarde",
    // y para eso hay que poder senalar un punto.
    var cursorX by remember { mutableStateOf<Float?>(null) }
    val fondoCursor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val borde = MaterialTheme.colorScheme.onSurface

    Canvas(modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { cursorX = it.x },
            onDragEnd = { cursorX = null },
            onDragCancel = { cursorX = null },
            onDrag = { cambio, _ -> cursorX = cambio.position.x })
    }) {
        val izq = 46f * densidad
        val abajo = 16f * densidad
        val w = size.width - izq
        val h = size.height - abajo

        val pMin = orden.minOf { it.hPa }
        val pMax = orden.maxOf { it.hPa }
        val centro = (pMin + pMax) / 2
        val rango = maxOf(pMax - pMin, 4.0)
        val lo = centro - rango / 2 * 1.15
        val hi = centro + rango / 2 * 1.15

        val t0 = orden.first().epochMillis
        val t1 = orden.last().epochMillis
        val span = (t1 - t0).coerceAtLeast(1L)

        fun x(t: Long) = izq + ((t - t0).toDouble() / span).toFloat() * w
        fun y(p: Double) = (((hi - p) / (hi - lo)).toFloat()) * h

        drawRect(fondo, Offset(izq, 0f), androidx.compose.ui.geometry.Size(w, h))
        listOf(lo, centro, hi).forEach { p ->
            val yy = y(p)
            drawLine(ejes.copy(alpha = 0.25f), Offset(izq, yy), Offset(size.width, yy), 1f)
            val r = medidor.measure("%.0f".format(p), estilo)
            drawText(r, topLeft = Offset(0f, yy - r.size.height / 2f))
        }

        val p = androidx.compose.ui.graphics.Path()
        orden.forEachIndexed { i, m ->
            val px = x(m.epochMillis); val py = y(m.hPa)
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, linea, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        orden.forEach { m ->
            drawCircle(linea, radius = 4f, center = Offset(x(m.epochMillis), y(m.hPa)))
        }

        cursorX?.let { cx ->
            // La muestra MAS CERCANA, no una interpolacion: entre dos lecturas tomadas a
            // mano no se sabe que hizo la presion, y dibujar un valor intermedio seria
            // inventarselo. Se marca el punto real y se dice cuando fue.
            val cercana = orden.minByOrNull { kotlin.math.abs(x(it.epochMillis) - cx) }
            if (cercana != null) {
                val px = x(cercana.epochMillis)
                drawLine(borde, Offset(px, 0f), Offset(px, h), 2f)
                drawCircle(borde, radius = 6f, center = Offset(px, y(cercana.hPa)))
                val texto = "%.2f hPa  %s".format(cercana.hPa, cuandoCorto(cercana.epochMillis))
                val r = medidor.measure(texto, estilo.copy(color = borde))
                val tx = (px + 6f).coerceIn(izq, (size.width - r.size.width).coerceAtLeast(izq))
                drawRect(fondoCursor, Offset(tx - 4f, 2f),
                         androidx.compose.ui.geometry.Size(r.size.width + 8f,
                                                           r.size.height.toFloat()))
                drawText(r, topLeft = Offset(tx, 2f))
            }
        }

        // Cuanto tiempo abarca, que es lo que da sentido a la pendiente.
        val horas = span / 3_600_000.0
        val cuanto = if (horas < 48) "%.0f h".format(horas) else "%.0f days".format(horas / 24)
        val r = medidor.measure(cuanto, estilo)
        drawText(r, topLeft = Offset(size.width - r.size.width, h + 1f))
    }
}

private val CORTO = SimpleDateFormat("d MMM HH:mm", Locale.US)

private fun cuandoCorto(ms: Long): String = CORTO.format(Date(ms))
