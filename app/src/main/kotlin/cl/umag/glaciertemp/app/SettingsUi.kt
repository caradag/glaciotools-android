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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.fieldbook.LengthUnit
import cl.umag.glaciertemp.core.geo.HeightReference
import cl.umag.glaciertemp.core.geo.HeightVerdict
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
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
            ReferenciaDeAlturas(location)
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

/**
 * En que referencia se muestran las alturas. GPS Average muestra SIEMPRE la elipsoidal y
 * ademas la del geoide elegido; lo guardado es siempre elipsoidal.
 */
@Composable
private fun ReferenciaDeAlturas(location: LocationSource?) {
    val ref by AppSettings.heightReference.collectAsStateWithLifecycle()
    val modelo by AppSettings.geoidModel.collectAsStateWithLifecycle()
    val geoidal = ref is HeightReference.Orthometric
    // Donde se esta, para decir si el geoide elegido tiene datos aqui.
    var aqui by remember { mutableStateOf<cl.umag.glaciertemp.core.GeoFix?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(location) { aqui = runCatching { location?.lastKnownFix() }.getOrNull() }

    Seccion("Height reference",
            "How altitudes are shown and which extra columns go into exports. Ellipsoidal is what " +
            "GNSS measures (above the WGS84 ellipsoid). A geoid gives heights close to 'above sea " +
            "level'. Saved data always keeps the ellipsoidal height, so this can be changed at any time.",
            "st-height-reference") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !geoidal, onClick = { AppSettings.setHeightReference(false) },
                       label = { Text("Ellipsoidal (WGS84)") }, modifier = Modifier.testTag("st-height-ellipsoidal"))
            FilterChip(selected = geoidal, onClick = { AppSettings.setHeightReference(true) },
                       label = { Text("Geoid") }, modifier = Modifier.testTag("st-height-geoid"))
        }
        if (geoidal) {
            for (m in GeoidModel.offered) {
                val disponible = remember(aqui, version) { aqui?.let { Geoids.available(m, it.latitude, it.longitude) } }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(
                        selected = modelo == m, onClick = { AppSettings.setHeightReference(true, m) },
                        modifier = Modifier.testTag("st-geoid-" + m.id))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = MaterialTheme.typography.bodyLarge)
                        Text(m.description, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when {
                                 m.builtIn -> "Built into the app."
                                 disponible == true -> "Downloaded for the current area."
                                 disponible == false -> "Not downloaded for the current area."
                                 else -> "Current position unknown."
                             },
                             style = MaterialTheme.typography.bodySmall,
                             color = if (disponible == false) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.testTag("st-geoid-status-" + m.id))
                        if (!m.builtIn) {
                            var enDisco by remember(m, version) { mutableStateOf(Geoids.store?.onDisk(m)) }
                            val (n, bytes) = enDisco ?: (0 to 0L)
                            if (disponible == false) aqui?.let { f ->
                                DescargaDeModelo(m, f.latitude, f.longitude) { version++ }
                            }
                            if (n > 0) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("%d tile(s), %.1f MB on this phone".format(n, bytes / 1e6),
                                     style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = {
                                    Geoids.store?.delete(m); version++
                                }, modifier = Modifier.testTag("st-geoid-delete-" + m.id)) { Text("Delete") }
                            }
                        }
                    }
                }
            }
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
