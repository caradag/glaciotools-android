package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.BuildConfig

/** Que herramienta esta abierta. */
private enum class Herramienta { INICIO, PLACA, GPS }

/**
 * La app entera: una pantalla de inicio que reparte, y las herramientas.
 *
 * GlacioTools deja de ser "la app de la placa" aqui. La herramienta de GPS no habla con
 * ningun aparato y no tiene por que estar detras de una conexion; ponerla en una pestana
 * junto a Device y Terminal habria obligado a conectar algo antes de poder usarla.
 */
@Composable
fun GlacioToolsApp(device: DeviceViewModel, gps: GpsViewModel) {
    // Con un Saver explicito y no el automatico: lo que se guarda en el Bundle al girar la
    // pantalla es el NOMBRE, que es texto y no depende de en que orden queden las constantes
    // el dia que se anada una herramienta en medio.
    var donde by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver(
        save = { it.name }, restore = { Herramienta.valueOf(it) },
    )) { mutableStateOf(Herramienta.INICIO) }

    // El boton atras del sistema vuelve al repartidor en vez de cerrar la app. Sin esto,
    // salir de una herramienta y salir de GlacioTools son el mismo gesto, y en terreno eso
    // significa perder una sesion de medida por un roce.
    androidx.activity.compose.BackHandler(enabled = donde != Herramienta.INICIO) {
        donde = Herramienta.INICIO
    }

    when (donde) {
        Herramienta.INICIO -> HomeScreen(
            onDevice = { donde = Herramienta.PLACA },
            onGps = { donde = Herramienta.GPS })
        Herramienta.PLACA -> Column(Modifier.fillMaxSize()) {
            GlacierTempApp(device, onBack = { donde = Herramienta.INICIO })
        }
        Herramienta.GPS -> Column(Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            ToolBar("GPS tools", onBack = { donde = Herramienta.INICIO })
            GpsToolScreen(gps)
        }
    }
}

/** La cabecera de una herramienta: volver, y el nombre de donde se esta. */
@Composable
fun ToolBar(titulo: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("tool-back")) {
            Text("‹ Tools")
        }
        Text(titulo, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HomeScreen(onDevice: () -> Unit, onGps: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val estilo = MaterialTheme.typography.headlineMedium
        val oscuro = androidx.compose.foundation.isSystemInDarkTheme()
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (oscuro) WordmarkBlueDark else WordmarkBlue)) {
                    append("Glacio")
                }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) {
                    append("Tools")
                }
                withStyle(SpanStyle(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    fontSize = estilo.fontSize * 0.45f,
                    fontWeight = FontWeight.Normal,
                )) { append("  v${BuildConfig.VERSION_NAME}") }
            },
            style = estilo,
            modifier = Modifier.padding(top = with(LocalDensity.current) {
                (estilo.fontSize.toPx() * 0.25f).toDp()
            }),
        )
        Text("Field tools for glaciology.",
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(8.dp))

        ToolCard(
            titulo = "Connect to a device",
            detalle = "Configure a GlacierTemp logger, download its data and watch its " +
                      "sensors live. Over USB cable or Bluetooth.",
            tag = "tool-device", onClick = onDevice)

        ToolCard(
            titulo = "GPS tools",
            detalle = "Average GNSS fixes to pin down a position more precisely than a " +
                      "single reading allows. Export as CSV or GPX.",
            tag = "tool-gps", onClick = onGps)
    }
}

@Composable
private fun ToolCard(titulo: String, detalle: String, tag: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            Text(detalle, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
