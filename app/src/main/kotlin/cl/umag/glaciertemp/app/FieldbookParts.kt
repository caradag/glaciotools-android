package cl.umag.glaciertemp.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.fieldbook.FieldPosition
import cl.umag.glaciertemp.core.geo.SavedPoint
import java.io.File

// ----------------------------------- formato de tiempo -----------------------------------

private val FECHA_HORA = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Una marca de tiempo en la zona horaria del telefono, que es donde esta quien la lee. */
fun formatWhen(ms: Long): String = FECHA_HORA.format(
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

/** Una duracion en h/min/s, con los ceros a la izquierda que hacen que no baile al correr. */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
           else "%02d:%02d".format(s / 60, s % 60)
}

/** Una duracion en palabras, para un dato ya cerrado. */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/**
 * Un reloj que avanza mientras algo esta en marcha.
 *
 * Reevalua una vez por segundo y SOLO mientras [running]: un `LaunchedEffect` eterno mantiene
 * viva una corrutina por cada cronometro que haya llegado a existir en la pantalla.
 */
@Composable
fun rememberTicker(running: Boolean): Long {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            ahora = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    return ahora
}

// -------------------------------------- numeros --------------------------------------

/** Lo que se escribe en un campo de numero, aceptando la coma que pone un teclado en espanol. */
fun parseNumber(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()

fun formatNumber(v: Double?): String = when {
    v == null -> ""
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
}

/**
 * Un campo numerico que conserva lo que el usuario esta tecleando.
 *
 * El texto vive aqui y no se regenera desde el numero en cada pulsacion: escribiendo "82."
 * o "0.0", volver a formatear el double borraria el punto o el cero de debajo del cursor y el
 * campo se pelearia con el dedo. Solo se resincroniza cuando el valor cambia desde fuera.
 */
@Composable
fun NumberField(
    label: String,
    value: Double?,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    tag: String? = null,
) {
    var texto by remember { mutableStateOf(formatNumber(value)) }
    // Se resincroniza solo cuando el valor cambia desde FUERA a algo que el texto actual no
    // representa. Reformatear en cada pulsacion borraria el punto recien escrito en "82." y
    // el cero de "0.0" justo debajo del cursor.
    LaunchedEffect(value) {
        if (parseNumber(texto) != value && !(texto.isBlank() && value == null)) {
            texto = formatNumber(value)
        }
    }
    OutlinedTextField(
        value = texto,
        onValueChange = { t ->
            texto = t
            onValue(if (t.isBlank()) null else parseNumber(t))
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        isError = texto.isNotBlank() && parseNumber(texto) == null,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal),
        modifier = modifier.then(tag?.let { Modifier.testTag(it) } ?: Modifier),
    )
}

// ----------------------------- desplegable de nombres con ⋮ -----------------------------

/**
 * Un campo de nombre con lista desplegable y un menu de administracion.
 *
 * El campo es EDITABLE ademas de desplegable: en terreno aparece gente que no estaba en la
 * lista, y obligar a darla de alta en otra pantalla antes de poder anotar una medicion es
 * justo el tipo de friccion que hace que la medicion se anote en papel.
 *
 * Borrar de la lista NO toca ningun registro: lo que se guarda en cada entrada es el nombre,
 * no una referencia. El menu lo dice, porque lo contrario es lo que uno teme al pulsar.
 */
@Composable
fun NamePicker(
    label: String,
    value: String,
    options: List<String>,
    onValue: (String) -> Unit,
    onRemember: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "name",
) {
    var lista by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmarVaciado by remember { mutableStateOf(false) }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                label = { Text(label) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { lista = !lista },
                               enabled = options.isNotEmpty(),
                               modifier = Modifier.testTag("$tag-open")) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose $label")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(tag)
                    // Se recuerda al SALIR del campo y no en cada pulsacion: guardando por
                    // letra, la lista se llenaria de "C", "Ca", "Cam"...
                    .onFocusChanged { f -> if (!f.isFocused && value.isNotBlank()) onRemember(value) },
            )
            DropdownMenu(expanded = lista, onDismissRequest = { lista = false }) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o) },
                        onClick = { lista = false; onValue(o); onRemember(o) },
                        modifier = Modifier.testTag("$tag-option"))
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("$tag-menu")) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Manage the $label list")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Remove “${value.ifBlank { "—" }}” from the list") },
                    enabled = value.isNotBlank() &&
                              options.any { it.equals(value, ignoreCase = true) },
                    onClick = { menu = false; onRemove(value) },
                    modifier = Modifier.testTag("$tag-remove"))
                DropdownMenuItem(
                    text = { Text("Clear the whole list") },
                    enabled = options.isNotEmpty(),
                    onClick = { menu = false; confirmarVaciado = true },
                    modifier = Modifier.testTag("$tag-clear"))
                HorizontalDivider()
                // Texto y no una opcion mas: es lo que uno teme al pulsar, y tiene que estar
                // a la vista EN el menu, no en una ayuda que nadie abre.
                Text("Removing only hides the name from new entries. Everything already " +
                     "recorded keeps it.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp,
                                                                      vertical = 8.dp))
            }
        }
    }

    if (confirmarVaciado) {
        AlertDialog(
            onDismissRequest = { confirmarVaciado = false },
            modifier = Modifier.testTag("$tag-clear-dialog"),
            title = { Text("Clear the $label list?") },
            text = { Text("${options.size} name(s) will stop being offered in new entries. " +
                          "Nothing already recorded changes.") },
            confirmButton = {
                TextButton(onClick = { confirmarVaciado = false; onClearAll() },
                           modifier = Modifier.testTag("$tag-clear-confirm")) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmarVaciado = false }) { Text("Cancel") }
            })
    }
}

