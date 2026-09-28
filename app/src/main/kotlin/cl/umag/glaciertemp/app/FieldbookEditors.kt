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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.drawText
import cl.umag.glaciertemp.core.sensors.Angles
import cl.umag.glaciertemp.core.sensors.AlbedoRun
import cl.umag.glaciertemp.core.sensors.Compass
import cl.umag.glaciertemp.core.sensors.Reversal
import cl.umag.glaciertemp.core.sensors.HorizonPoints
import cl.umag.glaciertemp.core.sensors.HorizonProfile
import cl.umag.glaciertemp.core.sensors.Shielding

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
    EntryType.COSMO -> "cosmogenic sample"
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

// =========================== muestra para isotopos cosmogenicos ===========================

/**
 * Una roca muestreada para datar su exposicion.
 *
 * TODO LO QUE AQUI SE ESCRIBE ES IRRECUPERABLE. La edad que saldra de esta muestra depende de
 * cosas que solo se ven en el sitio --si el bloque pudo moverse o estar enterrado, si la
 * superficie conserva el pulido glaciar, cuanto cielo tapa el relieve-- y ninguna se puede
 * reconstruir en el laboratorio. Un analisis impecable sobre una muestra mal descrita da una
 * edad precisa y equivocada. Por eso los campos son largos y no hay ninguno obligatorio: lo
 * que hace falta es que sea comodo escribir, no que el formulario quede completo.
 */
