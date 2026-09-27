package cl.umag.glaciertemp.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Lo que el sistema declara sobre la calibracion de la brujula.
 *
 * POR QUE IMPORTA MAS DE LO QUE PARECE. Una brujula descalibrada no desplaza el azimut una
 * cantidad fija: lo DEFORMA de manera distinta segun hacia donde se mire, porque el hierro
 * duro es un vector fijo en el aparato que se suma al campo terrestre. Una rotacion rigida
 * el apantallamiento la perdona --es una integral sobre todo el azimut-- pero una
 * deformacion no: mueve los cerros de sitio unos respecto de otros.
 *
 * Android no ofrece forma de calibrar ni de forzar una calibracion. Lo unico que expone es
 * esta precision declarada, que hasta ahora la app ignoraba: los `onAccuracyChanged` estaban
 * vacios y un barrido entero podia salir torcido sin que nada lo dijera.
 */
enum class CompassAccuracy(val label: String) {
    UNKNOWN("unknown"),
    UNRELIABLE("unreliable"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    /** Por debajo de MEDIUM no conviene levantar un horizonte. */
    fun isPoor(): Boolean = this == UNRELIABLE || this == LOW

    companion object {
        fun of(value: Int): CompassAccuracy = when (value) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> UNRELIABLE
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            else -> UNKNOWN
        }
    }
}

/**
 * La precision de la brujula, EN VIVO.
 *
 * Se sigue actualizando mientras se mueve el telefono, que es lo que permite ver el efecto de
 * hacer el ocho en vez de tener que confiar en que sirvio de algo.
 */
@Composable
fun rememberCompassAccuracy(): CompassAccuracy {
    val ctx = LocalContext.current
    var precision by remember { mutableStateOf(CompassAccuracy.UNKNOWN) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val oyente = object : SensorEventListener {
            // La precision tambien viaja en cada evento, no solo en onAccuracyChanged: hay
            // aparatos que nunca llaman al segundo.
            override fun onSensorChanged(e: SensorEvent) {
                precision = CompassAccuracy.of(e.accuracy)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {
                precision = CompassAccuracy.of(a)
            }
        }
        sm.registerListener(oyente, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(oyente) }
    }
    return precision
}

/** El color de cada estado: verde solo cuando de verdad se puede medir. */
@Composable
fun colorDePrecision(p: CompassAccuracy): Color = when (p) {
    CompassAccuracy.HIGH -> Color(0xFF4CAF50)
    CompassAccuracy.MEDIUM -> Color(0xFFFF9800)
    CompassAccuracy.LOW, CompassAccuracy.UNRELIABLE -> MaterialTheme.colorScheme.error
    CompassAccuracy.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** La linea de estado con su icono de informacion. */
@Composable
fun FilaDePrecision(p: CompassAccuracy, modifier: Modifier = Modifier) {
    var explicar by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Compass accuracy: ${p.label}",
             style = MaterialTheme.typography.bodySmall,
             color = colorDePrecision(p),
             modifier = Modifier.testTag("compass-accuracy"))
        TextButton(onClick = { explicar = true },
                   contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                   modifier = Modifier.testTag("compass-accuracy-info")) { Text("What is this?") }
    }
    if (explicar) DialogoDeCalibracion(p) { explicar = false }
}

/**
 * Que significa y como mejorarlo.
 *
 * LA PRECISION SE ACTUALIZA MIENTRAS EL CUADRO ESTA ABIERTO. Haciendo el ocho con el cuadro
 * delante se ve subir el estado, que es la unica forma de saber que la maniobra sirvio: sin
 * eso, uno mueve el telefono en el aire y se queda sin saber si cambio algo.
 */
@Composable
fun DialogoDeCalibracion(p: CompassAccuracy, onClose: () -> Unit) {
    val enVivo = rememberCompassAccuracy()
    val mostrada = if (enVivo == CompassAccuracy.UNKNOWN) p else enVivo
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("compass-calib-dialog"),
        title = { Text("Compass accuracy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Now: ${mostrada.label}",
                     style = MaterialTheme.typography.titleMedium,
                     color = colorDePrecision(mostrada),
                     modifier = Modifier.testTag("compass-calib-live"))
                Text("This is the phone's own estimate of how well its magnetometer is " +
                     "calibrated. Android gives no way to calibrate it on demand: the " +
                     "system works the correction out on its own from the orientations it " +
                     "happens to see.")
                Text("To give it something to work with, move the phone in a figure of " +
                     "eight, turning your wrist so the screen faces many different " +
                     "directions, for five or ten seconds. Watch the line above change.",
                     style = MaterialTheme.typography.bodyMedium)
                Text("Keep away from anything magnetic — a hammer, ski poles, the sled, a " +
                     "case with a magnetic clasp, the vehicle. Those bend the field itself, " +
                     "and no calibration can undo that.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Why it matters: a badly calibrated compass does not shift every " +
                     "bearing by the same amount, it distorts them differently depending on " +
                     "which way you face. A horizon swept like that has its ridges in the " +
                     "wrong places relative to each other.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onClose,
                       modifier = Modifier.testTag("compass-calib-close")) { Text("Close") }
        })
}