// --------------------------------- marca de tiempo ---------------------------------

/**
 * Una marca de tiempo que se puede corregir.
 *
 * Con los cuadros del sistema y no con los de Compose: son los que el usuario ya sabe usar, y
 * la fecha y la hora se eligen en dos pasos cortos en vez de en una pantalla completa.
 */
@Composable
fun TimestampRow(
    label: String,
    millis: Long?,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "ts",
    trailing: (@Composable () -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(millis?.let { formatWhen(it) } ?: "—",
                 style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("$tag-value"))
        }
        trailing?.invoke()
        TextButton(onClick = {
            val base = java.time.Instant.ofEpochMilli(millis ?: System.currentTimeMillis())
                .atZone(java.time.ZoneId.systemDefault())
            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                android.app.TimePickerDialog(ctx, { _, h, min ->
                    val nuevo = java.time.LocalDateTime.of(y, m + 1, d, h, min)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onChange(nuevo)
                }, base.hour, base.minute, true).show()
            }, base.year, base.monthValue - 1, base.dayOfMonth).show()
        }, modifier = Modifier.testTag("$tag-edit")) { Text("Edit") }
    }
}

// ------------------------------------- posicion -------------------------------------

/**
 * La coordenada de una entrada: del GPS del telefono, o de un punto ya promediado.
 *
 * Los dos caminos se ofrecen juntos y no uno como respaldo del otro, porque no son lo mismo
 * con distinta suerte: el punto guardado es MEJOR dato --minutos de promediado contra una
 * lectura suelta-- siempre que la entrada este efectivamente en ese punto.
 */
@Composable
fun PositionField(
    position: FieldPosition?,
    request: PositionRequest?,
    savedPoints: List<SavedPoint.Solution>,
    onUsePhone: () -> Unit,
    onCancelPhone: () -> Unit,
    onUsePoint: (SavedPoint.Solution) -> Unit,
    onClear: () -> Unit,
    onNeedPoints: () -> Unit,
) {
    var elegir by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Position", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (position != null) {
            Text(position.describe(), style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.testTag("fb-position"))
            Text(position.detail(), style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("fb-position-detail"))
        } else if (request?.waiting != true) {
            Text("Not recorded", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("fb-position"))
        }

        if (request?.waiting == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting for a fix…", style = MaterialTheme.typography.bodySmall,
                     modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelPhone,
                           modifier = Modifier.testTag("fb-pos-cancel")) { Text("Cancel") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUsePhone, modifier = Modifier.testTag("fb-pos-phone")) {
                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Phone GPS")
                }
                TextButton(onClick = { onNeedPoints(); elegir = true },
                           modifier = Modifier.testTag("fb-pos-saved")) {
                    Icon(Icons.Outlined.Place, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saved point")
                }
                if (position != null) {
                    TextButton(onClick = onClear,
                               modifier = Modifier.testTag("fb-pos-clear")) { Text("Clear") }
                }
            }
        }
    }

    if (elegir) {
        SavedPointDialog(savedPoints, onPick = { elegir = false; onUsePoint(it) },
                         onDismiss = { elegir = false })
    }
}