@Composable
fun CosmoEditor(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
                onMapHorizon: () -> Unit) {
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) }, onAdded = { vm.addPhotos(it) })
    val c = e.cosmo ?: CosmoSample()

    /** Cambia la ficha de la muestra sin tener que repetir el copy de la entrada entera. */
    fun edita(inmediato: Boolean = false, f: (CosmoSample) -> CosmoSample) {
        vm.update(immediate = inmediato) { it.copy(cosmo = f(it.cosmo ?: CosmoSample())) }
    }

    UniqueNameField(
        vm = vm, e = e, type = EntryType.COSMO,
        value = e.cosmoName, label = "Sample name", tag = "fb-cosmo-name",
        help = "The same code that is written on the bag.",
        onValue = { v -> vm.update(immediate = false) { it.copy(cosmoName = v) } })

    OutlinedTextField(
        value = c.site,
        onValueChange = { v -> edita { it.copy(site = v) } },
        label = { Text("Site") },
        supportingText = { Text("The site and the moraine in general.") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag("fb-cosmo-site"))

    OutlinedTextField(
        value = c.place,
        onValueChange = { v -> edita { it.copy(place = v) } },
        label = { Text("Place") },
        supportingText = { Text("The boulder's surroundings: anything suggesting it has " +
                                "moved or been buried, erosion around it, and so on.") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("fb-cosmo-place"))

    HorizontalDivider()
    Text("Boulder description", style = MaterialTheme.typography.titleSmall)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Max height", c.heightMaxM,
                    onValue = { v -> edita { it.copy(heightMaxM = v) } },
                    suffix = "m", modifier = Modifier.weight(1f), tag = "fb-cosmo-hmax")
        NumberField("Min height", c.heightMinM,
                    onValue = { v -> edita { it.copy(heightMinM = v) } },
                    suffix = "m", modifier = Modifier.weight(1f), tag = "fb-cosmo-hmin")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Long axis", c.longAxisM,
                    onValue = { v -> edita { it.copy(longAxisM = v) } },
                    suffix = "m", modifier = Modifier.weight(1f), tag = "fb-cosmo-long")
        NumberField("Short axis", c.shortAxisM,
                    onValue = { v -> edita { it.copy(shortAxisM = v) } },
                    suffix = "m", modifier = Modifier.weight(1f), tag = "fb-cosmo-short")
    }

    OutlinedTextField(
        value = c.boulder,
        onValueChange = { v -> edita { it.copy(boulder = v) } },
        label = { Text("Boulder description") },
        supportingText = { Text("The boulder itself, lithology, and so on.") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("fb-cosmo-boulder"))

    OutlinedTextField(
        value = c.surface,
        onValueChange = { v -> edita { it.copy(surface = v) } },
        label = { Text("Surface description") },
        supportingText = { Text("The surface the sample is taken from: erosion marks, " +
                                "preserved glacial polish, cracks, lichen cover, " +
                                "vegetation — anything that might be useful.") },
        minLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("fb-cosmo-surface"))

    HorizontalDivider()
    Text("Shielding", style = MaterialTheme.typography.titleSmall)

    var midiendoBuzamiento by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Strike", c.strikeDeg,
                    onValue = { v -> edita(inmediato = true) { it.copy(strikeDeg = v) } },
                    suffix = "°", modifier = Modifier.weight(1f), tag = "fb-cosmo-strike")
        NumberField("Dip", c.dipDeg,
                    onValue = { v -> edita(inmediato = true) { it.copy(dipDeg = v) } },
                    suffix = "°", modifier = Modifier.weight(1f), tag = "fb-cosmo-dip")
    }
    // MEDIRLO ES MEJOR QUE ESTIMARLO: a ojo se falla diez grados con facilidad, y entre 40 y
    // 50 el factor cambia dos centesimas.
    OutlinedButton(onClick = { midiendoBuzamiento = true },
                   modifier = Modifier.testTag("fb-cosmo-measure-dip")) {
        Text("Measure with the phone")
    }
    Text("Dip is down to the right of the strike: strike 0, dip 45 falls 45° to the east.",
         style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant)

    // El horizonte que MANDA en la cuenta: el levantado a mano si lo hay, porque es el mas
    // preciso por punto; si no, el que barrio el telefono.
    val puntosManuales = remember(c.manualAzimuths, c.manualElevations) {
        c.manualAzimuths.indices
            .mapNotNull { k ->
                c.manualElevations.getOrNull(k)?.let { HorizonPoints.Point(c.manualAzimuths[k], it) }
            }
    }
    val perfilTelefono = remember(c.horizonDeg, c.horizonBinDeg) {
        c.horizonDeg.takeIf { it.isNotEmpty() }
            ?.let { HorizonProfile(it.toDoubleArray(), c.horizonBinDeg) }
    }

    // LOS DOS FACTORES, calculados por separado. Haber medido el horizonte dos veces solo
    // sirve si se pueden comparar; quedarse con uno tiraria la razon de haber medido dos.
    val deTelefono = remember(perfilTelefono, c.strikeDeg, c.dipDeg) {
        if (perfilTelefono == null) null
        else Shielding.compute(perfilTelefono, c.strikeDeg ?: 0.0, c.dipDeg ?: 0.0).factor
    }
    val deMano = remember(puntosManuales, c.strikeDeg, c.dipDeg) {
        if (puntosManuales.isEmpty()) null
        else {
            val porRelieve = HorizonPoints.toShieldingHorizon(puntosManuales)
            val porBuz = Shielding.dippingHorizon(c.strikeDeg ?: 0.0, c.dipDeg ?: 0.0)
            Shielding.factorOf(DoubleArray(Shielding.SECTORS) {
                maxOf(porRelieve[it], porBuz[it])
            })
        }
    }
    // Manda el levantamiento a mano cuando lo hay: es mas preciso por punto.
    val factor = deMano ?: deTelefono
        ?: c.dipDeg?.let { Shielding.compute(null, c.strikeDeg ?: 0.0, it).factor }
    LaunchedEffect(factor, deTelefono, deMano) {
        if (factor != c.shieldingFactor || deTelefono != c.shieldingFromPhone ||
            deMano != c.shieldingFromManual) {
            edita(inmediato = true) {
                it.copy(shieldingFactor = factor, shieldingFromPhone = deTelefono,
                        shieldingFromManual = deMano)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Shielding factor", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(factor?.let { "%.4f".format(it) } ?: "—",
                 style = MaterialTheme.typography.headlineSmall,
                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                 modifier = Modifier.testTag("fb-cosmo-factor"))
        }
        Button(onClick = onMapHorizon, modifier = Modifier.testTag("fb-cosmo-map")) {
            Text(if (perfilTelefono == null) "Map horizon" else "Re-map")
        }
    }

    // LOS DOS FACTORES, CUANDO HAY DOS MEDIDAS. Es la razon de poder meterlas a mano: que
    // coincidan dice que las dos valen; que difieran dice que una se equivoco, y eso hay que
    // saberlo antes de mandar la muestra al laboratorio.
    if (deMano != null && deTelefono != null) {
        Text("Phone sweep %.4f · hand survey %.4f. The hand survey is the one used."
                 .format(deTelefono, deMano),
             style = MaterialTheme.typography.bodySmall,
             color = if (kotlin.math.abs(deTelefono - deMano) > 0.01)
                         MaterialTheme.colorScheme.error
                     else MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("fb-cosmo-compare"))
    }

    // LOS DOS HORIZONTES EN EL MISMO GRAFICO. Antes se dibujaba solo el que mandaba, asi que
    // volver a barrer con el telefono no cambiaba nada en pantalla y parecia que el "Re-map"
    // no habia hecho nada. Superpuestos, ademas, se ve DONDE discrepan, que es mucho mas util
    // que saber que discrepan.
    val delTelefono = perfilTelefono?.let { HorizonPoints.fromProfile(it) } ?: emptyList()
    val paraDibujar = delTelefono.ifEmpty { puntosManuales }
    val segunda = if (delTelefono.isNotEmpty()) puntosManuales else emptyList()
    val colorMano = MaterialTheme.colorScheme.tertiary
    if (paraDibujar.isEmpty()) {
        Text("No horizon yet. Without it the factor only accounts for the dip of the " +
             "surface itself, not for the terrain around it.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.error,
             modifier = Modifier.testTag("fb-cosmo-horizon-state"))
    } else {
        // EL GRAFICO VIVE EN LA NOTA. Un horizonte como lista de setenta numeros no se puede
        // revisar; dibujado se ve de un vistazo si falta un trozo o si hay un pico absurdo,
        // que es justo lo que conviene notar estando todavia en el sitio.
        PerfilDeHorizonte(paraDibujar,
                          Modifier.fillMaxWidth().height(180.dp).testTag("fb-cosmo-chart"),
                          segunda = segunda, colorSegunda = colorMano)
        Text(buildString {
                 if (delTelefono.isNotEmpty())
                     append("${delTelefono.size} sectors of ${c.horizonBinDeg}°, phone sweep")
                 if (delTelefono.isNotEmpty() && puntosManuales.isNotEmpty()) append("   ·   ")
                 if (puntosManuales.isNotEmpty())
                     append("${puntosManuales.size} points by hand")
             },
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.testTag("fb-cosmo-horizon-state"))
        Text("Drag across the chart to read the azimuth.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        CopiarHorizonte(delTelefono, puntosManuales)
    }

    var editandoPuntos by remember { mutableStateOf(false) }
    TextButton(onClick = { editandoPuntos = true },
               modifier = Modifier.testTag("fb-cosmo-manual")) {
        Text(if (puntosManuales.isEmpty()) "Enter horizon by hand"
             else "Edit the hand survey")
    }

    if (midiendoBuzamiento) MedirRumboYBuzamiento(
        onDone = { r, b ->
            edita(inmediato = true) { it.copy(strikeDeg = r, dipDeg = b) }
            midiendoBuzamiento = false
        },
        onCancel = { midiendoBuzamiento = false })

    if (editandoPuntos) HorizonteAMano(
        azimutes = c.manualAzimuths, elevaciones = c.manualElevations,
        onDone = { az, el ->
            edita(inmediato = true) { it.copy(manualAzimuths = az, manualElevations = el) }
            editandoPuntos = false
        },
        onCancel = { editandoPuntos = false })

    HorizontalDivider()
    Text("Photos", style = MaterialTheme.typography.titleSmall)
    PhotoStrip(e.photos, resolve = { vm.mediaFile(it) },
               onRemove = { vm.removePhoto(it) }, tag = "fb-cosmo-photos")
    PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "fb-cosmo-photo")
}

