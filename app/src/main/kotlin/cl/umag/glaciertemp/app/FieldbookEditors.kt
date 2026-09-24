package cl.umag.glaciertemp.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.umag.glaciertemp.core.fieldbook.*

// ===================================== medicion GNSS =====================================

/**
 * El panel de una medicion GNSS: receptor, los dos instantes y el cronometro.
 *
 * Es el mismo en la entrada de punto suelto y en la medicion de una baliza, asi que vive aqui
 * una sola vez. La alternativa --dos copias-- significaria que arreglar el cronometro en una
 * deja la otra como estaba, y la que se quedaria sin arreglar es siempre la de la baliza,
 * porque se usa menos.
 *
 * LAS MARCAS SON EDITABLES incluso con la medicion en marcha, y la duracion se RECALCULA
 * siempre a partir de ellas en vez de guardarse. Un dato derivado que ademas se almacena es un
 * dato que puede contradecir a sus fuentes en cuanto alguien corrija una hora a mano, que es
 * justo el caso que la especificacion pide que funcione.
 */
@Composable
fun GnssPanel(
    session: GnssSession,
    receivers: List<String>,
    /** Lo que se lee en la notificacion del sistema mientras corre. */
    notificationLabel: String,
    plannedDurations: List<Int>,
    formatPlanned: (Int) -> String,
    /** El segundo argumento dice si hay que escribir ya: lo tecleado, no; lo demas, si. */
    onSession: (GnssSession, Boolean) -> Unit,
    onRememberReceiver: (String) -> Unit,
    onRemoveReceiver: (String) -> Unit,
    onClearReceivers: () -> Unit,
    /** El cielo de ahora mismo, o null si no se esta mirando. */
    sky: SkyView?,
    onWatchSky: () -> Unit,
    onStopSky: () -> Unit,
    /** Se llama tras registrar el termino, para que quien manda revise que no falte nada. */
    onFinished: () -> Unit = {},
    tag: String,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ahora = rememberTicker(session.running)
    var menuDuracion by remember { mutableStateOf(false) }

    // Android 13+ no muestra la notificacion sin este permiso, y sin notificacion no hay
    // servicio en primer plano visible. Se pide al ir a medir, que es cuando se entiende.
    val permisoAviso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    fun avisarServicio(s: GnssSession) {
        val inicio = s.startEpochMillis
        if (s.running && inicio != null) {
            GnssTimer.start(ctx, notificationLabel, inicio, s.plannedEndEpochMillis)
        } else {
            GnssTimer.stop(ctx)
        }
    }

    // Vuelve a anunciar una medicion que seguia en marcha al reabrir la app. El servicio no
    // rearma una alarma ya vencida, asi que reabrir despues de que sonara no la hace sonar
    // otra vez.
    LaunchedEffect(session.running, session.startEpochMillis, session.plannedMinutes) {
        if (session.running) avisarServicio(session)
    }

    // El cielo se mira solo mientras la medicion corre Y el panel esta en pantalla. Atarlo a
    // la medicion entera dejaria el GPS del telefono encendido tres horas, y el telefono es
    // el que lleva el cronometro.
    DisposableEffect(session.running) {
        if (session.running) onWatchSky()
        onDispose { onStopSky() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NamePicker(
            label = "GNSS receiver",
            value = session.receiver,
            options = receivers,
            onValue = { onSession(session.copy(receiver = it), false) },
            onRemember = onRememberReceiver,
            onRemove = onRemoveReceiver,
            onClearAll = onClearReceivers,
            tag = "$tag-receiver")

        // La altura de antena va ARRIBA, junto al receptor, y no al final entre los botones:
        // es parte de como esta montado el equipo, se mide una vez al plantarlo, y es el dato
        // que mas veces se olvida. Enterrado abajo se olvida mas.
        NumberField("Antenna height", session.antennaHeightCm,
                    onValue = { v -> onSession(session.copy(antennaHeightCm = v), false) },
                    suffix = "cm", modifier = Modifier.fillMaxWidth(),
                    tag = "$tag-antenna")

        TimestampRow("Measurement start", session.startEpochMillis,
                     onChange = { ms ->
                         val s = session.copy(startEpochMillis = ms)
                         onSession(s, true); avisarServicio(s)
                     },
                     tag = "$tag-start")
        TimestampRow("Measurement end", session.endEpochMillis,
                     onChange = { ms ->
                         val s = session.copy(endEpochMillis = ms)
                         onSession(s, true); avisarServicio(s)
                     },
                     tag = "$tag-end")

        // La duracion programada se elige UNA VEZ EMPEZADA, como pide la especificacion: es
        // una decision sobre cuanto se va a estar aqui, y eso se sabe con el tripode ya
        // puesto. Tambien se puede cambiar a mitad, y la alarma se reprograma.
        if (session.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { menuDuracion = true },
                                   modifier = Modifier.testTag("$tag-planned")) {
                        Text(session.plannedMinutes?.let { formatPlanned(it) }
                             ?: "No planned duration")
                        Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(menuDuracion, onDismissRequest = { menuDuracion = false }) {
                        DropdownMenuItem(
                            text = { Text("No planned duration") },
                            onClick = {
                                menuDuracion = false
                                val s = session.copy(plannedMinutes = null)
                                onSession(s, true); avisarServicio(s)
                            },
                            modifier = Modifier.testTag("$tag-planned-none"))
                        plannedDurations.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(formatPlanned(m)) },
                                onClick = {
                                    menuDuracion = false
                                    val s = session.copy(plannedMinutes = m)
                                    onSession(s, true); avisarServicio(s)
                                },
                                modifier = Modifier.testTag("$tag-planned-$m"))
                        }
                    }
                }
            }
        }

        // ------------------------------- cronometro -------------------------------
        val inicio = session.startEpochMillis
        when {
            session.running && inicio != null -> {
                val transcurrido = (ahora - inicio).coerceAtLeast(0)
                val vence = session.plannedEndEpochMillis
                Column(Modifier.fillMaxWidth()
                           .background(MaterialTheme.colorScheme.surfaceVariant)
                           .padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Dato("Elapsed", formatElapsed(transcurrido), "$tag-elapsed")
                    if (vence != null) {
                        val restante = vence - ahora
                        if (restante > 0) {
                            Dato("Remaining", formatElapsed(restante), "$tag-remaining")
                        } else {
                            Text("Planned time is up — the receiver can be collected.",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.error,
                                 modifier = Modifier.testTag("$tag-due"))
                            Text("The measurement is still running: End records the moment " +
                                 "you actually lift it.",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // Callar y terminar son cosas distintas, y hasta ahora solo se
                            // podia lo segundo desde aqui. Quien llega al receptor con la
                            // alarma sonando quiere silencio YA, pero End tiene que
                            // pulsarse cuando de verdad se levanta el equipo: si silenciar
                            // obliga a terminar, la hora de fin queda mal para no hacer
                            // ruido, que es cambiar un dato por una molestia.
                            OutlinedButton(
                                onClick = { GnssTimer.silence(ctx) },
                                modifier = Modifier.testTag("$tag-silence")) {
                                Text("Silence alarm")
                            }
                        }
                    }
                }
            }
            session.durationMillis != null ->
                Dato("Total duration", formatDuration(session.durationMillis!!),
                     "$tag-duration")
        }

        if (session.running) SkyPanel(sky, tag)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        permisoAviso.launch("android.permission.POST_NOTIFICATIONS")
                    }
                    val s = session.copy(startEpochMillis = System.currentTimeMillis(),
                                         endEpochMillis = null)
                    onSession(s, true); avisarServicio(s)
                },
                enabled = !session.running,
                modifier = Modifier.testTag("$tag-go")) {
                Text(if (session.startEpochMillis == null) "Measurement Start" else "Restart")
            }
            Button(
                onClick = {
                    // La hora PRIMERO y el aviso despues. La hora de termino es el unico dato
                    // que no se puede recuperar --es el instante en que se levanto el
                    // receptor-- asi que nada puede impedir registrarla, y menos un dialogo.
                    val s = session.copy(endEpochMillis = System.currentTimeMillis())
                    onSession(s, true); avisarServicio(s)
                    onFinished()
                },
                enabled = session.running,
                modifier = Modifier.testTag("$tag-stop")) { Text("Measurement End") }
        }
    }
}

