package cl.umag.glaciertemp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * La hora del GPS frente a la del telefono, para poner en hora los aparatos de terreno.
 *
 * PARA QUE SIRVE. Varios instrumentos --el registrador, la camara, la libreta en papel-- se
 * anotan con su propia hora, y despues los datos hay que cruzarlos. Si cada aparato va por
 * su cuenta, cruzarlos es adivinar. La referencia es el GPS porque la lleva un reloj atomico
 * y no se va con el frio ni con la bateria baja, que es exactamente lo que le pasa a un
 * reloj de cuarzo a -10 grados.
 *
 * POR QUE LA HORA DEL GPS NO ES LA DEL TELEFONO. Android ajusta su reloj por red cuando
 * puede, y en terreno no puede: puede llevar semanas de deriva sin que nadie lo note. La
 * marca de tiempo de una posicion GNSS, en cambio, viene del propio sistema de satelites.
 *
 * LA CUENTA ATRAS SONORA es lo que hace la herramienta utilizable con una sola persona: para
 * poner en hora un aparato hay que tener las dos manos en el, mirando SU pantalla, no la del
 * telefono. Los pitidos dicen el segundo sin que haya que mirar.
 */
@SuppressLint("MissingPermission")
@Composable
fun GpsTimeScreen() {
    val ctx = LocalContext.current
    var permiso by rememberSaveable { mutableStateOf(tienePermisoUbicacion(ctx)) }
    val pedir = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { permiso = it }

    var horaGps by remember { mutableStateOf<Long?>(null) }
    var recibidaEn by remember { mutableStateOf(0L) }
    var satelites by remember { mutableStateOf<Int?>(null) }
    var sonar by rememberSaveable { mutableStateOf(false) }
    // POR DEFECTO LA LOCAL. Es la que llevan el reloj de pulsera, la camara y la libreta de
    // papel, o sea contra la que se compara sin tener que restar nada. UTC esta a un toque
    // porque es la que se escribe en los ficheros y la que entienden todos los aparatos.
    var utc by rememberSaveable { mutableStateOf(false) }

    // Reloj de pared a 10 Hz: con uno de 1 Hz el segundo mostrado puede ir hasta un segundo
    // atrasado respecto al real, que en una herramienta cuyo proposito es la sincronizacion
    // es el unico error que no se puede permitir.
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { ahora = System.currentTimeMillis(); kotlinx.coroutines.delay(100) }
    }

    DisposableEffect(permiso) {
        if (!permiso) return@DisposableEffect onDispose { }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@DisposableEffect onDispose { }

        val oyente = LocationListener { loc: Location ->
            // getTime() de una posicion GNSS es hora UTC del sistema de satelites, no la del
            // telefono. Se guarda junto al instante en que llego para poder envejecerla.
            horaGps = loc.time
            recibidaEn = System.currentTimeMillis()
        }
        val cielo = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(st: GnssStatus) {
                satelites = (0 until st.satelliteCount).count { st.usedInFix(it) }
            }
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, oyente)
            lm.registerGnssStatusCallback(cielo, null)
        }
        onDispose {
            runCatching { lm.removeUpdates(oyente) }
            runCatching { lm.unregisterGnssStatusCallback(cielo) }
        }
    }

    // La hora del GPS extrapolada hasta ahora: el receptor la entrega una vez por segundo y
    // entre una y otra hay que seguir contando, o la pantalla daria saltos.
    val gpsAhora = horaGps?.let { it + (ahora - recibidaEn) }
    val desfase = gpsAhora?.let { it - ahora }

    // --------------------------- la cuenta atras sonora ---------------------------
    val beeper = remember { GpsTimeBeeper() }
    var ultimoSegundo by remember { mutableIntStateOf(-1) }
    LaunchedEffect(sonar, gpsAhora != null) {
        if (!sonar) { ultimoSegundo = -1; return@LaunchedEffect }
        while (true) {
            val base = horaGps?.let { it + (System.currentTimeMillis() - recibidaEn) }
                ?: System.currentTimeMillis()
            val seg = ((base / 1000) % 60).toInt()
            if (seg != ultimoSegundo) {
                ultimoSegundo = seg
                cl.umag.glaciertemp.core.BeepPattern.at(seg)?.let { t ->
                    repeat(t.repeticiones) {
                        beeper.beep(t.hz, t.ms)
                        if (t.repeticiones > 1) kotlinx.coroutines.delay((t.ms + 60).toLong())
                    }
                }
            }
            kotlinx.coroutines.delay(20)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (!permiso) {
            Text("GPS time needs the location permission.",
                 style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { pedir.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                   modifier = Modifier.testTag("gpstime-permission")) { Text("Allow") }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !utc, onClick = { utc = false },
                       label = { Text("Local") },
                       modifier = Modifier.testTag("gpstime-local"))
            FilterChip(selected = utc, onClick = { utc = true },
                       label = { Text("UTC") },
                       modifier = Modifier.testTag("gpstime-utc"))
        }

        // La zona va ESCRITA en el rotulo, no solo implicita en el conmutador: una hora sin
        // zona al lado es justo el dato que se copia mal a la libreta y no hay forma de
        // recuperar despues.
        val zona = if (utc) "UTC" else "local, " + desplazamientoLocal()
        Reloj("GPS time ($zona)", gpsAhora, utc, "gpstime-gps")
        Reloj("Phone time ($zona)", ahora, utc, "gpstime-phone")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Phone clock", style = MaterialTheme.typography.labelMedium)
                Text(desfase?.let { cl.umag.glaciertemp.core.ClockOffset.describe(it) } ?: "—",
                     style = MaterialTheme.typography.headlineSmall,
                     color = when {
                         desfase == null -> MaterialTheme.colorScheme.onSurfaceVariant
                         abs(desfase) < cl.umag.glaciertemp.core.ClockOffset.EN_HORA_MS ->
                             MaterialTheme.colorScheme.onSurface
                         else -> MaterialTheme.colorScheme.error
                     },
                     modifier = Modifier.testTag("gpstime-offset"))
                Text(if (desfase == null) "Waiting for a GNSS fix — this needs open sky."
                     else "Set the other device to the GPS clock above.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                satelites?.let {
                    Text("$it satellite(s) used in the fix",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.testTag("gpstime-sats"))
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = sonar, onCheckedChange = { sonar = it },
                   modifier = Modifier.testTag("gpstime-beeps"))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Beep the last ten seconds", style = MaterialTheme.typography.bodyMedium)
                Text("Rising tone from :50, double at :58 and :59, a long low one on the " +
                     "minute. Plays on the alarm channel so it is heard with the phone " +
                     "silenced.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Set the other device to the GPS time, not to this phone's. The phone's clock " +
             "drifts whenever it has no network, which in the field is always.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Reloj(titulo: String, ms: Long?, utc: Boolean, tag: String) {
    Column {
        Text(titulo, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ms?.let { reloj(utc).format(Date(it)) } ?: "—",
             style = MaterialTheme.typography.displaySmall,
             fontFamily = FontFamily.Monospace,
             modifier = Modifier.testTag(tag))
    }
}

/** Monoespaciada: los digitos no deben bailar de anchura al cambiar cada decima. */
private fun reloj(utc: Boolean): SimpleDateFormat =
    SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        if (utc) timeZone = TimeZone.getTimeZone("UTC")
    }

/** "UTC-03" y no el nombre de la zona: el desplazamiento es lo que se necesita para restar. */
private fun desplazamientoLocal(): String {
    val min = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
    val signo = if (min < 0) "−" else "+"
    val a = abs(min)
    return if (a % 60 == 0) "UTC$signo%02d".format(a / 60)
           else "UTC$signo%02d:%02d".format(a / 60, a % 60)
}

private fun tienePermisoUbicacion(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