// ----------------------- piezas del apantallamiento de una muestra -----------------------

/**
 * El horizonte dibujado, relleno hacia abajo.
 *
 * Lo de abajo es roca y lo de arriba cielo: con una linea suelta habria que pararse a pensar
 * cual es cual. Es el mismo dibujo que el del Horizon mapper, en pequeno y sin el sol: aqui
 * lo que se revisa es la MEDIDA --que no falte un trozo, que no haya un pico imposible-- y
 * eso conviene verlo estando todavia junto al bloque.
 */
@Composable
fun PerfilDeHorizonte(
    puntos: List<HorizonPoints.Point>,
    modifier: Modifier = Modifier,
    segunda: List<HorizonPoints.Point> = emptyList(),
    colorSegunda: Color = Color.Unspecified,
) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val relleno = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val borde = MaterialTheme.colorScheme.onSurface
    val fondo = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    val fondoCursor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val medidor = androidx.compose.ui.text.rememberTextMeasurer()
    val estilo = MaterialTheme.typography.labelSmall.copy(color = ejes)

    val orden = remember(puntos) { puntos.sortedBy { it.azimuth } }
    val orden2 = remember(segunda) { segunda.sortedBy { it.azimuth } }
    val maxEl = remember(orden, orden2) {
        (((orden + orden2).maxOfOrNull { it.elevation } ?: 10.0)
            .coerceAtLeast(10.0) / 10).toInt() * 10 + 10
    }

    // EL CURSOR DE ARRASTRE. Un perfil dibujado dice la forma; para leer "el cerro del
    // noreste sube a 33 grados" hace falta poner el dedo encima. Null cuando no se toca.
    var cursorX by remember { mutableStateOf<Float?>(null) }

    androidx.compose.foundation.Canvas(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { cursorX = it.x },
                onDragEnd = { cursorX = null },
                onDragCancel = { cursorX = null },
                onDrag = { cambio, _ -> cursorX = cambio.position.x })
        }
    ) {
        val izq = 34f * density
        val abajo = 18f * density
        val w = size.width - izq
        val h = size.height - abajo

        fun x(az: Double) = izq + (az / 360.0).toFloat() * w
        fun y(el: Double) = ((maxEl - el.coerceAtLeast(0.0)) / maxEl).toFloat() * h

        drawRect(fondo, androidx.compose.ui.geometry.Offset(izq, 0f),
                 androidx.compose.ui.geometry.Size(w, h))

        listOf(0, maxEl / 2, maxEl).forEach { g ->
            val yy = y(g.toDouble())
            drawLine(ejes.copy(alpha = if (g == 0) 0.7f else 0.25f),
                     androidx.compose.ui.geometry.Offset(izq, yy),
                     androidx.compose.ui.geometry.Offset(size.width, yy), 1.5f)
            val r = medidor.measure("$g°", estilo)
            drawText(r, topLeft = androidx.compose.ui.geometry.Offset(
                0f, yy - r.size.height / 2f))
        }

        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (az, n) ->
            val xx = x(az.toDouble())
            drawLine(ejes.copy(alpha = 0.4f),
                     androidx.compose.ui.geometry.Offset(xx, 0f),
                     androidx.compose.ui.geometry.Offset(xx, h), 1f)
            val r = medidor.measure(n, estilo)
            drawText(r, topLeft = androidx.compose.ui.geometry.Offset(
                xx - r.size.width / 2f, h + 1f))
        }

        if (orden.isEmpty()) return@Canvas
        // Se cierra el contorno dando la vuelta: el ultimo punto conecta con el primero por
        // el norte, que es lo que hace un horizonte y no una linea suelta.
        val cerrado = orden + HorizonPoints.Point(orden.first().azimuth + 360.0,
                                                  orden.first().elevation)
        val p = androidx.compose.ui.graphics.Path()
        p.moveTo(x(0.0), y(orden.last().elevation))
        cerrado.forEach { q -> p.lineTo(x(q.azimuth.coerceAtMost(360.0)), y(q.elevation)) }
        val relleno2 = androidx.compose.ui.graphics.Path().apply {
            addPath(p); lineTo(x(360.0), h); lineTo(x(0.0), h); close()
        }
        drawPath(relleno2, relleno)
        drawPath(p, borde, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
        // Los puntos, para distinguir ocho medidos a mano de setenta y dos barridos.
        if (orden.size <= 40) orden.forEach { q ->
            drawCircle(borde, radius = 3.5f,
                       center = androidx.compose.ui.geometry.Offset(x(q.azimuth), y(q.elevation)))
        }

        // La segunda serie, si la hay: la otra forma de medir el mismo horizonte.
        if (orden2.isNotEmpty()) {
            val cerrado2 = orden2 + HorizonPoints.Point(orden2.first().azimuth + 360.0,
                                                        orden2.first().elevation)
            val p2 = androidx.compose.ui.graphics.Path()
            p2.moveTo(x(0.0), y(orden2.last().elevation))
            cerrado2.forEach { q -> p2.lineTo(x(q.azimuth.coerceAtMost(360.0)), y(q.elevation)) }
            drawPath(p2, colorSegunda,
                     style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            if (orden2.size <= 40) orden2.forEach { q ->
                drawCircle(colorSegunda, radius = 4f,
                           center = androidx.compose.ui.geometry.Offset(x(q.azimuth),
                                                                        y(q.elevation)))
            }
        }

        cursorX?.let { cx ->
            val az = (((cx - izq) / w) * 360.0).coerceIn(0.0, 360.0)
            drawLine(borde, androidx.compose.ui.geometry.Offset(x(az), 0f),
                     androidx.compose.ui.geometry.Offset(x(az), h), 2f)
            val elev = orden.minByOrNull {
                kotlin.math.abs(cl.umag.glaciertemp.core.sensors.Angles.wrap(it.azimuth - az))
            }?.elevation
            val texto = "%.0f° %s".format(az, cl.umag.glaciertemp.core.sensors.Compass.cardinal(az)) +
                        (elev?.let { "   %.1f°".format(it) } ?: "")
            val r = medidor.measure(texto, estilo.copy(color = borde))
            val tx = (x(az) + 6f).coerceAtMost(size.width - r.size.width - 2f)
            drawRect(fondoCursor,
                     androidx.compose.ui.geometry.Offset(tx - 3f, 2f),
                     androidx.compose.ui.geometry.Size(r.size.width + 6f,
                                                       r.size.height.toFloat()))
            drawText(r, topLeft = androidx.compose.ui.geometry.Offset(tx, 2f))
        }
    }
}