/**
 * Cuantos satelites hay ahora mismo, por constelacion.
 *
 * SIRVE PARA DECIDIR SI ALARGAR O ACORTAR la ocupacion, que es la unica razon de que este
 * aqui. Por eso se separan los VISIBLES de los USADOS: ver doce y usar cinco no significa lo
 * mismo que ver seis y usar seis, y un solo numero borraria justo esa diferencia.
 *
 * Es el receptor del TELEFONO, no el geodesico que esta midiendo el punto. Mira el mismo
 * cielo desde el mismo sitio --que es lo que hace falta para decidir-- pero sus cuentas no
 * son las del otro aparato, y la pantalla lo dice para que nadie las anote como si lo fueran.
 */
@Composable
private fun SkyPanel(sky: SkyView?, tag: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Satellites in view", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f))
            sky?.let {
                Text("${it.totalUsed} used / ${it.totalVisible} visible",
                     style = MaterialTheme.typography.labelMedium,
                     modifier = Modifier.testTag("$tag-sky-total"))
            }
        }
        when {
            sky == null -> Text("Looking…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("$tag-sky-waiting"))
            sky.constellations.isEmpty() -> Text(
                "No satellite data from this phone. It needs precise location permission and " +
                "the GPS turned on; indoors it may never arrive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$tag-sky-none"))
            else -> {
                sky.constellations.forEach { c ->
                    Row(Modifier.fillMaxWidth().testTag("$tag-sky-${c.name}")) {
                        Text(c.name, Modifier.weight(1f),
                             style = MaterialTheme.typography.bodySmall)
                        Text("${c.used} / ${c.visible}",
                             style = MaterialTheme.typography.bodySmall)
                    }
                }
                sky.medianCn0?.let {
                    Text("Median C/N0 of the satellites in use: " +
                         "%.0f dB-Hz".format(java.util.Locale.ROOT, it),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("From this phone's own receiver, not the one on the point.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: String, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(etiqueta, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.weight(1f))
        Text(valor, style = MaterialTheme.typography.titleMedium,
             modifier = Modifier.testTag(tag))
    }
}

// ================================== punto GNSS suelto ==================================

/**
 * Un campo de nombre que avisa si ya hay otra entrada del mismo tipo que se llama igual.
 *
 * AVISA, NO IMPIDE. Un nombre repetido casi siempre es un descuido --dos balizas "B1" salen
 * como dos filas indistinguibles en el CSV-- pero decidir por el usuario que no puede
 * escribirlo es peor: en terreno puede haber una razon que el programa no conoce, y dejar
 * sin salida a quien esta con las manos frias y el viento en contra es una forma seria de
 * estorbar. El aviso se ve, el camino sigue abierto.
 */
@Composable
private fun UniqueNameField(
    vm: FieldbookViewModel, e: FieldEntry, type: EntryType,
    value: String, label: String, tag: String,
    help: String? = null,
    onValue: (String) -> Unit,
) {
    // Se recalcula al cambiar el texto y no en cada recomposicion: la comprobacion lee el
    // almacen, y hacerlo al repintar seria ir al disco por cada fotograma.
    val repetido = remember(value, e.id) { vm.nameClash(type, value, e.id) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        isError = repetido,
        supportingText = {
            when {
                repetido -> Text(
                    "Another ${tipoEnPalabras(type)} is already called “${value.trim()}”. " +
                    "Use a different name so they can be told apart later.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("$tag-duplicate"))
                help != null -> Text(help)
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

private fun tipoEnPalabras(t: EntryType): String = when (t) {
    EntryType.STAKE -> "stake"
    EntryType.GNSS -> "GNSS point"
    EntryType.DENDRO -> "sample"
    EntryType.NOTE -> "entry"
}

@Composable
fun GnssEntryEditor(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
                    onFinished: () -> Unit = {}) {
    val sesion = e.gnss ?: GnssSession()
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) }, onAdded = { vm.addPhotos(it) })

    UniqueNameField(
        vm = vm, e = e, type = EntryType.GNSS,
        value = e.pointName, label = "Point name", tag = "fb-point-name",
        onValue = { v -> vm.update(immediate = false) { it.copy(pointName = v) } })

    GnssPanel(
        session = sesion,
        receivers = s.receivers,
        notificationLabel = e.pointName.ifBlank { "GNSS point" },
        plannedDurations = vm.plannedDurations,
        formatPlanned = vm::formatPlanned,
        onSession = { g, ya -> vm.update(immediate = ya) { it.copy(gnss = g) } },
        onRememberReceiver = { vm.rememberName(NameList.RECEIVERS, it) },
        onRemoveReceiver = { vm.removeName(NameList.RECEIVERS, it) },
        onClearReceivers = { vm.clearNames(NameList.RECEIVERS) },
        sky = s.sky,
        onWatchSky = { vm.watchSky() },
        onStopSky = { vm.stopWatchingSky() },
        onFinished = onFinished,
        tag = "fb-gnss")

    HorizontalDivider()
    Text("Photos", style = MaterialTheme.typography.titleSmall)
    PhotoStrip(e.photos, resolve = { vm.mediaFile(it) },
               onRemove = { vm.removePhoto(it) }, tag = "fb-gnss-photos")
    PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "fb-gnss-photo")
}

// ====================================== balizas ======================================

@Composable
fun StakeEditor(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
                onGnssFinished: (Long) -> Unit = {}) {
    val mediciones = Ablation.chronological(e.measurements)
    val tasas = Ablation.rates(e.measurements)
    var abierta by remember { mutableStateOf<Long?>(null) }

    UniqueNameField(
        vm = vm, e = e, type = EntryType.STAKE,
        value = e.stakeName, label = "Stake name or ID", tag = "fb-stake-name",
        onValue = { v -> vm.update(immediate = false) { it.copy(stakeName = v) } })

    NumberField("Total stake length", e.stakeLengthCm,
                onValue = { v -> vm.update(immediate = false) { it.copy(stakeLengthCm = v) } },
                suffix = "cm", modifier = Modifier.fillMaxWidth(), tag = "fb-stake-length")

    // El aviso que solo se puede dar teniendo los dos numeros delante: una baliza cuya parte
    // expuesta se acerca a su longitud total esta a punto de caerse, y con ella se pierde la
    // serie. Es la clase de cosa que se ve en la oficina y no en el hielo.
    val ultima = mediciones.lastOrNull()?.exposedHeightCm
    val total = e.stakeLengthCm
    if (ultima != null && total != null && total > 0 && ultima > 0.8 * total) {
        Text("The exposed height is ${"%.0f".format(java.util.Locale.ROOT, 100 * ultima / total)} % " +
             "of the stake. It may fall over before the next visit.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.error,
             modifier = Modifier.testTag("fb-stake-warning"))
    }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Readings", style = MaterialTheme.typography.titleSmall,
             modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.addStakeMeasurement(e.person) },
                   modifier = Modifier.testTag("fb-stake-add")) { Text("Add reading") }
    }

    if (mediciones.isEmpty()) {
        Text("No readings yet.", style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("fb-stake-empty"))
    } else {
        // Cabecera de la tabla. Los mismos pesos que las filas, escritos una vez.
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Celda("When", 1.5f, cabecera = true)
            Celda("Height", 1f, cabecera = true)
            Celda("cm/day", 1f, cabecera = true)
            // Hueco del ancho de la flecha, para que las cabeceras queden sobre sus columnas.
            Spacer(Modifier.width(24.dp))
        }
        HorizontalDivider()

        mediciones.forEachIndexed { i, m ->
            val tasa = tasas[i]
            val desplegada = abierta == m.atEpochMillis
            Row(Modifier.fillMaxWidth()
                    .clickable { abierta = if (desplegada) null else m.atEpochMillis }
                    .padding(vertical = 8.dp)
                    .testTag("fb-reading-row-$i"),
                verticalAlignment = Alignment.CenterVertically) {
                Celda(formatWhen(m.atEpochMillis), 1.5f)
                Celda(m.exposedHeightCm?.let { formatNumber(it) } ?: "—", 1f)
                // Una celda vacia y no un cero: un cero seria una tasa medida de cero, que
                // afirma algo sobre el glaciar. Lo que hay es que no se puede calcular.
                Celda(tasa?.let { "%+.1f".format(java.util.Locale.ROOT, it) } ?: "—", 1f,
                      tag = "fb-rate-$i")
                // La flecha es lo unico que dice que la fila se abre. Sin ella la tabla se
                // lee como una tabla y nadie descubre que hay una foto y una medicion GNSS
                // detras de cada linea.
                Icon(
                    if (desplegada) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (desplegada) "Collapse reading" else "Expand reading",
                    modifier = Modifier.size(24.dp).testTag("fb-reading-arrow-$i"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (abierta == m.atEpochMillis) {
                ReadingDetail(vm, s, e, m, index = i, onGnssFinished = onGnssFinished)
            }
            HorizontalDivider()
        }
        Text("Rate is the change in exposed height over the days between two consecutive " +
             "readings. Positive means ablation.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.Celda(texto: String, peso: Float, cabecera: Boolean = false,
                           tag: String? = null) {
    Text(texto,
         style = if (cabecera) MaterialTheme.typography.labelMedium
                 else MaterialTheme.typography.bodyMedium,
         fontWeight = if (cabecera) FontWeight.Bold else null,
         color = if (cabecera) MaterialTheme.colorScheme.onSurfaceVariant
                 else MaterialTheme.colorScheme.onSurface,
         modifier = Modifier.weight(peso)
             .then(tag?.let { Modifier.testTag(it) } ?: Modifier))
}

/** Lo que hay detras de una fila de la tabla: todo lo editable de esa medicion. */
@Composable
private fun ReadingDetail(
    vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
    m: StakeMeasurement, index: Int, onGnssFinished: (Long) -> Unit,
) {
    // La medicion se referencia por su INSTANTE y no por el indice de la fila: corregir una
    // hora reordena la tabla, y un indice capturado antes editaria otra medicion.
    val clave = m.atEpochMillis
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) },
        onAdded = { nuevas ->
            vm.updateStakeMeasurement(clave) { it.copy(photos = it.photos + nuevas) }
        })

    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 12.dp)
               .testTag("fb-reading-detail-$index"),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {

        TimestampRow("Measured at", m.atEpochMillis,
                     onChange = { ms ->
                         vm.updateStakeMeasurement(clave) { it.copy(atEpochMillis = ms) }
                     },
                     tag = "fb-reading-$index-ts")

        NamePicker(
            label = "Measured by",
            value = m.person,
            options = s.people,
            onValue = { v ->
                vm.updateStakeMeasurement(clave, immediate = false) { it.copy(person = v) }
            },
            onRemember = { vm.rememberName(NameList.PEOPLE, it) },
            onRemove = { vm.removeName(NameList.PEOPLE, it) },
            onClearAll = { vm.clearNames(NameList.PEOPLE) },
            tag = "fb-reading-$index-person")

        NumberField("Exposed height", m.exposedHeightCm,
                    onValue = { v ->
                        vm.updateStakeMeasurement(clave, immediate = false) {
                            it.copy(exposedHeightCm = v)
                        }
                    },
                    suffix = "cm", modifier = Modifier.fillMaxWidth(),
                    tag = "fb-reading-$index-height")

        Text("Photos", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        PhotoStrip(m.photos, resolve = { vm.mediaFile(it) },
                   onRemove = { f ->
                       vm.updateStakeMeasurement(clave) { it.copy(photos = it.photos - f) }
                   },
                   tag = "fb-reading-$index-photos")
        PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "fb-reading-$index-photo")

        // La medicion GNSS es OPCIONAL y por eso esta detras de un interruptor: desplegar los
        // cuatro campos en todas las filas haria creer que falta algo en las que no la llevan.
        val tieneGnss = m.gnss != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("High-precision GNSS position", Modifier.weight(1f),
                 style = MaterialTheme.typography.labelMedium)
            Switch(checked = tieneGnss,
                   onCheckedChange = { activo ->
                       vm.updateStakeMeasurement(clave) {
                           it.copy(gnss = if (activo) (it.gnss ?: GnssSession()) else null)
                       }
                   },
                   modifier = Modifier.testTag("fb-reading-$index-gnss-toggle"))
        }
        m.gnss?.let { g ->
            GnssPanel(
                session = g,
                receivers = s.receivers,
                notificationLabel = "Stake ${e.stakeName.ifBlank { "measurement" }}",
                plannedDurations = vm.plannedDurations,
                formatPlanned = vm::formatPlanned,
                onSession = { nueva, ya ->
                    vm.updateStakeMeasurement(clave, immediate = ya) { it.copy(gnss = nueva) }
                },
                onRememberReceiver = { vm.rememberName(NameList.RECEIVERS, it) },
                onRemoveReceiver = { vm.removeName(NameList.RECEIVERS, it) },
                onClearReceivers = { vm.clearNames(NameList.RECEIVERS) },
                sky = s.sky,
                onWatchSky = { vm.watchSky() },
                onStopSky = { vm.stopWatchingSky() },
                onFinished = { onGnssFinished(clave) },
                tag = "fb-reading-$index-gnss")
        }

        TextButton(onClick = { vm.removeStakeMeasurement(clave) },
                   modifier = Modifier.testTag("fb-reading-$index-remove")) {
            Text("Remove this reading", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ================================ muestra dendrocronologica ================================

@Composable
fun DendroEditor(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry) {
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) }, onAdded = { vm.addPhotos(it) })

    UniqueNameField(
        vm = vm, e = e, type = EntryType.DENDRO,
        value = e.sampleLabel, label = "Sample label", tag = "fb-dendro-label",
        help = "The same code that is physically written on the sample.",
        onValue = { v -> vm.update(immediate = false) { it.copy(sampleLabel = v) } })

    // La especie funciona igual que las personas y los receptores: se escribe una vez y queda
    // en el desplegable. En una campana se muestrean tres o cuatro especies y se teclean
    // decenas de veces, asi que el ahorro es el mismo -- y tambien el efecto de que dos
    // muestras de la misma especie no queden con dos grafias distintas.
    NamePicker(
        label = "Species",
        value = e.species,
        options = s.species,
        onValue = { v -> vm.update(immediate = false) { it.copy(species = v) } },
        onRemember = { vm.rememberName(NameList.SPECIES, it) },
        onRemove = { vm.removeName(NameList.SPECIES, it) },
        onClearAll = { vm.clearNames(NameList.SPECIES) },
        tag = "fb-dendro-species")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Sampling height", e.samplingHeightCm,
                    onValue = { v -> vm.update(immediate = false) { it.copy(samplingHeightCm = v) } },
                    suffix = "cm", modifier = Modifier.weight(1f),
                    tag = "fb-dendro-height")
        NumberField("Trunk perimeter", e.trunkPerimeterCm,
                    onValue = { v -> vm.update(immediate = false) { it.copy(trunkPerimeterCm = v) } },
                    suffix = "cm", modifier = Modifier.weight(1f),
                    tag = "fb-dendro-perimeter")
    }
    Text("Perimeter measured at the same height the sample was taken.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)

    OutlinedTextField(
        value = e.notes,
        onValueChange = { v -> vm.update(immediate = false) { it.copy(notes = v) } },
        label = { Text("Notes") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("fb-dendro-notes"))

    HorizontalDivider()
    Text("Photos", style = MaterialTheme.typography.titleSmall)
    PhotoStrip(e.photos, resolve = { vm.mediaFile(it) },
               onRemove = { vm.removePhoto(it) }, tag = "fb-dendro-photos")
    PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "fb-dendro-photo")
}
