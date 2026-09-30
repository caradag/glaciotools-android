package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.fieldbook.LengthUnit
import cl.umag.glaciertemp.core.geo.HeightVerdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

/**
 * Opciones generales de la app.
 *
 * Una seccion por tema, cada una con una frase de para que sirve: son decisiones que cambian
 * lo que se lee en todas las pantallas, y hay que poder tomarlas sabiendo que se elige.
 */
@Composable
fun SettingsScreen(location: LocationSource?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ToolBar("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AlturaDelTelefono(location)
            UnidadesDelAforo()
        }
    }
}

@Composable
internal fun Seccion(titulo: String, explicacion: String, tag: String,
                     contenido: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            Text(explicacion, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            contenido()
        }
    }
}

/** En que unidad se escriben las longitudes del aforo (ver LengthUnit). */
@Composable
private fun UnidadesDelAforo() {
    val u by AppSettings.gaugingLengthUnit.collectAsStateWithLifecycle()
    Seccion("Stream gauging lengths",
            "Unit for typing and reading the section width, bin interval, positions and depths " +
            "of a stream gauging. Velocities stay in m/s. Saved and exported data are always in " +
            "metres, so this can be changed at any time.",
            "st-gauging-unit") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (op in LengthUnit.entries) {
                FilterChip(selected = u == op, onClick = { AppSettings.setGaugingLengthUnit(op) },
                           label = { Text(op.label + " (" + op.suffix + ")") },
                           modifier = Modifier.testTag("st-gauging-unit-" + op.suffix))
            }
        }
    }
}

/**
 * Si la altura del GPS de ESTE telefono es de verdad elipsoidal (ver PhoneAltitude).
 *
 * La verificacion ocurre sola cada vez que la app usa el GPS; el boton solo sirve para no
 * tener que esperar a promediar un punto.
 */
@Composable
private fun AlturaDelTelefono(location: LocationSource?) {
    val estado by AppSettings.heightCheck.collectAsStateWithLifecycle()
    var comprobando by remember { mutableStateOf(false) }
    var progreso by remember { mutableStateOf("") }

    Seccion("This phone's GPS altitude",
            "Android should report heights above the WGS84 ellipsoid, but some phones report " +
            "heights above sea level instead. GlacioTools checks it against the phone's own GNSS " +
            "chip (its NMEA GGA sentences) whenever the GPS is on, and corrects it if needed.",
            "st-height-check") {
        val fecha = estado.checkedAtMillis?.let { DateFormat.getDateInstance().format(Date(it)) }
        Text(when (estado.verdict) {
                 HeightVerdict.ELLIPSOIDAL ->
                     "Verified: this phone reports ellipsoidal heights (checked $fecha; the chip " +
                     "used a geoid separation of %.1f m).".format(estado.chipSeparation ?: 0.0)
                 HeightVerdict.MSL ->
                     "This phone reports heights above sea level. GlacioTools adds the chip's own " +
                     "geoid separation to get ellipsoidal heights (checked $fecha)."
                 HeightVerdict.UNVERIFIED ->
                     "Not verified yet. It needs about ten 3D fixes with the GPS on, where the " +
                     "geoid separation is larger than 2 m (true in Patagonia). Some phones do not " +
                     "provide NMEA; then it cannot be verified."
             },
             style = MaterialTheme.typography.bodyMedium,
             color = if (estado.verdict == HeightVerdict.UNVERIFIED) MaterialTheme.colorScheme.error
                     else MaterialTheme.colorScheme.onSurface,
             modifier = Modifier.testTag("st-height-verdict"))
        if (location != null) {
            OutlinedButton(enabled = !comprobando, onClick = { comprobando = true },
                           modifier = Modifier.testTag("st-height-check-now")) {
                Text(if (comprobando) "Checking… $progreso" else "Check now")
            }
        }
    }

    // La comprobacion: GPS encendido hasta 2 minutos o hasta que haya veredicto.
    LaunchedEffect(comprobando) {
        if (!comprobando || location == null) return@LaunchedEffect
        withTimeoutOrNull(120_000) {
            coroutineScope {
                val gps = launch { location.samples(1000L).collect {} }
                while (!PhoneAltitude.confirmedThisSession) {
                    val (e, m) = PhoneAltitude.votes()
                    progreso = "($e ellipsoidal, $m sea level)"
                    delay(1000)
                }
                gps.cancel()
            }
        }
        comprobando = false
        progreso = ""
    }
}