/**
 * Copia TODO el horizonte que haya, en el formato de la calculadora de ICE-D.
 *
 * UN SOLO BOTON. Antes habia uno por medida, y eso obligaba a decidir cual copiar justo
 * cuando lo que se quiere es tenerlo todo: se pega en un correo, en la pagina o en un
 * cuaderno y ya se elegira alli. Cada medida va con su rotulo y sus dos lineas de numeros.
 */
@Composable
private fun CopiarHorizonte(
    delTelefono: List<HorizonPoints.Point>,
    aMano: List<HorizonPoints.Point>,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var copiado by remember { mutableStateOf(false) }
    LaunchedEffect(copiado) { if (copiado) { kotlinx.coroutines.delay(1500); copiado = false } }
    OutlinedButton(
        onClick = {
            val texto = buildString {
                // Con una sola medida NO se pone rotulo: asi las dos lineas se pegan
                // directamente en los dos campos de la pagina, que es el caso comun.
                val dos = delTelefono.isNotEmpty() && aMano.isNotEmpty()
                if (delTelefono.isNotEmpty()) {
                    if (dos) appendLine("Phone sweep, azimuths then elevations:")
                    append(HorizonPoints.toClipboard(delTelefono))
                    if (dos) appendLine()
                }
                if (aMano.isNotEmpty()) {
                    if (dos) { appendLine(); appendLine("Hand survey, azimuths then elevations:") }
                    append(HorizonPoints.toClipboard(aMano))
                }
            }
            val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cb?.setPrimaryClip(android.content.ClipData.newPlainText("GlacioTools", texto))
            copiado = true
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.testTag("fb-cosmo-copy")) {
        Icon(Icons.Outlined.ContentCopy, null,
             Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (copiado) "Copied" else "Copy horizon")
    }
}

/**
 * Rumbo y buzamiento apoyando el telefono en la roca.
 *
 * Sin cuenta atras sonora: aqui SI se ve la pantalla, porque el telefono esta tumbado
 * delante y no apuntando al cielo.
 */
@Composable
private fun MedirRumboYBuzamiento(onDone: (Double, Double) -> Unit, onCancel: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val beeper = remember { GpsTimeBeeper() }

    var rumbo by remember { mutableStateOf<Double?>(null) }
    var buzamiento by remember { mutableStateOf<Double?>(null) }
    val vivo = rememberUpdatedState(rumbo to buzamiento)

    // Las dos posiciones. La primera se toma tal cual; la segunda con el telefono girado
    // media vuelta sobre la superficie.
    var primera by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var segunda by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var midiendo by remember { mutableStateOf(false) }
    var cuenta by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val R = FloatArray(9)
        val oyente = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(ev: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(R, ev.values)
                val (r, b) = Shielding.strikeDipFrom(R)
                rumbo = r; buzamiento = b
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(oyente, sensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST)
        onDispose { sm.unregisterListener(oyente) }
    }

    // Tres segundos de promedio por posicion. Circular para el rumbo, que da la vuelta.
    LaunchedEffect(midiendo) {
        if (!midiendo) return@LaunchedEffect
        val rumbos = ArrayList<Double>()
        val buzamientos = ArrayList<Double>()
        for (seg in SEGUNDOS_DOS_POSICIONES downTo 1) {
            cuenta = seg
            AlbedoRun.beep(midiendo = true, ultimo = false).let { beeper.beep(it.hz, it.ms) }
            repeat(10) {
                kotlinx.coroutines.delay(100)
                val (r, b) = vivo.value
                if (r != null && b != null) { rumbos += r; buzamientos += b }
            }
        }
        AlbedoRun.beep(midiendo = true, ultimo = true).let { beeper.beep(it.hz, it.ms) }
        cuenta = 0
        midiendo = false
        val r = Angles.mean(rumbos)?.let { Compass.normalize(it) }
        val b = buzamientos.average().takeIf { buzamientos.isNotEmpty() }
        if (r != null && b != null) {
            if (primera == null) primera = r to b else segunda = r to b
        }
    }

    val p1 = primera
    val p2 = segunda
    // El buzamiento NO cambia al girar el telefono sobre el plano --el plano es el mismo-- y
    // el rumbo si cambia media vuelta. Ver Reversal: cada uno se combina de su manera.
    // LOS DOS SON MAGNITUDES DE LA SUPERFICIE. El plano no se mueve al girar el telefono
    // sobre el, y el strike sale de su normal, que es justo el eje del giro: tampoco se
    // entera. Usar Reversal.heading aqui --como se hizo primero-- resta 180 grados que no
    // habia que restar y el "sesgo" sale de casi 90 siempre.
    val finalBuz = if (p1 != null && p2 != null) Reversal.surface(p1.second, p2.second) else null
    val finalRumbo = if (p1 != null && p2 != null)
        Reversal.surfaceHeading(p1.first, p2.first) else null

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("fb-cosmo-dip-dialog"),
        title = { Text("Strike and dip in two positions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    midiendo -> {
                        Text(if (p1 == null) "Hold still — first position"
                             else "Hold still — second position",
                             style = MaterialTheme.typography.titleMedium)
                        Text("$cuenta", style = MaterialTheme.typography.displaySmall,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             modifier = Modifier.testTag("fb-cosmo-dip-count"))
                    }
                    p1 == null ->
                        Text("Lay the phone flat on the surface, screen up, then press Start. " +
                             "It averages for $SEGUNDOS_DOS_POSICIONES seconds.")
                    p2 == null -> {
                        Text("First position: strike %.0f°, dip %.0f°".format(p1.first, p1.second),
                             style = MaterialTheme.typography.bodyMedium,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             modifier = Modifier.testTag("fb-cosmo-dip-first"))
                        // EL EJE SE DICE, porque es lo unico que hay que hacer bien: girar
                        // sobre otro eje no cancela el sesgo, lo mezcla.
                        Text("Now turn the phone 180° WITHOUT LIFTING IT: keep it flat on the " +
                             "same spot and spin it in place, so the end that pointed away " +
                             "now points towards you. Which way you spin it does not matter.",
                             style = MaterialTheme.typography.bodyMedium)
                        Text("That reverses the sensor's own bias while the rock stays put, " +
                             "so combining the two readings cancels it.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text("Strike %.0f°   Dip %.0f°"
                                 .format(finalRumbo!!.value, finalBuz!!.value),
                             style = MaterialTheme.typography.headlineSmall,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             modifier = Modifier.testTag("fb-cosmo-dip-live"))
                        Text("Bias removed: %.1f° in dip, %.1f° in strike"
                                 .format(finalBuz.bias, finalRumbo.bias),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.testTag("fb-cosmo-dip-bias"))
                        Reversal.warning(listOf(finalBuz.bias, finalRumbo.bias))?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.error,
                                 modifier = Modifier.testTag("fb-cosmo-dip-warn"))
                        }
                    }
                }
                if (!midiendo && p2 == null) {
                    Text("Live: strike %s  dip %s".format(
                             rumbo?.let { "%.0f°".format(it) } ?: "—",
                             buzamiento?.let { "%.0f°".format(it) } ?: "—"),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("The strike needs the magnetometer, so keep the hammer and the sled away.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            when {
                midiendo -> {}
                p2 != null -> TextButton(
                    onClick = { onDone(finalRumbo!!.value, finalBuz!!.value) },
                    modifier = Modifier.testTag("fb-cosmo-dip-use")) { Text("Use these") }
                else -> TextButton(onClick = { midiendo = true },
                                   enabled = buzamiento != null,
                                   modifier = Modifier.testTag("fb-cosmo-dip-start")) {
                    Text(if (p1 == null) "Start" else "Start second")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(if (p1 != null && p2 == null) "Use first only" else "Cancel")
            }
        })
}

