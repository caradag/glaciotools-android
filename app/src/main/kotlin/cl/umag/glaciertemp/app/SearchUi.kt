package cl.umag.glaciertemp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.fieldbook.FieldbookSearch
import cl.umag.glaciertemp.core.fieldbook.JournalEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CUANDO = DateTimeFormatter.ofPattern("d MMM yyyy  HH:mm")

private fun cuando(ms: Long): String =
    CUANDO.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * Buscar texto en toda la libreta y todo el diario.
 *
 * BUSCA EN LO ARCHIVADO TAMBIEN, y esa es la mitad de su razon de ser: lo que uno no
 * recuerda esta en una campana cerrada hace anos. Por eso cada resultado dice a que campana
 * pertenece -- sin eso, encontrar "E-12" en cuatro campanas distintas no ayuda nada.
 */
@Composable
fun SearchScreen(vm: FieldbookViewModel, s: FieldbookUiState, onClose: () -> Unit) {
    // Se abre con el teclado puesto. Se ha llegado aqui pulsando la lupa: no hay ninguna otra
    // cosa que se pueda querer hacer en esta pantalla antes de escribir.
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }

    var mirando by remember { mutableStateOf<JournalEntry?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {

        OutlinedTextField(
            value = s.query,
            onValueChange = { vm.search(it) },
            placeholder = { Text("Search notes and journal") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (s.query.isNotEmpty()) {
                    IconButton(onClick = { vm.clearSearch() },
                               modifier = Modifier.testTag("se-clear")) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(s.query) }),
            modifier = Modifier.fillMaxWidth().focusRequester(foco).testTag("se-query"))

        when {
            s.query.isBlank() ->
                Text("Looks in every note and every journal entry, " +
                     "including archived campaigns. Accents and capitals are ignored.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)

            s.hits.isEmpty() && s.searched ->
                Text("Nothing found for “${s.query}”.",
                     style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.testTag("se-empty"))

            else -> {
                Text("${s.hits.size} result" + (if (s.hits.size == 1) "" else "s"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.testTag("se-count"))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
                           modifier = Modifier.weight(1f)) {
                    items(s.hits, key = { it.kind.name + it.id }) { h ->
                        Resultado(h) {
                            // Una nota se abre para editarla; una entrada del diario se abre
                            // para LEERLA. Saltar al diario de otra campana desde aqui
                            // obligaria a cambiar la campana en curso por haber buscado algo,
                            // que es demasiado efecto para una consulta.
                            if (h.kind == FieldbookSearch.Kind.NOTE) { onClose(); vm.open(h.id) }
                            else mirando = vm.journalEntry(h.id)
                        }
                    }
                }
            }
        }
    }

    mirando?.let { EntradaDeDiario(it) { mirando = null } }
}

@Composable
private fun Resultado(h: FieldbookSearch.Hit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("se-hit")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // La etiqueta dice de donde sale. Un resultado del diario y una nota se leen
                // igual de parecidos, y abrirlos hace cosas distintas.
                Surface(color = if (h.kind == FieldbookSearch.Kind.JOURNAL)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small) {
                    Text(if (h.kind == FieldbookSearch.Kind.JOURNAL) "Journal" else "Note",
                         style = MaterialTheme.typography.labelSmall,
                         modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(h.title, style = MaterialTheme.typography.titleSmall,
                     maxLines = 1, modifier = Modifier.weight(1f))
            }
            Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            Text(cuando(h.epochMillis) +
                 (if (h.campaignName.isBlank()) "" else "  ·  ${h.campaignName}") +
                 "  ·  ${h.field}",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** La entrada del diario tal cual, para leerla sin cambiar de campana. */
@Composable
private fun EntradaDeDiario(e: JournalEntry, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.title.ifBlank { "Journal entry" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                   verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(cuando(e.epochMillis), style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodyMedium)
                val medios = e.photos.size to e.audio.size
                if (medios.first > 0 || medios.second > 0)
                    Text(buildString {
                        if (medios.first > 0)
                            append("${medios.first} photo").append(if (medios.first == 1) "" else "s")
                        if (medios.first > 0 && medios.second > 0) append("  ·  ")
                        if (medios.second > 0)
                            append("${medios.second} audio note")
                                .append(if (medios.second == 1) "" else "s")
                    }, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
                       color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
