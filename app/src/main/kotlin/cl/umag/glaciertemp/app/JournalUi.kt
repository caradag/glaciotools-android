package cl.umag.glaciertemp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.fieldbook.JournalDay
import cl.umag.glaciertemp.core.fieldbook.JournalEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * El diario de campana: una bitacora continua, dia a dia.
 *
 * QUE PROBLEMA RESUELVE, y por que no es un tipo de anotacion mas. La libreta documenta
 * COSAS --una baliza, un punto, una muestra-- y cada una acaba en una fila de un CSV. Pero
 * una campana tambien produce otra clase de informacion: que se intento y no salio, como
 * estaba el hielo, por que se cambio el plan. Eso no cabe en campos con nombre, y sin un
 * sitio donde ponerlo se pierde -- o peor, acaba metido a la fuerza en el comentario de una
 * baliza, donde nadie lo volvera a buscar.
 */
@Composable
fun JournalScreen(vm: JournalViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    // Lo tecleado se agrupa medio segundo antes de tocar el disco, asi que al irse la app a
    // segundo plano puede quedar algo sin escribir. ON_STOP es el momento a partir del cual
    // Android puede matar el proceso sin avisar: aqui se fuerza el volcado.
    val duenoCiclo = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(duenoCiclo) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.flush()
        }
        duenoCiclo.lifecycle.addObserver(obs)
        onDispose { duenoCiclo.lifecycle.removeObserver(obs); vm.flush() }
    }

    val abierta = s.open
    // SIEMPRE habilitado, no solo con una entrada abierta. El diario se entra desde la
    // libreta, asi que el atras del sistema tiene que devolver ahi; sin este manejador caia
    // en el de la aplicacion y saltaba hasta el inicio, saltandose la libreta entera.
    androidx.activity.compose.BackHandler {
        if (abierta != null) vm.close() else onBack()
    }

    ToolBar(
        titulo = if (abierta == null) "Journal" else "Journal entry",
        // Dice a donde vuelve. Poner "Tools" y aterrizar en la libreta es una promesa que
        // el boton no cumple.
        atras = if (abierta == null) "Fieldbook" else "Journal",
        onBack = { if (abierta != null) vm.close() else onBack() },
    )

    if (abierta == null) DiasDelDiario(vm, s) else EditorDeEntrada(vm, abierta)

    s.note?.let {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.clearNote() }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DiasDelDiario(vm: JournalViewModel, s: JournalUiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(s.campaignName, style = MaterialTheme.typography.titleSmall,
             modifier = Modifier.testTag("jr-campaign"))

        Button(onClick = { vm.create() }, modifier = Modifier.testTag("jr-new")) {
            Text("New journal entry")
        }

        if (s.days.isEmpty()) {
            Text("Nothing written yet. The journal is for what the structured notes do not " +
                 "hold: what was tried, what the weather did, why the plan changed.",
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.testTag("jr-empty"))
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.days, key = { it.key }) { dia ->
                DiaDelDiario(vm, s, dia)
            }
        }
    }
}