/** Tres segundos por posicion: lo pedido, y bastante para promediar el temblor de la mano. */
private const val SEGUNDOS_DOS_POSICIONES = 3

/**
 * El horizonte tecleado a mano, en el formato de la calculadora.
 *
 * DOS CAMPOS Y NO UNA TABLA DE PARES. Lo que se trae de terreno es una columna de azimuts y
 * otra de elevaciones, y lo que se quiere despues es pegarlas en la pagina de ICE-D, que
 * tambien son dos campos. Una tabla de filas obligaria a teclear alternando entre dos
 * numeros que en el cuaderno estan en columnas separadas.
 */
@Composable
private fun HorizonteAMano(
    azimutes: List<Double>, elevaciones: List<Double>,
    onDone: (List<Double>, List<Double>) -> Unit, onCancel: () -> Unit,
) {
    var az by remember { mutableStateOf(azimutes.joinToString(" ") { "%.0f".format(it) }) }
    var el by remember { mutableStateOf(elevaciones.joinToString(" ") { "%.0f".format(it) }) }
    val leido = remember(az, el) { HorizonPoints.parse(az, el) }

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("fb-cosmo-manual-dialog"),
        title = { Text("Horizon by hand") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Compass and inclinometer: one line of azimuths, one of elevations, " +
                     "in the same order. Same format as the ICE-D calculator.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                // TECLADO NUMERICO tambien aqui, aunque sean listas: lo que se teclea son
                // decenas de numeros seguidos, y el pad decimal trae el espacio, el punto y
                // el signo, que es todo lo que hace falta. Con el teclado de texto hay que
                // cambiar de capa en cada numero.
                val padNumerico = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(
                    value = az, onValueChange = { az = it },
                    label = { Text("Azimuths") }, placeholder = { Text("0 55 115 235 310") },
                    keyboardOptions = padNumerico,
                    modifier = Modifier.fillMaxWidth().testTag("fb-cosmo-manual-az"))
                OutlinedTextField(
                    value = el, onValueChange = { el = it },
                    label = { Text("Elevations") }, placeholder = { Text("3 0 5 0 0") },
                    keyboardOptions = padNumerico,
                    modifier = Modifier.fillMaxWidth().testTag("fb-cosmo-manual-el"))
                leido.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall,
                         modifier = Modifier.testTag("fb-cosmo-manual-error"))
                }
                if (leido.error == null && leido.points.isNotEmpty()) {
                    Text("${leido.points.size} points.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(leido.points.map { it.azimuth },
                                   leido.points.map { it.elevation }) },
                enabled = leido.error == null,
                modifier = Modifier.testTag("fb-cosmo-manual-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
