package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.gnss.AlmanacFreshness
import cl.umag.glaciertemp.core.gnss.Constellation
import cl.umag.glaciertemp.core.gnss.Freshness

/**
 * El planificador: cuantos satelites habra a cada hora, para elegir cuando medir.
 *
 * De momento, la parte del almanaque. La grafica viene detras.
 */
@Composable
fun PlannerScreen(vm: AlmanacViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ahora = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Orbit data", style = MaterialTheme.typography.titleMedium,
                         modifier = Modifier.weight(1f))
                    if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                // LA ANTIGUEDAD, SIEMPRE A LA VISTA. Es el dato que decide si uno se fia de
                // la grafica, y esconderlo detras de un menu seria presentar como bueno algo
                // que puede llevar meses guardado.
                Text(AlmanacFreshness.describeAge(s.downloadedAtMillis, ahora),
                     style = MaterialTheme.typography.bodyLarge,
                     modifier = Modifier.testTag("planner-age"))

                Text(
                    when (s.freshness) {
                        Freshness.MISSING ->
                            "Without orbit data the planner cannot predict anything. " +
                            "Connect to a network once and it downloads by itself."
                        Freshness.FRESH ->
                            "Up to date. It refreshes by itself when it passes " +
                            "${AlmanacFreshness.FRESH_DAYS} days and there is a network."
                        Freshness.AGING ->
                            "Still good. Orbit data stays usable for months at the accuracy " +
                            "this tool needs; it will refresh next time there is a network."
                        Freshness.STALE ->
                            "Older than ${AlmanacFreshness.STALE_DAYS} days. It still works " +
                            "for choosing hours, but refresh it when you get a network."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.freshness == Freshness.MISSING || s.freshness == Freshness.STALE)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("planner-freshness"))

                if (s.tles.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Constellation.entries.forEach { c ->
                        Row {
                            Text(nombre(c), Modifier.weight(1f),
                                 style = MaterialTheme.typography.bodyMedium)
                            Text("${s.counts[c] ?: 0} satellites",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 modifier = Modifier.testTag("planner-count-${c.name.lowercase()}"))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.refreshNow() }, enabled = !s.busy,
                           modifier = Modifier.testTag("planner-refresh")) {
                        Text("Update now")
                    }
                }
            }
        }

        s.note?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         modifier = Modifier.testTag("planner-note"))
                    TextButton(onClick = { vm.clearNote() }) { Text("Dismiss") }
                }
            }
        }

        Text("About 24 kB, downloaded once every few weeks. At the accuracy this tool needs " +
             "— a couple of degrees, which is about four minutes of error in when a " +
             "satellite rises — orbit data stays good for months.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun nombre(c: Constellation) = when (c) {
    Constellation.GPS -> "GPS"
    Constellation.GLONASS -> "GLONASS"
    Constellation.GALILEO -> "Galileo"
    Constellation.BEIDOU -> "BeiDou"
}