@Composable
private fun DiaDelDiario(vm: JournalViewModel, s: JournalUiState, dia: JournalDay) {
    val plegado = dia.key in s.collapsed
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { vm.toggleDay(dia.key) }
                    .testTag("jr-day-${dia.key}")) {
                Icon(if (plegado) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                     contentDescription = if (plegado) "Expand" else "Collapse")
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(fechaLegible(dia.key),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // EL TITULO DEL DIA es lo unico que queda a la vista plegado, asi que es
                    // lo que permite recorrer una campana larga. Si esta vacio se dice, para
                    // que se note que falta: un dia sin titulo en un diario de seis semanas
                    // es un dia que habra que abrir para saber que hay dentro.
                    Text(dia.title.ifBlank { "Untitled day" },
                         style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.Bold,
                         color = if (dia.title.isBlank())
                             MaterialTheme.colorScheme.onSurfaceVariant
                         else MaterialTheme.colorScheme.onSurface)
                }
                Text("${dia.entries.size}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!plegado) {
                if (s.editingTitleFor == dia.key) {
                    var texto by remember(dia.key) { mutableStateOf(dia.title) }
                    // EL CAMPO SE ENFOCA SOLO. Sin esto hay que pulsar "Name this day" y
                    // DESPUES el campo que acaba de aparecer: dos toques para una accion que
                    // ya se habia pedido. Con guantes, el segundo toque es el que falla.
                    val foco = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(dia.key) { runCatching { foco.requestFocus() } }
                    OutlinedTextField(
                        value = texto, onValueChange = { texto = it },
                        label = { Text("Title for this day") },
                        placeholder = { Text("Instalación de balizas en glaciar") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(foco)
                            .testTag("jr-day-title-field"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.setDayTitle(dia.key, texto); vm.editTitleFor(null) },
                               modifier = Modifier.testTag("jr-day-title-save")) { Text("Save") }
                        TextButton(onClick = { vm.editTitleFor(null) }) { Text("Cancel") }
                    }
                } else {
                    TextButton(onClick = { vm.editTitleFor(dia.key) },
                               modifier = Modifier.testTag("jr-day-title-edit")) {
                        Text(if (dia.title.isBlank()) "Name this day" else "Rename day")
                    }
                }

                HorizontalDivider()

                dia.entries.forEach { e ->
                    Column(Modifier.fillMaxWidth().clickable { vm.open(e.id) }
                               .padding(vertical = 6.dp)
                               .testTag("jr-entry-${e.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(horaLegible(e.epochMillis),
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(e.title.ifBlank { "(no title)" },
                                 style = MaterialTheme.typography.titleSmall,
                                 modifier = Modifier.weight(1f))
                            val medios = e.photos.size + e.audio.size
                            if (medios > 0) {
                                Text("$medios ${if (medios == 1) "file" else "files"}",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (e.text.isNotBlank()) {
                            Text(e.text.lineSequence().first().take(90),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorDeEntrada(vm: JournalViewModel, e: JournalEntry) {
    var borrar by rememberSaveable { mutableStateOf(false) }
    val reproductor = remember { AudioNotePlayer() }
    val grabador = remember { AudioNoteRecorder() }
    var aviso by rememberSaveable { mutableStateOf<String?>(null) }
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) }, onAdded = { vm.addPhotos(it) })

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // LA FECHA VA ARRIBA Y ES EDITABLE. Casi siempre esta bien --se pone sola-- pero el
        // caso que importa es el otro: escribir de noche lo que paso ayer. Cambiarla mueve
        // la entrada al dia que le toca, sin mas.
        TimestampRow("Date and time", e.epochMillis,
                     onChange = { ms -> vm.update(immediate = true) { it.copy(epochMillis = ms) } },
                     tag = "jr-when")

        OutlinedTextField(
            value = e.title,
            onValueChange = { v -> vm.update { it.copy(title = v) } },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("jr-title"))

        OutlinedTextField(
            value = e.text,
            onValueChange = { v -> vm.update { it.copy(text = v) } },
            label = { Text("What happened") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("jr-text"))

        Text("Photos", style = MaterialTheme.typography.titleSmall)
        PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "jr-photo")
        PhotoStrip(files = e.photos, resolve = { vm.media(it) },
                   onRemove = { vm.removePhoto(it) }, tag = "jr-photos")

        Text("Audio notes", style = MaterialTheme.typography.titleSmall)
        RecordButton(
            recorder = grabador,
            newFile = { vm.newMediaFile(it) },
            onFinished = { f, ms -> vm.addAudio(f.name, ms) },
            onError = { aviso = it })
        e.audio.forEach { a ->
            AudioRow(file = vm.media(a.file), durationMillis = a.durationMillis,
                     player = reproductor, onChanged = { vm.removeAudio(a.file) })
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.close() }, modifier = Modifier.testTag("jr-done")) { Text("Done") }
            TextButton(onClick = { borrar = true },
                       colors = ButtonDefaults.textButtonColors(
                           contentColor = MaterialTheme.colorScheme.error),
                       modifier = Modifier.testTag("jr-delete")) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete")
            }
        }
        aviso?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
                 modifier = Modifier.testTag("jr-aviso"))
        }
        Text("Everything is saved as you type.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (borrar) {
        AlertDialog(
            onDismissRequest = { borrar = false },
            modifier = Modifier.testTag("jr-delete-dialog"),
            title = { Text("Delete this journal entry?") },
            text = { Text("Its text, photos and audio go with it. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { borrar = false; vm.delete(e.id) },
                           colors = ButtonDefaults.textButtonColors(
                               contentColor = MaterialTheme.colorScheme.error),
                           modifier = Modifier.testTag("jr-delete-confirm")) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { borrar = false }) { Text("Cancel") } })
    }
}

/** "Wednesday 25 September 2026" a partir de "2026-09-25". */
private fun fechaLegible(dayKey: String): String {
    val p = dayKey.split("-").mapNotNull { it.toIntOrNull() }
    if (p.size != 3) return dayKey
    val c = java.util.Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2], 12, 0, 0) }
    return SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(c.time)
}

private fun horaLegible(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
