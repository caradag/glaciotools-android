package cl.umag.glaciertemp.app

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.umag.glaciertemp.core.fieldbook.LengthUnit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.umag.glaciertemp.core.Decimals
import cl.umag.glaciertemp.core.fieldbook.FieldEntry
import cl.umag.glaciertemp.core.fieldbook.FieldbookCsv
import cl.umag.glaciertemp.core.fieldbook.GaugingBin
import cl.umag.glaciertemp.core.fieldbook.Gauging
import cl.umag.glaciertemp.core.fieldbook.StreamGauging

// ==================================== aforo de caudal ====================================

/**
 * La pantalla de un aforo: el metodo de area-velocidad puesto en una tabla.
 *
 * POR QUE ESTA NOTA TIENE PANTALLA PROPIA Y NO SE MONTA EN LA COMUN. Todas las demas son un
 * formulario que se rellena de arriba abajo y caben en una columna que se desplaza. Un aforo
 * no: son treinta y cinco filas que hay que ir recorriendo mientras se mira el perfil que se
 * esta construyendo, y si el grafico se va con el desplazamiento deja de servir justo para lo
 * que sirve -- ver, con el agua todavia delante, que una profundidad esta mal tecleada.
 * Por eso la cabecera y el grafico van clavados arriba y solo la tabla se mueve.
 */