/** La lista de puntos de GPS tools, para elegir uno como coordenada. */
@Composable
fun SavedPointDialog(
    points: List<SavedPoint.Solution>,
    onPick: (SavedPoint.Solution) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("saved-point-dialog"),
        title = { Text("Use a saved point") },
        text = {
            if (points.isEmpty()) {
                Text("No averaged points yet. Measure one in GPS tools first — a point that " +
                     "was created but never measured has no solution to offer.",
                     modifier = Modifier.testTag("saved-point-empty"))
            } else {
                LazyColumnOfPoints(points, onPick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun LazyColumnOfPoints(
    points: List<SavedPoint.Solution>,
    onPick: (SavedPoint.Solution) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(points, key = { it.id }) { p ->
            Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp)
                       .testTag("saved-point-${p.id}")) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text("%.5f, %.5f".format(java.util.Locale.ROOT, p.latitude, p.longitude),
                     style = MaterialTheme.typography.bodySmall)
                Text(buildString {
                    append("${p.samples} fixes")
                    p.standardErrorMetres?.let {
                        append("  ·  ± %.2f m".format(java.util.Locale.ROOT, it))
                    }
                    append("  ·  ${formatWhen(p.lastEpochMillis)}")
                }, style = MaterialTheme.typography.bodySmall,
                   color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// -------------------------------------- fotos --------------------------------------

/**
 * Los dos caminos para anadir una foto: hacerla ahora o coger una que ya esta.
 *
 * El selector de imagenes del sistema (`PickVisualMedia`) NO pide permiso de almacenamiento:
 * el usuario elige en una pantalla del sistema y la app recibe solo lo elegido. Pedir
 * READ_MEDIA_IMAGES para lo mismo daria acceso a la galeria entera a cambio de nada.
 *
 * LO ELEGIDO SE COPIA a la carpeta de la libreta. La URI que devuelve el selector vale para
 * un rato; guardarla en el fichero de la entrada seria guardar un enlace que manana no abre.
 */
@Composable
fun rememberPhotoAdders(
    newFile: (String) -> File?,
    onAdded: (List<String>) -> Unit,
): Pair<() -> Unit, () -> Unit> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // rememberSaveable y la RUTA en vez de un File: mientras la camara esta delante, Android
    // puede matar este proceso --pasa de verdad en un telefono de terreno con la memoria
    // llena-- y al volver, el resultado llega a un `remember` recien nacido en null. La foto
    // estaria en disco y la libreta no sabria de ella. Un String sobrevive en el Bundle.
    var pendiente by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val camara = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = pendiente?.let { File(it) }
        pendiente = null
        if (f == null) return@rememberLauncherForActivityResult
        // Una foto cancelada deja un fichero de cero bytes. Sin borrarlo, la carpeta se
        // llena de huecos que la libreta no nombra y que nadie sabe de donde salieron.
        if (ok && f.exists() && f.length() > 0L) onAdded(listOf(f.name)) else f.delete()
    }

    val galeria = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val nuevos = uris.mapNotNull { uri ->
            val destino = newFile("jpg") ?: return@mapNotNull null
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { entrada ->
                    destino.outputStream().use { entrada.copyTo(it) }
                }
                destino.name.takeIf { destino.length() > 0L }
            }.getOrElse { destino.delete(); null }
        }
        if (nuevos.isNotEmpty()) onAdded(nuevos)
    }

    val tomar = {
        val f = newFile("jpg")
        if (f != null) {
            pendiente = f.absolutePath
            runCatching { camara.launch(MediaVault.uriFor(ctx, f)) }
                // SE DICE. Antes esto moria callado: al faltar la carpeta del diario en
                // file_paths.xml, getUriForFile lanzaba, el fichero se borraba y el boton
                // de camara no hacia absolutamente nada -- sin aviso, sin registro, y sin
                // ninguna forma de distinguirlo de un toque que no se registro.
                .onFailure { e ->
                    pendiente = null; f.delete()
                    android.util.Log.e("GlacioTools", "camara: ${f.absolutePath}", e)
                    android.widget.Toast.makeText(
                        ctx, "The camera could not be opened", android.widget.Toast.LENGTH_LONG)
                        .show()
                }
            Unit
        } else Unit
    }
    val elegir = {
        galeria.launch(androidx.activity.result.PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    return tomar to elegir
}

/** Los dos botones de anadir foto, con el mismo aspecto en las cuatro pantallas. */
@Composable
fun PhotoButtons(onTake: () -> Unit, onPick: () -> Unit, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onTake, modifier = Modifier.testTag("$tag-take")) {
            Icon(Icons.Outlined.Photo, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Camera")
        }
        TextButton(onClick = onPick, modifier = Modifier.testTag("$tag-pick")) {
            Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Gallery")
        }
    }
}

/** Las miniaturas en fila, con su aspa para quitarlas y pulsacion para verlas grandes. */
@Composable
fun PhotoStrip(
    files: List<String>,
    resolve: (String) -> File?,
    onRemove: (String) -> Unit,
    tag: String,
) {
    if (files.isEmpty()) return
    var mirando by remember { mutableStateOf<String?>(null) }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag(tag)) {
        items(files, key = { it }) { nombre ->
            val f = resolve(nombre)
            Box {
                if (f != null) {
                    val bmp by MediaVault.rememberImage(f, 240)
                    val b = bmp
                    if (b != null) {
                        Image(b, contentDescription = "Photo",
                              contentScale = ContentScale.Crop,
                              modifier = Modifier.size(88.dp)
                                  .clip(RoundedCornerShape(8.dp))
                                  .clickable { mirando = nombre }
                                  .testTag("$tag-item"))
                    } else {
                        Box(Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        }
                    }
                }
                IconButton(onClick = { onRemove(nombre) },
                           modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove photo",
                         modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    mirando?.let { nombre ->
        val f = resolve(nombre)
        AlertDialog(
            onDismissRequest = { mirando = null },
            modifier = Modifier.testTag("$tag-viewer"),
            text = {
                if (f == null) Text("That photo is no longer on the phone.")
                else {
                    val bmp by MediaVault.rememberImage(f, 1600)
                    bmp?.let {
                        Image(it, contentDescription = "Photo",
                              contentScale = ContentScale.Fit,
                              modifier = Modifier.fillMaxWidth())
                    } ?: CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = { mirando = null }) { Text("Close") } })
    }
}

// -------------------------------------- audio --------------------------------------

/** Una nota de audio en la lista: reproducir, parar y su duracion. */
@Composable
fun AudioRow(
    file: File?,
    durationMillis: Long?,
    player: AudioNotePlayer,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sonando = file != null && player.playing == file.name
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { file?.let { f -> player.toggle(f) { onChanged() } } },
            enabled = file != null && file.exists(),
            modifier = Modifier.testTag("fb-audio-play"),
        ) {
            Icon(if (sonando) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                 contentDescription = if (sonando) "Stop" else "Play")
        }
        Column(Modifier.weight(1f)) {
            Text(if (file?.exists() == true) "Audio note" else "Audio note (missing)",
                 style = MaterialTheme.typography.bodyMedium)
            durationMillis?.let {
                Text(formatDuration(it), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** El boton de grabar, con el tiempo corriendo mientras graba. */
@Composable
fun RecordButton(recorder: AudioNoteRecorder, onFinished: (File, Long) -> Unit,
                 onError: (String) -> Unit, newFile: (String) -> File?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var grabando by remember { mutableStateOf(false) }
    val ahora = rememberTicker(grabando)
    val permiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) onError("Microphone permission denied: no audio notes.")
    }

    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            if (grabando) {
                val f = recorder.stop()
                grabando = false
                if (f == null) onError("Nothing was recorded.")
                else onFinished(f, MediaVault.audioDurationMillis(f) ?: 0L)
            } else {
                val concedido = androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!concedido) {
                    permiso.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    val destino = newFile("m4a")
                    if (destino == null) onError("Could not create the audio file.")
                    else if (!recorder.start(ctx, destino)) onError("The microphone is not available.")
                    else grabando = true
                }
            }
        }, modifier = Modifier.testTag("fb-record")) {
            Icon(if (grabando) Icons.Filled.Stop else Icons.Outlined.Mic, null,
                 Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (grabando) "Stop recording" else "Audio note")
        }
        if (grabando) {
            // `ahora` lo mueve el ticker una vez por segundo; el origen lo da el propio
            // grabador, que es quien sabe cuando empezo de verdad.
            Text(formatElapsed(ahora - recorder.startedAtMillis),
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.error,
                 modifier = Modifier.testTag("fb-record-elapsed"))
            TextButton(onClick = { recorder.cancel(); grabando = false }) { Text("Discard") }
        }
    }
}
