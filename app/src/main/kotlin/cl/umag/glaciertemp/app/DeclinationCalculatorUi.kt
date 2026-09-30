package cl.umag.glaciertemp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.geo.CoordinateInput
import cl.umag.glaciertemp.core.geomag.AndroidMagneticModel
import cl.umag.glaciertemp.core.geomag.DecimalYear
import cl.umag.glaciertemp.core.geomag.MagneticModels
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Una fila de la tabla: un modelo y lo que dice, o por que no dice nada. */
private data class FilaModelo(
    val modelo: String,
    val declinacion: Double?,
    val tasa: Double?,
    val inclinacion: Double?,
    val nota: String?,
)

/**
 * La declinacion en cualquier sitio y fecha, con todos los modelos a la vez.
 *
 * PARA QUE SIRVE. Orientar un mapa o una foto aerea antiguos, cuyo norte magnetico es el
 * de su fecha (en Punta Arenas eran 17 grados en 1950 y seran 12 en 2030), o preparar una
 * campana en otro sitio. TODOS LOS MODELOS A LA VEZ, y el de Android con ellos: ver que
 * coinciden es la comprobacion; ver que no, dice cuanto error arrastra el telefono.
 *
 * FUERA DE VALIDEZ SE DICE, no se calcula: WMM2025 no vale en 1990 y ningun modelo vale en
 * 2035. El de Android si contesta siempre --extrapola-- y por eso lleva la marca al lado.
 */