@Composable
fun GaugingScreen(vm: FieldbookViewModel, s: FieldbookUiState, e: FieldEntry,
                  onBorrar: (Boolean) -> Unit) {
    val g = e.gauging ?: StreamGauging()
    val u by AppSettings.gaugingLengthUnit.collectAsStateWithLifecycle()
    val tramos = remember(g.widthM, g.intervalM) { Gauging.bins(g.widthM, g.intervalM) }
    val resumen = remember(g) { Gauging.summarize(g) }
    val rango = remember(g.bins) { Gauging.velocityRange(g) }
    var detalle by remember { mutableStateOf<Int?>(null) }
    // LA CABECERA SE PLIEGA AL PULSAR OK. Medido en el emulador: con el teclado abierto y
    // los campos de configuracion desplegados quedaba UNA fila visible en una pantalla de
    // 2400 px. El perfil, el ancho y el intervalo se ponen una vez y se pasa luego una hora
    // en la tabla, asi que su sitio natural es una linea que se despliega cuando hace falta.
    var ajustes by remember { mutableStateOf(false) }
    val configurado = tramos.isNotEmpty()
    val abierta = !configurado || ajustes

    // EL ANCHO Y EL INTERVALO SON UN BORRADOR HASTA EL OK. La tabla se construye con lo que
    // hay grabado, no con lo que se esta tecleando: asi se puede probar un intervalo, ver
    // cuantos tramos daria y corregirlo sin que aparezcan y desaparezcan filas, y sin que un
    // valor a medio escribir recorte verticales ya medidas. Se rellena con lo grabado cada
    // vez que la cabecera se abre, de modo que Cancel deja todo como estaba.
    var anchoBorrador by remember(e.id, abierta) { mutableStateOf(g.widthM) }
    var intervaloBorrador by remember(e.id, abierta) { mutableStateOf(g.intervalM) }

    Column(Modifier.fillMaxSize()) {

        // ------------------------------- lo que no se mueve -------------------------------
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                   verticalArrangement = Arrangement.spacedBy(6.dp)) {

                FieldbookNotice(vm, s)

                if (!abierta) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(buildString {
                                 append(e.profileName.ifBlank { "(unnamed profile)" })
                                 append("  ·  ").append(u.format(g.widthM ?: 0.0, 3))
                                 append(" ${u.suffix}  ·  bins ")
                                 append(u.format(g.intervalM ?: 0.0, 3))
                                 append(" ${u.suffix}  ·  ")
                                 append(if (g.depthFromBed) "from bed" else "from surface")
                             },
                             Modifier.weight(1f),
                             style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { ajustes = true },
                                   modifier = Modifier.testTag("fb-gauging-settings")) {
                            Text("Edit")
                        }
                    }
                }

                if (abierta) {
                NamePicker(
                    label = "Profile",
                    value = e.profileName,
                    options = s.profiles,
                    onValue = { v -> vm.update(immediate = false) { it.copy(profileName = v) } },
                    onRemember = { vm.rememberName(NameList.PROFILES, it) },
                    onRemove = { vm.removeName(NameList.PROFILES, it) },
                    onClearAll = { vm.clearNames(NameList.PROFILES) },
                    tag = "fb-gauging-profile")

                // TODAS LAS LONGITUDES EN LA MISMA UNIDAD, la elegida en Settings (metros o
                // centimetros), el intervalo incluido. Estuvo el intervalo solo en centimetros
                // y era la unica casilla distinta: una tabla con x en metros y el intervalo en
                // centimetros invita a leer mal justo el numero que decide cuantas verticales
                // hay. Se convierte en la entrada y en la salida; lo guardado sigue en metros.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Section width", anchoBorrador?.let { u.fromMetres(it) },
                                onValue = { anchoBorrador = it?.let(u::toMetres) },
                                suffix = u.suffix, modifier = Modifier.weight(1f),
                                tag = "fb-gauging-width")
                    NumberField("Bin interval", intervaloBorrador?.let { u.fromMetres(it) },
                                onValue = { intervaloBorrador = it?.let(u::toMetres) },
                                suffix = u.suffix, modifier = Modifier.weight(1f),
                                tag = "fb-gauging-interval")
                }

                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Velocity depth:", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = !g.depthFromBed,
                        onClick = { vm.updateGauging(immediate = true) {
                            it.copy(depthFromBed = false) } },
                        label = { Text("From surface") },
                        modifier = Modifier.testTag("fb-gauging-from-surface"))
                    FilterChip(
                        selected = g.depthFromBed,
                        onClick = { vm.updateGauging(immediate = true) {
                            it.copy(depthFromBed = true) } },
                        label = { Text("From bed") },
                        modifier = Modifier.testTag("fb-gauging-from-bed"))
                }

                val previstos = Gauging.bins(anchoBorrador, intervaloBorrador)
                CuantosTramos(anchoBorrador, intervaloBorrador, previstos, g, u)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                               if (vm.applyGaugingSettings(anchoBorrador, intervaloBorrador))
                                   ajustes = false
                           },
                           enabled = previstos.isNotEmpty(),
                           modifier = Modifier.weight(1f).testTag("fb-gauging-ok")) {
                        Text(if (configurado) "OK — update table" else "OK — build table")
                    }
                    if (configurado) {
                        TextButton(onClick = { ajustes = false },
                                   modifier = Modifier.testTag("fb-gauging-settings-done")) {
                            Text("Cancel")
                        }
                    }
                }
                }

                PerfilDelRio(g, tramos, rango,
                             Modifier.fillMaxWidth().height(110.dp)
                                 .testTag("fb-gauging-chart"))
                LeyendaDeVelocidad(rango)

                CaudalEnCurso(resumen)
            }
        }
        HorizontalDivider()

        // --------------------------------- lo que se mueve ---------------------------------
        // weight(1f) y NO fillMaxSize(). Dentro de una Column, `fillMaxSize` toma la altura
        // MAXIMA que ofrece el padre --la pantalla entera-- y como esta lista empieza debajo
        // de la cabecera, su mitad inferior quedaba fuera del telefono y no se desplazaba:
        // el resumen, el boton de copiar, las fotos y el propio Done eran inalcanzables.
        // `weight` reparte lo que SOBRA, que es lo que aqui significa "el resto".
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {

            if (tramos.isNotEmpty()) {
                item { CabeceraDeTabla(g.depthFromBed, u) }
                itemsIndexed(tramos, key = { _, t -> t.index }) { _, t ->
                    FilaDeTramo(
                        t = t,
                        b = g.bins.getOrElse(t.index) { GaugingBin() },
                        fromBed = g.depthFromBed,
                        u = u,
                        onDepth = { vm.setBinDepth(t.index, it) },
                        onVelocity = { vm.setBinVelocity(t.index, it) },
                        onInfo = { detalle = t.index })
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { Estadisticas(resumen, u) }
                item { CopiarAforo(e) }
            } else {
                item {
                    Text("Enter the section width and the bin interval, then press OK to " +
                         "build the table.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(vertical = 16.dp)
                             .testTag("fb-gauging-empty"))
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            // Quien, cuando y donde van ABAJO en esta nota y no arriba como en las demas. No
            // es descuido: arriba esta ocupado por lo unico que hay que mirar con el rio
            // delante. El observador y la coordenada se rellenan una vez y no se vuelven a
            // tocar, asi que pierden poco por estar a un desplazamiento de distancia.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                 onChange = { ms ->
                                     vm.update { it.copy(createdEpochMillis = ms) } },
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
                }
            }

            item { FotosYComentarios(vm, e, g) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { vm.close() },
                           modifier = Modifier.weight(1f).testTag("fb-done")) { Text("Done") }
                    TextButton(onClick = { onBorrar(true) },
                               modifier = Modifier.testTag("fb-delete")) {
                        Icon(Icons.Outlined.Delete, contentDescription = null,
                             Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Text("Everything is saved as you type.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    detalle?.let { i ->
        DetalleDelTramo(tramos.getOrNull(i), g.bins.getOrElse(i) { GaugingBin() }, u,
                        onClose = { detalle = null })
    }
}

/**
 * Cuantos tramos daria el ancho y el intervalo tecleados, ANTES de aceptarlos.
 *
 * Es lo que permite ajustar el intervalo con conocimiento de causa: "35 bins" dice si el
 * trabajo es de media hora o de tres. Se dice ademas si el ultimo tramo queda mas corto
 * --pasa cuando el ancho no es multiplo del intervalo, y es legitimo, pero conviene saberlo--
 * y cuantas verticales medidas se perderian si la tabla nueva es mas corta que la actual.
 */
@Composable
private fun CuantosTramos(ancho: Double?, intervalo: Double?, previstos: List<Gauging.Bin>,
                          g: StreamGauging, u: LengthUnit) {
    val cuantos = Gauging.binCount(ancho, intervalo)
    val error = MaterialTheme.colorScheme.error
    val (texto, color) = when {
        cuantos == 0 ->
            "Enter a width and an interval greater than zero." to
                MaterialTheme.colorScheme.onSurfaceVariant
        previstos.isEmpty() ->
            "That would be $cuantos bins, more than the ${Gauging.MAX_BINS} allowed. " +
                "Check the interval — it is in ${u.label.lowercase()}." to error
        else -> {
            val ultimo = previstos.last()
            val corto = previstos.size > 1 && intervalo != null &&
                        ultimo.widthM < intervalo - 1e-9
            val perdidos = Gauging.wouldLose(g.bins, previstos.size)
            buildString {
                append("This gives ${previstos.size} bin")
                if (previstos.size != 1) append("s")
                if (corto) append(", the last one ${u.format(ultimo.widthM, 3)} ${u.suffix} wide")
                append(".")
                if (perdidos > 0) append(" $perdidos measured bin(s) at the far bank " +
                                         "would be dropped.")
            } to (if (perdidos > 0) error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(texto, style = MaterialTheme.typography.bodySmall, color = color,
         modifier = Modifier.testTag("fb-gauging-bincount"))
}

/** El caudal acumulado, y si esta terminado o no. */
@Composable
private fun CaudalEnCurso(r: Gauging.Summary) {
    Row(verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Discharge", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.dischargeM3s?.let { Decimals.fixed(it, 3) + " m³/s" } ?: "—",
                 style = MaterialTheme.typography.headlineSmall,
                 fontFamily = FontFamily.Monospace,
                 modifier = Modifier.testTag("fb-gauging-total"))
        }
        // MIENTRAS FALTE UN TRAMO, EL NUMERO ES PARCIAL Y LO DICE. Un total a medias y uno
        // terminado se parecen demasiado en pantalla, y en terreno el que se apunta en la
        // libreta de papel es el que se esta mirando.
        Text(if (r.isComplete) "complete · ${r.binCount} bins"
             else "${r.completeBins} of ${r.binCount} bins — partial",
             style = MaterialTheme.typography.bodySmall,
             color = if (r.isComplete) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.error,
             modifier = Modifier.padding(bottom = 4.dp).testTag("fb-gauging-progress"))
    }
}

// ------------------------------------- el grafico -------------------------------------

/**
 * La rampa de color de la velocidad: azul lo lento, rojo lo rapido.
 *
 * Es la escala que usan los perfiladores acusticos y la que espera cualquiera que haya visto
 * una seccion de un rio, asi que se respeta aunque no sea la mejor para un daltonico. Lo que
 * si se hace es no dejar que un tramo SIN medir caiga en la rampa: va en gris, porque
 * pintarlo del azul de "lento" diria que se midio y que el agua no se mueve.
 */
private val RAMPA = listOf(
    Color(0xFF1440A0), Color(0xFF0096C8), Color(0xFF3CB44B),
    Color(0xFFFABE1E), Color(0xFFD2281E))

private fun colorDeVelocidad(f: Double?): Color {
    if (f == null) return Color(0xFF9E9E9E)
    val x = (f.coerceIn(0.0, 1.0) * (RAMPA.size - 1)).toFloat()
    val i = x.toInt().coerceAtMost(RAMPA.size - 2)
    val t = x - i
    val a = RAMPA[i]; val b = RAMPA[i + 1]
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t)
}

/**
 * La seccion del rio tal como se va midiendo.
 *
 * Lo que hace util tenerlo delante es que un error de tecleo se ve al instante: una
 * profundidad de 12 m entre dos de 0,4 m es un escalon absurdo en el dibujo y una fila mas en
 * la tabla. Con el agua todavia delante se vuelve a medir; en el escritorio ya no.
 */
@Composable
private fun PerfilDelRio(g: StreamGauging, tramos: List<Gauging.Bin>,
                         rango: Pair<Double, Double>?, modifier: Modifier) {
    val ejes = MaterialTheme.colorScheme.onSurfaceVariant
    val agua = MaterialTheme.colorScheme.primary
    val fondo = MaterialTheme.colorScheme.onSurface

    Canvas(modifier) {
        val ancho = g.widthM ?: return@Canvas
        if (tramos.isEmpty() || ancho <= 0.0) return@Canvas
        val margenSup = 14f
        val margenInf = 10f
        val h = size.height - margenSup - margenInf
        if (h <= 0f) return@Canvas

        val maxProf = tramos.maxOf { t ->
            g.bins.getOrElse(t.index) { GaugingBin() }.depthM ?: 0.0
        }.coerceAtLeast(0.1)

        fun x(m: Double) = (m / ancho * size.width).toFloat()
        fun y(prof: Double) = margenSup + (prof / maxProf * h).toFloat()

        // La superficie del agua: la linea desde la que cuelga todo lo demas.
        drawLine(agua, Offset(0f, margenSup), Offset(size.width, margenSup), 3f)

        tramos.forEach { t ->
            val b = g.bins.getOrElse(t.index) { GaugingBin() }
            val d = b.depthM ?: return@forEach
            if (d <= 0.0) return@forEach
            val f = Gauging.velocityFraction(b.velocityMps, rango?.first, rango?.second)
            val x0 = x(t.startM)
            val x1 = x(t.endM)
            drawRect(color = colorDeVelocidad(f),
                     topLeft = Offset(x0, margenSup),
                     size = Size((x1 - x0).coerceAtLeast(1f), y(d) - margenSup))
        }

        // El fondo, uniendo los centros. Se dibuja ENCIMA de las columnas y solo entre
        // tramos medidos: unir por encima de un hueco inventaria un fondo que nadie sondeo.
        //
        // LAS ORILLAS CUENTAN COMO MEDIDAS DE PROFUNDIDAD CERO. El perfil empieza y acaba en
        // la superficie, que es lo que es un cauce, y asi la primera profundidad tecleada ya
        // dibuja un tramo de fondo desde la orilla en vez de un punto suelto. La regla del
        // hueco las alcanza igual: la orilla derecha solo se une si el ultimo tramo esta
        // medido. Son solo dibujo: el caudal no las usa.
        val puntos = ArrayList<Offset?>()
        puntos += Offset(x(0.0), y(0.0))
        tramos.forEach { t ->
            val d = g.bins.getOrElse(t.index) { GaugingBin() }.depthM
            puntos += d?.let { Offset(x(t.centreM), y(it)) }
        }
        puntos += Offset(x(ancho), y(0.0))

        var previo: Offset? = null
        puntos.forEachIndexed { i, p ->
            if (p == null) { previo = null; return@forEachIndexed }
            previo?.let { drawLine(fondo, it, p, 2f) }
            // Las orillas no llevan punto: no son una medida.
            if (i != 0 && i != puntos.lastIndex) drawCircle(fondo, radius = 2.5f, center = p)
            previo = p
        }

        drawLine(ejes, Offset(0f, size.height - margenInf),
                 Offset(size.width, size.height - margenInf), 1f)
    }
}

/** La escala de color con sus extremos, para que el dibujo signifique un numero. */
@Composable
private fun LeyendaDeVelocidad(rango: Pair<Double, Double>?) {
    if (rango == null) return
    // Con una sola velocidad medida no hay rango que mostrar: una barra de color entre
    // 0,85 y 0,85 no significa nada, y el tramo se pinta del centro de la rampa por lo
    // mismo. Se dice el valor y ya.
    if (rango.second - rango.first <= 1e-12) {
        Text("All measured velocities: ${Decimals.fixed(rango.first, 2)} m/s",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Decimals.fixed(rango.first, 2), style = MaterialTheme.typography.labelSmall,
             fontFamily = FontFamily.Monospace)
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val n = 48
            val paso = size.width / n
            for (i in 0 until n) {
                drawRect(colorDeVelocidad(i.toDouble() / (n - 1)),
                         topLeft = Offset(i * paso, 0f), size = Size(paso + 1f, size.height))
            }
        }
        Text(Decimals.fixed(rango.second, 2) + " m/s",
             style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

// -------------------------------------- la tabla --------------------------------------

private val PESOS = listOf(0.9f, 1.05f, 0.85f, 1.05f, 1.0f)

@Composable
private fun CabeceraDeTabla(fromBed: Boolean, u: LengthUnit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("x (${u.suffix})", "depth (${u.suffix})",
               if (fromBed) "meas. ↑(${u.suffix})" else "meas. ↓(${u.suffix})",
               "v (m/s)", "Q (m³/s)")
            .forEachIndexed { i, t ->
                Text(t, Modifier.weight(PESOS[i]),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        Spacer(Modifier.width(32.dp))
    }
}

/**
 * Una vertical del perfil.
 *
 * LA TERCERA COLUMNA NO SE PUEDE EDITAR: sale de la segunda. Dejarla escribir crearia dos
 * verdades sobre la misma vertical --la profundidad medida y una profundidad de medida que no
 * le corresponde-- y la que se usaria para calcular no seria necesariamente la que se ve.
 */
@Composable
private fun FilaDeTramo(t: Gauging.Bin, b: GaugingBin, fromBed: Boolean, u: LengthUnit,
                        onDepth: (Double?) -> Unit, onVelocity: (Double?) -> Unit,
                        onInfo: () -> Unit) {
    val q = Gauging.binDischarge(t, b)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {

        Celda(u.format(t.centreM, 3), PESOS[0], "fb-gauging-x-${t.index}")

        CeldaEditable(b.depthM?.let { u.fromMetres(it) }, { onDepth(it?.let(u::toMetres)) },
                      PESOS[1], "fb-gauging-depth-${t.index}")

        Celda(Gauging.measurementDepthM(b.depthM, fromBed)
                  ?.let { u.format(it, 2, fixed = true) } ?: "—",
              PESOS[2], "fb-gauging-vdepth-${t.index}",
              color = MaterialTheme.colorScheme.primary)

        CeldaEditable(b.velocityMps, onVelocity, PESOS[3], "fb-gauging-v-${t.index}")

        Celda(q?.let { Decimals.fixed(it, 4) } ?: "—", PESOS[4],
              "fb-gauging-q-${t.index}")

        IconButton(onClick = onInfo, modifier = Modifier.width(32.dp)
            .testTag("fb-gauging-info-${t.index}")) {
            Icon(Icons.Outlined.Info, contentDescription = "Bin details",
                 Modifier.width(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Celda(
    texto: String, peso: Float, tag: String, color: Color = Color.Unspecified,
) {
    Text(texto, Modifier.weight(peso).testTag(tag),
         style = MaterialTheme.typography.bodySmall,
         fontFamily = FontFamily.Monospace, fontSize = 13.sp,
         textAlign = TextAlign.End, color = color)
}

/**
 * Una casilla que se teclea, sin etiqueta ni marco grueso: en una tabla de treinta y cinco
 * filas, el adorno de un campo normal se come la pantalla entera.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CeldaEditable(
    valor: Double?, onValor: (Double?) -> Unit, peso: Float, tag: String,
) {
    var texto by remember { mutableStateOf(formatNumber(valor)) }
    LaunchedEffect(valor) {
        if (parseNumber(texto) != valor && !(texto.isBlank() && valor == null))
            texto = formatNumber(valor)
    }
    OutlinedTextField(
        value = texto,
        onValueChange = { t -> texto = t; onValor(if (t.isBlank()) null else parseNumber(t)) },
        singleLine = true,
        isError = texto.isNotBlank() && parseNumber(texto) == null,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.weight(peso).testTag(tag))
}

/** Todo lo que se sabe de una vertical, incluido cuando se escribio cada casilla. */
@Composable
private fun DetalleDelTramo(t: Gauging.Bin?, b: GaugingBin, u: LengthUnit, onClose: () -> Unit) {
    if (t == null) { onClose(); return }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("fb-gauging-detail"),
        title = { Text("Bin ${t.index + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Linea("Edges", "${u.format(t.startM, 3)} ${u.suffix} " +
                               "to ${u.format(t.endM, 3)} ${u.suffix}")
                Linea("Centre", u.format(t.centreM, 3) + " " + u.suffix)
                Linea("Bin width", u.format(t.widthM, 3) + " " + u.suffix)
                HorizontalDivider()
                Linea("Depth first written", cuando(b.depthFirstEditMillis))
                Linea("Depth last written", cuando(b.depthLastEditMillis))
                Linea("Velocity first written", cuando(b.velocityFirstEditMillis))
                Linea("Velocity last written", cuando(b.velocityLastEditMillis))
                // SI COINCIDEN, NO SE TOCO DESPUES. Decirlo ahorra comparar dos horas
                // iguales, y la pregunta que trae a alguien a este cuadro suele ser
                // exactamente esa: si el numero raro es el medido o uno corregido.
                // NO se comparan los milisegundos a pelo: la casilla se guarda en cada
                // pulsacion, asi que "0.85" deja primera y ultima separadas por un segundo
                // y el aviso saltaria en toda casilla de mas de un digito.
                val tocado = Gauging.wasCorrected(b.depthFirstEditMillis,
                                                  b.depthLastEditMillis) ||
                             Gauging.wasCorrected(b.velocityFirstEditMillis,
                                                  b.velocityLastEditMillis)
                Text(if (tocado) "One of the values was changed well after it was first " +
                                 "written — the first time is when it was measured."
                     else "Both values were written in one go and not changed since.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun Linea(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun cuando(ms: Long?): String = ms?.let { formatWhen(it) } ?: "—"

// ----------------------------------- pie de la tabla -----------------------------------

@Composable
private fun Estadisticas(r: Gauging.Summary, u: LengthUnit) {
    Card(Modifier.fillMaxWidth().testTag("fb-gauging-stats")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleSmall)
            Linea("Bins", "${r.completeBins} complete of ${r.binCount}")
            Linea("Wetted area", r.areaM2?.let { Decimals.fixed(it, 3) + " m²" } ?: "—")
            Linea("Mean depth", r.meanDepthM?.let { u.format(it, 3, fixed = true) + " " + u.suffix } ?: "—")
            Linea("Max depth", r.maxDepthM?.let { u.format(it, 3, fixed = true) + " " + u.suffix } ?: "—")
            Linea("Mean velocity",
                  r.meanVelocityMps?.let { Decimals.fixed(it, 3) + " m/s" } ?: "—")
            Linea("Max velocity",
                  r.maxVelocityMps?.let { Decimals.fixed(it, 3) + " m/s" } ?: "—")
            Tramo("Depth measurements", r.depthTimes)
            Tramo("Velocity measurements", r.velocityTimes)
            // LA MEDIANA FECHA EL AFORO. Un perfil largo se mide a lo largo de una hora, y
            // si el rio subio durante esa hora el caudal no corresponde a ningun instante:
            // corresponde, con suerte, al del medio.
            Text("The median is what dates the discharge when the gauging takes a while.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Inicio, fin y mediana de una tanda, como bloque con TITULO y una linea por dato.
 *
 * Estuvo en una sola `Linea` con las tres horas en el valor. El valor no tiene peso y la
 * etiqueta si, asi que el texto largo se quedaba todo el ancho y la etiqueta se aplastaba a
 * cero: el titulo desaparecia y se partia letra a letra hacia abajo, dejando una columna
 * alta y vacia debajo del bloque.
 */
@Composable
private fun Tramo(titulo: String, t: Gauging.TimeSpan) {
    HorizontalDivider(Modifier.padding(vertical = 3.dp))
    Text(titulo, style = MaterialTheme.typography.labelLarge)
    if (t.isEmpty) {
        Linea("Not measured yet", "—")
        return
    }
    Linea("Start", cuando(t.firstMillis))
    Linea("End", cuando(t.lastMillis))
    Linea("Median", cuando(t.medianMillis))
}

/**
 * Copia el aforo ENTERO, que es exactamente lo que se exporta.
 *
 * Se reusa la misma funcion que escribe el CSV de la exportacion en vez de armar aqui otro
 * texto parecido. Dos generadores del mismo dato divergen --uno gana una columna y el otro
 * no-- y entonces lo que se pega en un correo deja de ser lo que hay en el fichero.
 */
@Composable
private fun CopiarAforo(e: FieldEntry) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var copiado by remember { mutableStateOf(false) }
    LaunchedEffect(copiado) { if (copiado) { kotlinx.coroutines.delay(1500); copiado = false } }
    OutlinedButton(
        onClick = {
            val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cb?.setPrimaryClip(android.content.ClipData.newPlainText(
                "GlacioTools", FieldbookCsv.gauging(e)))
            copiado = true
        },
        modifier = Modifier.fillMaxWidth().testTag("fb-gauging-copy")) {
        Text(if (copiado) "Copied" else "Copy table and discharge")
    }
}

@Composable
private fun FotosYComentarios(vm: FieldbookViewModel, e: FieldEntry, g: StreamGauging) {
    val (tomarFoto, elegirFoto) = rememberPhotoAdders(
        newFile = { vm.newMediaFile(it) }, onAdded = { vm.addPhotos(it) })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = g.comments,
            onValueChange = { v -> vm.updateGauging { it.copy(comments = v) } },
            label = { Text("Comments") },
            supportingText = { Text("Anything that would change how the number is read: " +
                                    "turbid water, a gauge that skipped, wind on the tape.") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("fb-gauging-comments"))
        PhotoStrip(e.photos, resolve = { vm.mediaFile(it) },
                   onRemove = { vm.removePhoto(it) }, tag = "fb-gauging-photos")
        PhotoButtons(onTake = tomarFoto, onPick = elegirFoto, tag = "fb-gauging-photo")
    }
}
