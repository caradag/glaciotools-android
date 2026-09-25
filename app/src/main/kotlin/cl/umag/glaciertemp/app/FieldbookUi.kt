package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.fieldbook.*

/** Como se llama cada tipo en pantalla. En un solo sitio, para que no diverja. */
fun typeLabel(t: EntryType): String = when (t) {
    EntryType.NOTE -> "General note"
    EntryType.STAKE -> "Stake measurement"
    EntryType.GNSS -> "GNSS measurement"
    EntryType.DENDRO -> "Dendro sample"
}

private fun typeBlurb(t: EntryType): String = when (t) {
    EntryType.NOTE -> "Free text, photos and audio notes, each with its own time stamp."
    EntryType.STAKE -> "A stake and its successive exposed-height readings, with the " +
                       "ablation rate between them."
    EntryType.GNSS -> "A high-precision point: receiver, start, end and a timer that rings " +
                      "when the planned time is up."
    EntryType.DENDRO -> "A tree core or wedge: label, species, sampling height and trunk " +
                        "perimeter."
}

/**
 * La libreta entera: la lista, y la entrada abierta.
 *
 * El reparto es el mismo que en GPS tools --una lista que abre una pantalla de detalle-- y no
 * por simetria: es la forma que tiene el trabajo. En terreno se abre la libreta para anadir
 * algo a lo que ya se estaba haciendo mucho mas a menudo que para empezar de cero.
 */