@Composable
fun DeclinationCalculator(location: LocationSource?) {
    var abierto by rememberSaveable { mutableStateOf(false) }
    val hoy = remember { utc("yyyy-MM-dd").format(Date()) }
    var fecha by rememberSaveable { mutableStateOf(hoy) }
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }
    var alt by rememberSaveable { mutableStateOf("0") }
    var aviso by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clickable { abierto = !abierto }.padding(vertical = 4.dp)
                .testTag("sn-decl-calc"),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Declination at any place and date", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.weight(1f))
            Icon(if (abierto) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (!abierto) return@Column

        OutlinedTextField(fecha, { fecha = it }, singleLine = true,
            label = { Text("Date, UTC (YYYY-MM-DD or YYYY-MM-DD HH:MM)") },
            isError = DecimalYear.parseUtc(fecha) == null,
            modifier = Modifier.fillMaxWidth().testTag("sn-decl-date"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lat, { lat = it }, singleLine = true,
                label = { Text("Latitude") }, placeholder = { Text("53°09.6'S") },
                isError = lat.isNotBlank() && CoordinateInput.latitude(lat) == null,
                modifier = Modifier.weight(1f).testTag("sn-decl-lat"))
            OutlinedTextField(lon, { lon = it }, singleLine = true,
                label = { Text("Longitude") }, placeholder = { Text("70°54.6'W") },
                isError = lon.isNotBlank() && CoordinateInput.longitude(lon) == null,
                modifier = Modifier.weight(1f).testTag("sn-decl-lon"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(alt, { alt = it }, singleLine = true,
                label = { Text("Altitude (m)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = alt.isNotBlank() && alt.replace(',', '.').toDoubleOrNull() == null,
                modifier = Modifier.width(140.dp).testTag("sn-decl-alt"))
            OutlinedButton(enabled = location != null, onClick = {
                aviso = "Getting position…"
                scope.launch {
                    val loc = location ?: return@launch
                    val fix = runCatching { loc.lastKnownFix() }.getOrNull()
                        ?: runCatching { loc.freshFix(30_000L) }.getOrNull()
                    if (fix == null) { aviso = "No position available."; return@launch }
                    lat = "%.5f".format(Locale.ROOT, fix.latitude)
                    lon = "%.5f".format(Locale.ROOT, fix.longitude)
                    fix.altitudeMetres?.let { alt = "%.0f".format(Locale.ROOT, it) }
                    aviso = null
                }
            }, modifier = Modifier.testTag("sn-decl-here")) { Text("Current position") }
        }
        aviso?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        val t = DecimalYear.parseUtc(fecha)
        val la = CoordinateInput.latitude(lat)
        val lo = CoordinateInput.longitude(lon)
        val h = if (alt.isBlank()) 0.0 else alt.replace(',', '.').toDoubleOrNull()
        if (t == null || la == null || lo == null || h == null) {
            Text("Enter a valid date and position.", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val filas = calcular(la, lo, h, t)
        Tabla(filas)
        Text("Declination is positive east. Annual change in arc minutes per year. " +
             "IGRF-14 is valid 1900–2030 (definitive from 1945); WMM2025 is the navigation " +
             "model for 2025–2030. Android's own model is what other apps on this phone use.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        BotonCopiar({ informe(fecha, la, lo, h, filas) }, "sn-decl-copy")
    }
}

private fun calcular(lat: Double, lon: Double, alt: Double, t: Long): List<FilaModelo> {
    val ano = DecimalYear.fromEpochMillis(t)
    val propios = MagneticModels.all.map { m ->
        val r = MagneticModels.evaluate(m, lat, lon, alt, ano)
        FilaModelo(m.name, r?.declination, r?.declinationRate, r?.inclination,
                   if (r == null) "outside %.0f–%.0f".format(m.validFrom, m.validTo) else null)
    }
    // Android no da tasa: se mide por diferencia en un ano. Su modelo es lineal en el tiempo,
    // asi que la diferencia es exacta para lo que el contesta.
    val medio = 365L * 86_400_000L / 2
    val d = PlatformMagneticModel.declination(lat, lon, alt, t)
    val d1 = PlatformMagneticModel.declination(lat, lon, alt, t - medio)
    val d2 = PlatformMagneticModel.declination(lat, lon, alt, t + medio)
    var dd = d2 - d1
    if (dd > 180) dd -= 360 else if (dd < -180) dd += 360
    val nombre = PlatformMagneticModel.name
    val validez = AndroidMagneticModel.validity(nombre)
    val nota = when {
        nombre == null -> "unrecognised model"
        validez != null && ano !in validez -> "extrapolated (valid %.0f–%.0f)".format(validez.start, validez.endInclusive)
        else -> null
    }
    val android = FilaModelo("Android (${nombre ?: "?"})", d, dd,
                             PlatformMagneticModel.inclination(lat, lon, alt, t), nota)
    return propios + android
}

@Composable
private fun Tabla(filas: List<FilaModelo>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp),
           modifier = Modifier.testTag("sn-decl-table")) {
        Row {
            Celda("Model", 1.4f, true); Celda("Declination", 1f, true)
            Celda("Change/yr", 0.9f, true); Celda("Inclination", 0.9f, true)
        }
        for (f in filas) {
            Row {
                Celda(f.modelo, 1.4f)
                Celda(f.declinacion?.let { Declination.describe(it) } ?: "—", 1f)
                Celda(f.tasa?.let { tasaCorta(it) } ?: "—", 0.9f)
                Celda(f.inclinacion?.let { "%.1f°".format(it) } ?: "—", 0.9f)
            }
            f.nota?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RowScope.Celda(texto: String, peso: Float,
                                                              cabecera: Boolean = false) {
    Text(texto, style = if (cabecera) MaterialTheme.typography.labelMedium
                        else MaterialTheme.typography.bodyMedium,
         fontFamily = if (cabecera) null else FontFamily.Monospace,
         modifier = Modifier.weight(peso))
}

/** "5.3′ W", la variacion anual sin la coletilla "per year" que ya dice la cabecera. */
private fun tasaCorta(degPerYear: Double): String {
    val min = degPerYear * 60
    return if (abs(min) < 0.05) "0′" else "%.1f′ %s".format(abs(min), if (min > 0) "E" else "W")
}

private fun informe(fecha: String, lat: Double, lon: Double, alt: Double,
                    filas: List<FilaModelo>): String = buildString {
    appendLine("GlacioTools — magnetic declination")
    appendLine("Date (UTC): $fecha")
    appendLine("Position: %.5f, %.5f, %.0f m".format(Locale.ROOT, lat, lon, alt))
    for (f in filas) {
        append(f.modelo).append(": ")
        if (f.declinacion == null) append(f.nota ?: "—")
        else {
            append(Declination.describe(f.declinacion))
            f.tasa?.let { append(", annual change ").append(tasaCorta(it)) }
            f.inclinacion?.let { append(", inclination %.1f°".format(Locale.ROOT, it)) }
            f.nota?.let { append(" [").append(it).append("]") }
        }
        appendLine()
    }
}.trimEnd()

private fun utc(patron: String) = SimpleDateFormat(patron, Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
