package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.geo.CoordinateInput
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** Lo que dice un modelo en un punto, o por que no dice nada. */
private data class FilaGeoide(val model: GeoidModel, val n: Double?, val estado: String)

/**
 * GPS tools > Geoid: la ondulacion N de todos los geoides en un punto.
 *
 * TODOS A LA VEZ porque la pregunta de fondo es "cuanto importa cual elija aqui": en
 * Patagonia EGM96 y EGM2008 difieren de decimetros a siete metros segun el sitio. Y si el
 * propio chip GNSS del telefono dijo que separacion usa (NMEA GGA), se pone al lado: es la
 * forma de saber sobre que geoide esta la altura "sobre el mar" que muestran otras apps.
 */
@Composable
fun GeoidScreen(location: LocationSource?) {
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }
    var alt by rememberSaveable { mutableStateOf("") }
    var aviso by rememberSaveable { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }       // recalcular tras descargar
    val scope = rememberCoroutineScope()

    fun usarPosicion(fresca: Boolean) {
        aviso = "Getting position…"
        scope.launch {
            val loc = location ?: return@launch
            val fix = (if (fresca) null else runCatching { loc.lastKnownFix() }.getOrNull())
                ?: runCatching { loc.freshFix(30_000L) }.getOrNull()
            if (fix == null) { aviso = "No position available."; return@launch }
            lat = "%.6f".format(Locale.ROOT, fix.latitude)
            lon = "%.6f".format(Locale.ROOT, fix.longitude)
            alt = fix.altitudeMetres?.let { "%.1f".format(Locale.ROOT, it) } ?: ""
            aviso = null
        }
    }
    LaunchedEffect(location) { if (lat.isBlank() && location != null) usarPosicion(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Geoid undulation N: how far the geoid is above the WGS84 ellipsoid. " +
             "Height above the geoid H = h − N, where h is the ellipsoidal (GNSS) height.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lat, { lat = it }, singleLine = true, label = { Text("Latitude") },
                isError = lat.isNotBlank() && CoordinateInput.latitude(lat) == null,
                modifier = Modifier.weight(1f).testTag("geoid-lat"))
            OutlinedTextField(lon, { lon = it }, singleLine = true, label = { Text("Longitude") },
                isError = lon.isNotBlank() && CoordinateInput.longitude(lon) == null,
                modifier = Modifier.weight(1f).testTag("geoid-lon"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(alt, { alt = it }, singleLine = true,
                label = { Text("Ellipsoidal h (m)") }, placeholder = { Text("optional") },
                isError = alt.isNotBlank() && alt.replace(',', '.').toDoubleOrNull() == null,
                modifier = Modifier.width(170.dp).testTag("geoid-h"))
            OutlinedButton(enabled = location != null, onClick = { usarPosicion(true) },
                           modifier = Modifier.testTag("geoid-here")) { Text("Current position") }
        }
        aviso?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        val la = CoordinateInput.latitude(lat)
        val lo = CoordinateInput.longitude(lon)
        val h = alt.replace(',', '.').toDoubleOrNull()
        if (la == null || lo == null) {
            Text("Enter a position, or use the current one.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val filas = remember(la, lo, version) {
            GeoidModel.entries.map { m ->
                val n = Geoids.undulation(m, la, lo)
                FilaGeoide(m, n, when {
                    m.builtIn -> "built in"
                    n != null -> "downloaded"
                    else -> "not downloaded"
                })
            }
        }
        Tabla(filas, h)

        // Cuanto importa la eleccion aqui: diferencias contra EGM2008 (o EGM96 si no esta).
        val base = filas.firstOrNull { it.model == GeoidModel.EGM2008 && it.n != null }
            ?: filas.first { it.model == GeoidModel.EGM96 }
        val difs = filas.filter { it !== base && it.n != null }
        if (difs.isNotEmpty() && base.n != null) {
            Text(difs.joinToString("   ") { "${it.model.title} − ${base.model.title} = %+.2f m".format(it.n!! - base.n) },
                 style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                 modifier = Modifier.testTag("geoid-diffs"))
        }

        ChipDelTelefono(filas)

        if (filas.any { !it.model.builtIn && it.n == null }) {
            DescargarZona(la, lo) { version++ }
        }
        BotonCopiar({ informe(la, lo, h, filas) }, "geoid-copy")
    }
}

@Composable
private fun Tabla(filas: List<FilaGeoide>, h: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("geoid-table")) {
        Row {
            Celda("Model", 1.2f, true); Celda("N (m)", 1f, true)
            if (h != null) Celda("H (m)", 1f, true)
            Celda("", 1.3f, true)
        }
        for (f in filas) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Celda(f.model.title, 1.2f)
                Celda(f.n?.let { "%.3f".format(it) } ?: "—", 1f, tag = "geoid-n-" + f.model.id)
                if (h != null) Celda(f.n?.let { "%.2f".format(h - it) } ?: "—", 1f, tag = "geoid-h-" + f.model.id)
                Text(f.estado, style = MaterialTheme.typography.bodySmall,
                     color = if (f.n == null) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun RowScope.Celda(t: String, peso: Float, cabecera: Boolean = false, tag: String? = null) {
    Text(t, style = if (cabecera) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
         fontFamily = if (cabecera) null else FontFamily.Monospace,
         modifier = Modifier.weight(peso).then(tag?.let { Modifier.testTag(it) } ?: Modifier))
}

/** La separacion que uso el chip GNSS en su ultima GGA, y a que modelo se parece. */
@Composable
private fun ChipDelTelefono(filas: List<FilaGeoide>) {
    val sep = PhoneAltitude.lastChipSeparation() ?: return
    val cercano = filas.filter { it.n != null }.minByOrNull { abs(it.n!! - sep) }
    Text("This phone's GNSS chip last used N = %.2f m for its sea-level height".format(sep) +
         (cercano?.let { ", closest to ${it.model.title} (%+.2f m).".format(sep - it.n!!) } ?: "."),
         style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("geoid-chip"))
}

/** Hueco para la descarga de teselas (fase E); por ahora remite a Settings. */
@Composable
internal fun DescargarZona(lat: Double, lon: Double, onDone: () -> Unit) {
    Text("Models marked 'not downloaded' can be downloaded for this area in Settings.",
         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun informe(lat: Double, lon: Double, h: Double?, filas: List<FilaGeoide>): String = buildString {
    appendLine("GlacioTools — geoid undulation")
    appendLine("Position: %.6f, %.6f".format(Locale.ROOT, lat, lon) +
               (h?.let { "  ellipsoidal h = %.2f m".format(Locale.ROOT, it) } ?: ""))
    for (f in filas) {
        append(f.model.title).append(": ")
        if (f.n == null) append("not available") else {
            append("N = %.3f m".format(Locale.ROOT, f.n))
            if (h != null) append(", H = %.2f m".format(Locale.ROOT, h - f.n))
        }
        appendLine()
    }
    PhoneAltitude.lastChipSeparation()?.let { appendLine("Phone GNSS chip: N = %.2f m".format(Locale.ROOT, it)) }
}.trimEnd()