@Composable
fun FieldbookScreen(vm: FieldbookViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    // Lo tecleado se agrupa medio segundo antes de tocar el disco, asi que al irse la app a
    // segundo plano puede haber algo sin escribir -- y ON_STOP es justo el momento a partir
    // del cual Android puede matar el proceso sin avisar. Aqui se fuerza el volcado.
    val duenoCiclo = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(duenoCiclo) {
        val observador = androidx.lifecycle.LifecycleEventObserver { _, evento ->
            if (evento == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.flush()
        }
        duenoCiclo.lifecycle.addObserver(observador)
        onDispose { duenoCiclo.lifecycle.removeObserver(observador); vm.flush() }
    }

    androidx.activity.compose.BackHandler(enabled = s.open != null) { vm.close() }

    val abierta = s.open

    // Una sola cabecera. Antes habia dos filas diciendo lo mismo: la barra de navegacion con
    // "Fieldbook" y, justo debajo, un titulo "Field notebook" con los iconos. Dos franjas de
    // pantalla para un nombre que ya estaba escrito, y en un telefono eso empuja la primera
    // anotacion fuera de la vista.
    var exportar by rememberSaveable { mutableStateOf(false) }
    var explicar by rememberSaveable { mutableStateOf(false) }

    ToolBar(
        titulo = when {
            abierta != null -> "Entry"
            s.viewingCampaign != null -> "Archived campaign"
            else -> "Fieldbook"
        },
        onBack = { if (abierta != null) vm.close() else onBack() },
    ) {
        // Solo en la lista: dentro de una entrada, exportar la libreta entera o abrir la
        // explicacion general no viene a cuento, y un icono que no toca es un icono que
        // alguien toca.
        if (abierta == null) {
            IconButton(onClick = { exportar = true },
                       enabled = s.total > 0 && !s.exporting,
                       modifier = Modifier.testTag("fb-export")) {
                Icon(Icons.Outlined.Share, contentDescription = "Export")
            }
            IconButton(onClick = { explicar = true }, modifier = Modifier.testTag("fb-info")) {
                Icon(Icons.Outlined.Info, contentDescription = "What the notebook records")
            }
        }
    }

    if (abierta == null) EntryListScreen(vm, s, onExport = { exportar = true })
    else EntryScreen(vm, s, abierta)

    if (exportar) ExportDialog(vm, s) { exportar = false }
    if (explicar) FieldbookExplained { explicar = false }
}

@Composable
private fun EntryListScreen(vm: FieldbookViewModel, s: FieldbookUiState,
                            onExport: () -> Unit) {
    var eligiendoTipo by remember { mutableStateOf(false) }
    var terminarCampana by remember { mutableStateOf(false) }
    var verArchivadas by remember { mutableStateOf(false) }
    // La campana CONCRETA que se renombra, no un booleano: el cuadro sirve para la abierta y
    // para cualquier archivada, y confundir cual se esta tocando es justo el fallo que se
    // esta arreglando aqui.
    var renombrando by remember { mutableStateOf<Campaign?>(null) }
    var borrando by remember { mutableStateOf<Campaign?>(null) }
    val mirandoArchivada = s.viewingCampaign != null

    Column(Modifier.fillMaxSize().padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CampaignBar(
            s = s,
            onRename = { renombrando = s.viewingCampaign ?: s.activeCampaign },
            onFinish = { terminarCampana = true },
            onArchived = { verArchivadas = true },
            onBackToCurrent = { vm.viewCampaign(null) })

        if (!mirandoArchivada) {
            Button(onClick = { eligiendoTipo = true }, modifier = Modifier.testTag("fb-new")) {
                Text("New entry")
            }
        }

        if (s.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
        FieldbookNotice(vm, s)

        // Los filtros solo aparecen cuando hay mas de un tipo anotado: con tres entradas
        // todas del mismo tipo, cinco botones ocupan mas sitio del que ahorran.
        if (s.counts.values.count { it > 0 } > 1) FilterRow(s) { vm.setFilter(it) }

        if (s.entries.isEmpty()) {
            Text(
                when {
                    s.total == 0 && mirandoArchivada -> "This campaign has no entries."
                    s.total == 0 -> "No entries yet."
                    else -> "Nothing of that kind in this campaign."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fb-empty"))
        } else {
            LazyColumn(Modifier.weight(1f).testTag("fb-list"),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.entries, key = { it.id }) { e -> EntryCard(e) { vm.open(e.id) } }
            }
        }
    }

    if (eligiendoTipo) {
        AlertDialog(
            onDismissRequest = { eligiendoTipo = false },
            modifier = Modifier.testTag("fb-type-dialog"),
            title = { Text("New entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryType.entries.forEach { t ->
                        Card(onClick = { eligiendoTipo = false; vm.create(t) },
                             modifier = Modifier.fillMaxWidth()
                                 .testTag("fb-type-${t.name.lowercase()}")) {
                            Column(Modifier.padding(12.dp)) {
                                Text(typeLabel(t), style = MaterialTheme.typography.titleSmall)
                                Text(typeBlurb(t),
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { eligiendoTipo = false }) { Text("Cancel") }
            })
    }

    if (terminarCampana) {
        FinishCampaignDialog(s, onDismiss = { terminarCampana = false }) { nombre ->
            terminarCampana = false
            vm.archiveActiveCampaign(nombre)
        }
    }

    if (verArchivadas) {
        ArchivedCampaignsDialog(
            s,
            onOpen = { verArchivadas = false; vm.viewCampaign(it) },
            onRename = { verArchivadas = false; renombrando = it },
            onDelete = { verArchivadas = false; borrando = it },
            onReopen = { verArchivadas = false; vm.unarchiveCampaign(it.id) },
            onDismiss = { verArchivadas = false })
    }

    borrando?.let { c ->
        DeleteCampaignDialog(c, s.campaignCounts[c.id] ?: 0,
                             onDismiss = { borrando = null }) {
            borrando = null
            vm.deleteCampaign(c.id)
        }
    }

    renombrando?.let { c ->
        RenameCampaignDialog(c, onDismiss = { renombrando = null }) { nombre ->
            renombrando = null
            vm.renameCampaign(c.id, nombre)
        }
    }

}

/**
 * La campana en curso, o la archivada que se esta mirando.
 *
 * Siempre visible y no detras de un menu: es el contexto de todo lo que hay debajo, y anotar
 * en la campana equivocada es un error que no da ninguna senal hasta meses despues, al
 * exportar.
 */
@Composable
private fun CampaignBar(
    s: FieldbookUiState,
    onRename: () -> Unit,
    onFinish: () -> Unit,
    onArchived: () -> Unit,
    onBackToCurrent: () -> Unit,
) {
    val mirando = s.viewingCampaign
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (mirando != null) "Viewing" else "Current campaign",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(mirando?.displayName()
                     ?: s.activeCampaign?.displayName()
                     ?: "No campaign started yet",
                     style = MaterialTheme.typography.titleSmall,
                     modifier = Modifier.testTag("fb-campaign"))
                Text("${s.total} entr" + if (s.total == 1) "y" else "ies",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Bautizarla SIN cerrarla. El nombre se sabe el primer dia; obligar a terminar
            // la campana para poder escribirlo convierte un rotulo en una decision, y de
            // paso empuja a cerrarla antes de tiempo solo para poder nombrarla.
            val nombrable = mirando ?: s.activeCampaign
            if (nombrable != null) {
                TextButton(onClick = onRename,
                           modifier = Modifier.testTag("fb-campaign-rename")) {
                    Text(if (nombrable.name.isBlank()) "Name" else "Rename")
                }
            }
            if (mirando != null) {
                TextButton(onClick = onBackToCurrent,
                           modifier = Modifier.testTag("fb-campaign-back")) { Text("Current") }
            } else {
                TextButton(onClick = onFinish, enabled = s.activeCampaign != null && s.total > 0,
                           modifier = Modifier.testTag("fb-campaign-finish")) { Text("Finish") }
                TextButton(onClick = onArchived, enabled = s.archivedCampaigns.isNotEmpty(),
                           modifier = Modifier.testTag("fb-campaign-archived")) {
                    Text("Archived (${s.archivedCampaigns.size})")
                }
            }
        }
    }
}

/**
 * Los filtros rapidos por tipo, TODOS a la vista. El numero al lado es lo que hay, no lo
 * que podria haber.
 *
 * Antes era una fila con desplazamiento horizontal, y eso esconde: en una pantalla de
 * telefono cabian tres de los cinco chips y los otros dos habia que ir a buscarlos
 * arrastrando. Un filtro que no se ve no se usa, y peor, invita a pensar que la libreta no
 * tiene anotaciones de ese tipo. Con FlowRow los que no caben bajan a una segunda linea y
 * se ven los cinco de golpe.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(s: FieldbookUiState, onFilter: (EntryType?) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = s.filter == null, onClick = { onFilter(null) },
                   label = { Text("All (${s.total})") },
                   modifier = Modifier.testTag("fb-filter-all"))
        EntryType.entries.forEach { t ->
            val n = s.counts[t] ?: 0
            FilterChip(
                selected = s.filter == t,
                onClick = { onFilter(if (s.filter == t) null else t) },
                enabled = n > 0,
                label = { Text("${shortLabel(t)} ($n)") },
                modifier = Modifier.testTag("fb-filter-${t.name.lowercase()}"))
        }
    }
}

private fun shortLabel(t: EntryType): String = when (t) {
    EntryType.NOTE -> "Notes"
    EntryType.STAKE -> "Stakes"
    EntryType.GNSS -> "GNSS"
    EntryType.DENDRO -> "Dendro"
}

/**
 * Confirmar antes de borrar una campana archivada.
 *
 * DICE EL NUMERO, y ese es el punto entero del cuadro. Lo que motivo esta pantalla fue
 * borrar anotaciones creyendo que no pertenecian a nada; un aviso generico --"se borrara la
 * campana, seguro?"-- no habria evitado nada, porque la pregunta que hacia falta responder
 * no era "seguro" sino "cuanto hay ahi dentro".
 *
 * No se puede deshacer y se dice con esas palabras. En terreno no hay copia de seguridad.
 */
@Composable
private fun DeleteCampaignDialog(
    c: Campaign, entradas: Int, onDismiss: () -> Unit, onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fb-delete-campaign-dialog"),
        title = { Text("Delete this campaign?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(buildAnnotatedString {
                    append("“${c.displayName()}” and its ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("$entradas ${if (entradas == 1) "entry" else "entries"}")
                    }
                    append(" go with it, including their photos and audio.")
                }, modifier = Modifier.testTag("fb-delete-campaign-count"))
                Text("This cannot be undone. Export the notebook first if you want a copy.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete,
                       colors = ButtonDefaults.textButtonColors(
                           contentColor = MaterialTheme.colorScheme.error),
                       modifier = Modifier.testTag("fb-delete-campaign-confirm")) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Ponerle nombre a una campana sin cerrarla, o cambiarselo a una ya archivada.
 *
 * Las dos cosas faltaban, y la segunda tenia una consecuencia peor que la molestia: la unica
 * forma de renombrar una archivada era reabrirla y volver a cerrarla, y ese viaje dejaba por
 * el camino una campana nueva vacia con el nombre tecleado. Se arreglaba el rotulo y se
 * creaba un duplicado.
 */
@Composable
private fun RenameCampaignDialog(
    c: Campaign, onDismiss: () -> Unit, onRename: (String) -> Unit,
) {
    var nombre by remember { mutableStateOf(c.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fb-rename-dialog"),
        title = { Text(if (c.name.isBlank()) "Name this campaign" else "Rename campaign") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nombre, onValueChange = { nombre = it },
                    label = { Text("Campaign name") },
                    placeholder = { Text("Bernal, February 2026") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fb-rename-name"))
                Text("Renaming changes nothing that was measured: the entries point at the " +
                     "campaign, not at its name.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(nombre.trim()) },
                       modifier = Modifier.testTag("fb-rename-confirm")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Terminar la campana: ponerle nombre y archivarla.
 *
 * El nombre se pide AQUI y no al empezar porque es cuando se sabe. Al abrir la libreta el
 * primer dia, un cuadro pidiendo un nombre es un obstaculo antes de la primera anotacion; al
 * cerrar, es la pregunta natural.
 */
@Composable
private fun FinishCampaignDialog(
    s: FieldbookUiState, onDismiss: () -> Unit, onFinish: (String) -> Unit,
) {
    var nombre by remember { mutableStateOf(s.activeCampaign?.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fb-finish-dialog"),
        title = { Text("Finish this campaign") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // DECIR CUAL. Archivar una campana y empezar a anotar abre otra en silencio,
                // asi que despues de mirar una archivada es facil creer que este cuadro
                // habla de aquella: se escribe su nombre, se archiva la NUEVA, y aparece una
                // segunda campana con el nombre que uno acababa de teclear. Parecia un
                // duplicado y era este cuadro, que no decia de que campana hablaba.
                s.activeCampaign?.let { c ->
                    Text("Archiving the campaign open now" +
                         (if (c.name.isBlank()) "" else " — “${c.name}”") +
                         ", with ${s.total} entr" + (if (s.total == 1) "y" else "ies") + ".",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.testTag("fb-finish-which"))
                }
                OutlinedTextField(
                    value = nombre, onValueChange = { nombre = it },
                    label = { Text("Campaign name") },
                    placeholder = { Text("Bernal, February 2026") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fb-finish-name"))
                Text("Its ${s.total} entries move to Archived campaigns and the list is " +
                     "empty again for the next one. Nothing is deleted, and you can open the " +
                     "campaign or reopen it at any time.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onFinish(nombre) },
                       modifier = Modifier.testTag("fb-finish-confirm")) { Text("Archive") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ArchivedCampaignsDialog(
    s: FieldbookUiState,
    onOpen: (Campaign) -> Unit,
    onRename: (Campaign) -> Unit,
    onDelete: (Campaign) -> Unit,
    onReopen: (Campaign) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fb-archived-dialog"),
        title = { Text("Archived campaigns") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp),
                       verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(s.archivedCampaigns, key = { it.id }) { c ->
                    // El nombre en su propia linea y los botones debajo. Con los tres en la
                    // misma fila, el nombre quedaba comprimido en una columna de diez
                    // caracteres y "Prueba borrado" se leia partido en cuatro lineas.
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(c.displayName(),
                             style = MaterialTheme.typography.titleSmall,
                             modifier = Modifier.fillMaxWidth().clickable { onOpen(c) }
                                 .testTag("fb-archived-${c.id}"))
                        // EL NUMERO DE ANOTACIONES, en la lista y no solo al borrar: una
                        // campana que dice "0 entries" avisa de que algo no cuadra ANTES de
                        // que nadie borre nada por creerla vacia.
                        val n = s.campaignCounts[c.id] ?: 0
                        Text("$n ${if (n == 1) "entry" else "entries"}" +
                             (c.archivedEpochMillis?.let { "  ·  ${formatWhen(it)}" } ?: ""),
                             style = MaterialTheme.typography.bodySmall,
                             color = if (n == 0) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.testTag("fb-archived-count-${c.id}"))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onRename(c) },
                                       contentPadding = PaddingValues(horizontal = 8.dp),
                                       modifier = Modifier.testTag("fb-archived-rename-${c.id}")) {
                                Text("Rename")
                            }
                            TextButton(onClick = { onReopen(c) },
                                       contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("Reopen")
                            }
                            TextButton(onClick = { onDelete(c) },
                                       contentPadding = PaddingValues(horizontal = 8.dp),
                                       colors = ButtonDefaults.textButtonColors(
                                           contentColor = MaterialTheme.colorScheme.error),
                                       modifier = Modifier.testTag("fb-archived-delete-${c.id}")) {
                                Text("Delete")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Una entrada en la lista: lo justo para reconocerla sin abrirla. */
@Composable
private fun EntryCard(e: FieldEntry, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("fb-card-${e.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title(), style = MaterialTheme.typography.titleSmall,
                     modifier = Modifier.weight(1f))
                // Una medicion en marcha se ve desde la LISTA. Es lo unico de la libreta que
                // sigue ocurriendo mientras nadie mira, y encontrarla obliga a recordar en
                // que entrada estaba.
                if (e.runningGnss() != null) {
                    AssistChip(onClick = onOpen, label = { Text("measuring") },
                               modifier = Modifier.testTag("fb-running-${e.id}"))
                }
            }
            Text(typeLabel(e.type), style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.primary)
            Text(buildString {
                append(formatWhen(e.createdEpochMillis))
                if (e.person.isNotBlank()) append("  ·  ${e.person}")
                when (e.type) {
                    EntryType.STAKE -> append("  ·  ${e.measurements.size} reading(s)")
                    EntryType.NOTE -> append("  ·  ${e.items.size} item(s)")
                    else -> {}
                }
                e.position?.let { append("  ·  ${it.describe()}") }
            }, style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Errores y avisos, con el mismo aspecto que en GPS tools. */
@Composable
fun FieldbookNotice(vm: FieldbookViewModel, s: FieldbookUiState) {
    s.error?.let {
        Text("Error: $it", color = MaterialTheme.colorScheme.error,
             style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.testTag("fb-error"))
    }
    s.note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("fb-note"))
    }
    if (s.error != null || s.note != null) {
        TextButton(onClick = { vm.dismissNote() }) { Text("Dismiss") }
    }
}

// ------------------------------------ entrada abierta ------------------------------------

@Composable
private fun EntryScreen(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry) {
    var borrar by remember { mutableStateOf(false) }
    var falta by remember { mutableStateOf<List<String>?>(null) }

    /**
     * Repasa la medicion GNSS y avisa de lo que falte. NUNCA impide nada: lo que se acaba de
     * registrar ya esta guardado, y esto solo recuerda lo que conviene rellenar antes de
     * levantar el equipo -- que es el unico momento en que todavia se puede.
     */
    fun revisar(at: Long? = null) {
        val pendiente = vm.missingInGnss(at)
        if (pendiente.isNotEmpty()) falta = pendiente
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.close() }, modifier = Modifier.testTag("fb-back")) {
                Text("‹ Notebook")
            }
            Text(typeLabel(e.type), style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.weight(1f))
            IconButton(onClick = { borrar = true }, modifier = Modifier.testTag("fb-delete")) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete entry",
                     tint = MaterialTheme.colorScheme.error)
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)
                   .verticalScroll(rememberScrollState()),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {

            FieldbookNotice(vm, s)

            // Quien y donde son comunes a los cuatro tipos y van arriba en los cuatro: en una
            // libreta de terreno, "quien" y "donde" no son metadatos, son parte del dato.
            NamePicker(
                label = "Observer",
                value = e.person,
                options = s.people,
                onValue = { v -> vm.update(immediate = false) { it.copy(person = v) } },
                onRemember = { vm.rememberName(NameList.PEOPLE, it) },
                onRemove = { vm.removeName(NameList.PEOPLE, it) },
                onClearAll = { vm.clearNames(NameList.PEOPLE) },
                tag = "fb-person")

            TimestampRow("Created", e.createdEpochMillis,
                         onChange = { ms -> vm.update { it.copy(createdEpochMillis = ms) } },
                         tag = "fb-created")

            PositionField(
                position = e.position,
                request = s.positionRequest,
                savedPoints = s.savedPoints,
                onUsePhone = { vm.requestPhonePosition() },
                onCancelPhone = { vm.cancelPositionRequest() },
                onUsePoint = { vm.usePoint(it) },
                onClear = { vm.clearPosition() },
                onNeedPoints = { vm.refreshSavedPoints() })

            HorizontalDivider()

            when (e.type) {
                EntryType.NOTE -> NoteEditor(vm, e)
                EntryType.STAKE -> StakeEditor(vm, s, e, onGnssFinished = { revisar(it) })
                EntryType.GNSS -> GnssEntryEditor(vm, s, e, onFinished = { revisar() })
                EntryType.DENDRO -> DendroEditor(vm, s, e)
            }

            HorizontalDivider()

            // Done no hace nada que el atras no haga: cierra la entrada. Esta porque el atras
            // NO dice que lo anotado quedo guardado, y en terreno esa duda hace volver a
            // entrar a comprobarlo. El boton lo afirma, y ademas es el sitio donde uno mira
            // cuando ha terminado, que es abajo del todo.
            Button(
                onClick = {
                    if (e.type == EntryType.GNSS) {
                        val pendiente = vm.missingInGnss()
                        if (pendiente.isNotEmpty()) { falta = pendiente; return@Button }
                    }
                    vm.close()
                },
                modifier = Modifier.fillMaxWidth().testTag("fb-done")) { Text("Done") }
            Text("Everything is saved as you type.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(24.dp))
        }
    }

    falta?.let { pendiente ->
        AlertDialog(
            onDismissRequest = { falta = null },
            modifier = Modifier.testTag("fb-missing-dialog"),
            title = { Text("Before you pack up") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This GNSS measurement is missing " +
                         pendiente.joinToString(" and ") + ".")
                    Text("Neither can be reconstructed afterwards, and the receiver is still " +
                         "on the point right now. The measurement is already saved either way.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { falta = null },
                           modifier = Modifier.testTag("fb-missing-fill")) { Text("Fill it in") }
            },
            dismissButton = {
                TextButton(onClick = { falta = null; vm.close() },
                           modifier = Modifier.testTag("fb-missing-skip")) {
                    Text("Leave it blank")
                }
            })
    }

    if (borrar) {
        AlertDialog(
            onDismissRequest = { borrar = false },
            modifier = Modifier.testTag("fb-delete-dialog"),
            title = { Text("Delete this entry?") },
            text = {
                Text(buildString {
                    append("“${e.title()}” and everything in it go with it, and this cannot " +
                           "be undone.")
                    val medios = e.mediaFiles().size
                    if (medios > 0) append(" That includes $medios photo(s) or audio note(s).")
                })
            },
            confirmButton = {
                TextButton(onClick = { borrar = false; vm.delete(e.id) },
                           modifier = Modifier.testTag("fb-delete-confirm")) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { borrar = false }) { Text("Cancel") } })
    }
}

/**
 * Elegir el destino del zip y escribirlo.
 *
 * Con SAF, igual que el CSV de la placa y los puntos de GPS: el usuario elige la carpeta en
 * una pantalla del sistema y la app no necesita ningun permiso de almacenamiento. El zip se
 * escribe DIRECTAMENTE sobre el flujo que devuelve, sin pasar por memoria: una campana con
 * doscientas fotos son cientos de megabytes.
 */
@Composable
private fun ExportDialog(vm: FieldbookViewModel, s: FieldbookUiState, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var todo by remember { mutableStateOf(false) }

    val guardador = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        onClose()
        if (uri == null) return@rememberLauncherForActivityResult
        val salida = runCatching { ctx.contentResolver.openOutputStream(uri) }.getOrNull()
        if (salida == null) { vm.report("Could not write there."); return@rememberLauncherForActivityResult }
        vm.export(salida, FieldbookMedia(vm.store!!), todo)
    }

    val queCampana = s.viewingCampaign ?: s.activeCampaign
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("fb-export-dialog"),
        title = { Text("Export the notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !todo, onClick = { todo = false },
                               label = { Text(queCampana?.displayName() ?: "Current") },
                               modifier = Modifier.testTag("fb-export-scope-campaign"))
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = todo, onClick = { todo = true },
                               label = { Text("Everything") },
                               modifier = Modifier.testTag("fb-export-scope-all"))
                }
                Text("One ZIP with three CSV files (GNSS measurements, stake readings with " +
                     "their ablation rates, dendro samples), an ODT document with every note " +
                     "in chronological order, and the photos and audio in folders named after " +
                     "each point.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("You pick the folder in the next screen.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { guardador.launch(vm.exportName(todo)) },
                       modifier = Modifier.testTag("fb-export-go")) { Text("Choose folder") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

// ------------------------------------- nota general -------------------------------------

/**
 * Una nota general: una sucesion de anotaciones fechadas.
 *
 * No es un campo de texto con adjuntos colgando. Lo que se hace en terreno es escribir algo,
 * seguir andando, y media hora despues anadir otra cosa a la misma observacion; con un solo
 * campo esas dos anotaciones quedarian con la misma hora, que es falso.
 */
@Composable
private fun NoteEditor(vm: FieldbookViewModel, e: FieldEntry) {
    val reproductor = remember { AudioNotePlayer() }
    val grabador = remember { AudioNoteRecorder() }
    DisposableEffect(Unit) {
        onDispose { reproductor.stop(); grabador.cancel() }
    }
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) },
        onAdded = { nombres ->
            nombres.forEach { vm.addNoteItem(NoteItemKind.PHOTO, file = it) }
        })

    // El titulo es OPCIONAL a proposito. Exigirlo seria un campo mas que rellenar antes de
    // poder anotar nada; vacio, la lista usa las primeras palabras del texto, que casi siempre
    // es lo que uno habria escrito como titulo.
    OutlinedTextField(
        value = e.title,
        onValueChange = { v -> vm.update(immediate = false) { it.copy(title = v) } },
        label = { Text("Title") },
        placeholder = { Text("Optional — the first words of the note are used if left blank") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("fb-note-title"))

    Text("Entries", style = MaterialTheme.typography.titleSmall)

    e.items.forEachIndexed { i, item ->
        Card(Modifier.fillMaxWidth().testTag("fb-item-$i")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimestampRow(
                    label = when (item.kind) {
                        NoteItemKind.TEXT -> "Text"
                        NoteItemKind.PHOTO -> "Photo"
                        NoteItemKind.AUDIO -> "Audio"
                    },
                    millis = item.atEpochMillis,
                    onChange = { vm.setNoteItemTime(i, it) },
                    tag = "fb-item-$i-ts",
                    trailing = {
                        TextButton(onClick = { vm.removeNoteItem(i) },
                                   modifier = Modifier.testTag("fb-item-$i-remove")) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    })

                when (item.kind) {
                    NoteItemKind.TEXT -> OutlinedTextField(
                        value = item.text,
                        onValueChange = { vm.setNoteItemText(i, it) },
                        label = { Text("Observation") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("fb-item-$i-text"))

                    NoteItemKind.PHOTO -> PhotoStrip(
                        files = listOfNotNull(item.file),
                        resolve = { vm.mediaFile(it) },
                        onRemove = { vm.removeNoteItem(i) },
                        tag = "fb-item-$i-photo")

                    // El reproductor lleva su estado en Compose, asi que el boton de parar
                    // aparece solo en la linea que suena sin que haya que forzar nada.
                    NoteItemKind.AUDIO -> AudioRow(
                        file = item.file?.let { vm.mediaFile(it) },
                        durationMillis = item.durationMillis,
                        player = reproductor,
                        onChanged = {})
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.addNoteItem(NoteItemKind.TEXT) },
                   modifier = Modifier.testTag("fb-add-text")) { Text("Add text") }
    }
    PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "fb-note-photo")
    RecordButton(
        recorder = grabador,
        newFile = { vm.newMediaFile(it) },
        onFinished = { f, ms ->
            vm.addNoteItem(NoteItemKind.AUDIO, file = f.name, durationMillis = ms)
        },
        onError = { vm.report(it) })
}

// ------------------------------------- la explicacion -------------------------------------

@Composable
private fun FieldbookExplained(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("fb-explained"),
        title = { Text("About the field notebook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                   verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Parrafo("Everything stays here",
                        "Entries, photos and audio notes live in this app's own folder on " +
                        "this phone. Nothing is uploaded anywhere, and nothing leaves the " +
                        "phone on its own.")
                Parrafo("Times",
                        "Every entry is stamped when you create it, and each item you add " +
                        "inside it gets its own stamp. All of them can be corrected " +
                        "afterwards — the stamp is what you say it is, not what the phone " +
                        "guessed.")
                Parrafo("People, receivers and species",
                        "Names you type are remembered so you can pick them next time. " +
                        "Removing a name from a list only stops it being offered: " +
                        "measurements already made with it keep showing it.")
                Parrafo("Campaigns",
                        "Entries belong to the campaign that was open when you made them. " +
                        "Finishing a campaign names it and moves it to Archived campaigns, " +
                        "which empties the list for the next one. Nothing is deleted and you " +
                        "can reopen a campaign later.")
                Parrafo("Antenna height",
                        "Without it a GNSS height means nothing: the receiver measures where " +
                        "its own phase centre is, not where the point is. It cannot be " +
                        "reconstructed afterwards, so the app asks for it when you end a " +
                        "measurement — while the tripod is still standing.")
                Parrafo("Satellites in view",
                        "While a measurement runs, the phone's own receiver reports how many " +
                        "satellites of each constellation it sees and how many it is using. " +
                        "It is there to decide whether to stay longer or pack up early. " +
                        "Those are the phone's numbers, not the geodetic receiver's.")
                Parrafo("Exporting",
                        "One ZIP with three CSV files, an ODT document with every note in " +
                        "chronological order, and the photos and audio in folders named " +
                        "after each point. You can export the current campaign, an archived " +
                        "one, or everything.")
                Parrafo("Ablation rate",
                        "Between two consecutive stake readings the rate is the change in " +
                        "exposed height divided by the days between them, in cm/day. " +
                        "Positive means the surface went down and more stake is showing; " +
                        "negative means accumulation.")
                Parrafo("What the rate is not",
                        "It is surface change in centimetres of ice or snow, not water " +
                        "equivalent — that would need the density, which is not measured " +
                        "here. And if a stake is re-drilled, the reading before and the " +
                        "reading after are not comparable: start a new stake entry instead, " +
                        "or the rate will describe the drilling and not the melt.")
                Parrafo("The GNSS timer",
                        "The alarm rings at the planned time even with the screen off, " +
                        "because a measurement runs for hours with the phone in a pocket. " +
                        "It does NOT end the measurement: the end time is when you actually " +
                        "lift the receiver, which is the only time worth recording.")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun Parrafo(titulo: String, cuerpo: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        Text(cuerpo, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
