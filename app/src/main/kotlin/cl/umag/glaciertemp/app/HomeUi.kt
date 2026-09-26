package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
private enum class Herramienta { INICIO, PLACA, GPS, LIBRETA, DIARIO, SENSORES }

/**
 * La app entera: una pantalla de inicio que reparte, y las herramientas.
 *
 * GlacioTools deja de ser "la app de la placa" aqui. La herramienta de GPS no habla con
 * ningun aparato y no tiene por que estar detras de una conexion; ponerla en una pestana
 * junto a Device y Terminal habria obligado a conectar algo antes de poder usarla.
 */
@Composable
fun GlacioToolsApp(device: DeviceViewModel, gps: GpsViewModel,
                   fieldbook: FieldbookViewModel, almanac: AlmanacViewModel,
                   journal: JournalViewModel) {
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

    // EL RECORDATORIO VA POR ENCIMA DE TODO, no dentro de la libreta. Su razon de ser es
    // que alguien que no abrio la libreta ayer se entere hoy; ponerlo dentro de la propia
    // libreta lo dejaria justo donde no lo va a ver quien lo necesita.
    val sDiario by journal.state.collectAsStateWithLifecycle()
    LaunchedEffect(donde) { journal.refresh() }

    Column(Modifier.fillMaxSize()) {
        sDiario.reminderDay?.let { dia ->
            RecordatorioDeDiario(
                dia = dia,
                onOpen = { journal.create(journal.middayOf(dia)); donde = Herramienta.DIARIO },
                onDismiss = { journal.dismissReminder() })
        }

    when (donde) {
        Herramienta.INICIO -> HomeScreen(
            onDevice = { donde = Herramienta.PLACA },
            onGps = { donde = Herramienta.GPS },
            onFieldbook = { donde = Herramienta.LIBRETA },
            onSensors = { donde = Herramienta.SENSORES })
        Herramienta.PLACA -> Column(Modifier.fillMaxSize()) {
            GlacierTempApp(device, onBack = { donde = Herramienta.INICIO })
        }
        Herramienta.GPS -> Column(Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            GpsToolScreen(gps, almanac, onBack = { donde = Herramienta.INICIO })
        }
        Herramienta.LIBRETA -> Column(Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            FieldbookScreen(fieldbook,
                            onJournal = { donde = Herramienta.DIARIO },
                            onBack = { donde = Herramienta.INICIO })
        }
        Herramienta.DIARIO -> Column(Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            JournalScreen(journal, onBack = { donde = Herramienta.LIBRETA })
        }
        Herramienta.SENSORES -> Column(Modifier.fillMaxSize()
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            SensorsScreen(onBack = { donde = Herramienta.INICIO })
        }
    }
    }
}

/**
 * El aviso de que falta el diario de ayer.
 *
 * NO DICE QUE FALTE ALGO, dice que quiza se olvido. Un dia de campana puede no tener nada
 * que contar --se espero a que dejara de llover, se viajo-- y presentarlo como una
 * obligacion incumplida convertiria una ayuda en un reproche. De ahi que se pueda descartar.
 *
 * Y lleva la FECHA escrita: "falta el diario de ayer" obliga a calcular que dia era ayer,
 * que es justo lo que uno no tiene claro despues de una semana en el hielo.
 */
@Composable
private fun RecordatorioDeDiario(dia: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("No journal entry for ${fechaCorta(dia)}",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onTertiaryContainer,
                     modifier = Modifier.testTag("jr-reminder"))
                Text("Write it now while you still remember.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            TextButton(onClick = onOpen, modifier = Modifier.testTag("jr-reminder-open")) {
                Text("Write")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("jr-reminder-dismiss")) {
                Text("Hide")
            }
        }
    }
}

private fun fechaCorta(dayKey: String): String {
    val p = dayKey.split("-").mapNotNull { it.toIntOrNull() }
    if (p.size != 3) return dayKey
    val c = java.util.Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2], 12, 0, 0) }
    return java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.US).format(c.time)
}

/**
 * La cabecera de una herramienta: volver, el nombre de donde se esta, y sus acciones.
 *
 * UNA SOLA PARA TODAS. Antes cada herramienta resolvia su cabecera a su manera: GPS y la
 * libreta usaban esta barra, la pantalla del aparato tenia la suya con el logotipo --de modo
 * que arriba solo ponia "Tools", sin decir en que herramienta estabas-- y la libreta ademas
 * repetia su nombre debajo, en un titulo que decia lo mismo que la barra. Tres formas de
 * encabezar tres pantallas de la misma app.
 *
 * Las acciones van AQUI, a la derecha del nombre, y no en una fila propia: en un telefono
 * cada fila de cabecera es una franja de pantalla que no muestra datos, y en la libreta esa
 * franja empujaba la primera anotacion fuera de la vista.
 *
 * El logotipo se queda en la pantalla de inicio, que es donde significa algo. Repetido en
 * cada herramienta solo gasta la altura que necesita el trabajo.
 */
@Composable
fun ToolBar(titulo: String, onBack: () -> Unit,
            atras: String = "Tools",
            acciones: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("tool-back")) {
            Text("‹ $atras", style = MaterialTheme.typography.titleMedium)
        }
        Text(titulo, style = MaterialTheme.typography.titleLarge,
             modifier = Modifier.weight(1f).testTag("tool-title"))
        acciones()
    }
}

@Composable
private fun HomeScreen(onDevice: () -> Unit, onGps: () -> Unit,
                       onFieldbook: () -> Unit, onSensors: () -> Unit) {
    var info by rememberSaveable { mutableStateOf(false) }
    // SE DESPLAZA. Con cuatro herramientas la lista ya pasa del alto de un telefono, y una
    // tarjeta cortada por abajo se lee como que no hay nada mas.
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
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
            detalle = "Average GNSS fixes for a position better than a single reading. " +
                      "GPS time against the phone clock, with an audible countdown for " +
                      "setting other instruments. A planner showing satellites per " +
                      "constellation through the day.",
            tag = "tool-gps", onClick = onGps)

        ToolCard(
            titulo = "Fieldbook",
            detalle = "Field notes, stake readings with their ablation rate, GNSS points " +
                      "with a timer, and dendro samples — with photos, audio and " +
                      "coordinates. A campaign journal day by day, and search across " +
                      "everything, archived campaigns included.",
            tag = "tool-fieldbook", onClick = onFieldbook)

        ToolCard(
            titulo = "Onboard sensors",
            detalle = "What the phone itself can measure: tilt, compass, pressure and " +
                      "light — including an albedo measurement, facing up then down.",
            tag = "tool-sensors", onClick = onSensors)

        // Abajo y discreto: se consulta una vez, no se usa. Pero tiene que estar, porque es
        // lo unico que dice a quien escribir cuando algo falla en terreno.
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { info = true }, modifier = Modifier.testTag("home-info")) {
                Text("Info")
            }
        }
    }

    if (info) InfoDialog(onDismiss = { info = false })
}

/**
 * Quien hizo esto y a quien escribir.
 *
 * El correo es el motivo de que exista el cuadro: una app que se usa sobre un glaciar y
 * falla lejos de todo no sirve de nada si no dice como avisar.
 */
@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("info-dialog"),
        title = { Text("GlacioTools  v${BuildConfig.VERSION_NAME}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Field tools for glaciology, made by GlacioTools.")
                Text("Led by Camilo Rada, built with the help of Claude Code.")
                Text("Contact: camilo@rada.cl", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss,
                       modifier = Modifier.testTag("info-close")) { Text("Close") }
        },
    )
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
