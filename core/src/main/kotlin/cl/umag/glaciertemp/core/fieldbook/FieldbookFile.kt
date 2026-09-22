package cl.umag.glaciertemp.core.fieldbook

/**
 * Como se guarda una entrada de la libreta en disco.
 *
 * Cabecera de `clave=valor` con lo comun a todos los tipos, una linea `---`, y luego bloques
 * `[nombre]` con las lineas `clave=valor` que le tocan al tipo. Una clave repetida dentro de
 * un bloque es una LISTA --asi se guardan las fotos-- y un bloque repetido es un elemento
 * mas de una serie: las anotaciones de una nota, las mediciones de una baliza.
 *
 * Texto plano y no JSON por lo mismo que los puntos de GPS: `core` no tiene dependencias a
 * proposito, y un dato de terreno que alguien querra mirar dentro de diez anos vale mas si se
 * abre con cualquier cosa.
 *
 * A DIFERENCIA de un punto de GPS, aqui NO se hace append: una entrada se reescribe entera
 * cada vez. Un punto acumula miles de muestras y reescribirlo entero por cada arreglo seria
 * absurdo; una entrada de libreta son unos pocos kilobytes incluso con cincuenta mediciones,
 * y se EDITA --se corrige una hora, se cambia una altura-- que es algo que un formato de solo
 * anadir no sabe hacer. La escritura pasa por un temporal y un rename, asi que quedarse sin
 * bateria a mitad deja intacto lo anterior.
 */
object FieldbookFile {

    const val SEPARATOR = "---"

    // ------------------------------- escapado de valores -------------------------------

    /**
     * Un valor puede traer saltos de linea: las notas son texto libre y la gente pega
     * parrafos enteros. Sin escapar, uno solo partiria el fichero y todo lo que viniera
     * detras se leeria como claves desconocidas.
     */
    internal fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    internal fun unesc(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { sb.append(c); i++; continue }
            when (s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                // Una barra que no abre nada conocido se deja tal cual en vez de comersela:
                // si alguien escribio una ruta de Windows en una nota, tiene que seguir ahi.
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    // ----------------------------------- escritura -----------------------------------

    private fun StringBuilder.kv(key: String, value: String?) {
        if (value.isNullOrEmpty()) return
        append(key).append('=').append(esc(value)).append('\n')
    }

    private fun StringBuilder.kv(key: String, value: Long?) {
        if (value == null) return
        append(key).append('=').append(value).append('\n')
    }

    private fun StringBuilder.kv(key: String, value: Int?) {
        if (value == null) return
        append(key).append('=').append(value).append('\n')
    }

    /**
     * Los decimales se escriben con `Double.toString`, que no mira el idioma del telefono y
     * ademas ida y vuelta da el MISMO double.
     *
     * No se usa `"%.8f".format(v)`: sin Locale.ROOT escribe la coma decimal en un telefono en
     * espanol --que es donde esta app va a correr-- y el fichero deja de poder leerse con un
     * parser de numeros; y con un numero fijo de decimales una latitud pierde precision al
     * guardarla. Aqui no hay nada que formatear para leer: eso lo hace la pantalla.
     */
    private fun StringBuilder.kvNum(key: String, value: Double?) {
        if (value == null || value.isNaN() || value.isInfinite()) return
        append(key).append('=').append(value.toString()).append('\n')
    }

    fun write(e: FieldEntry): String = buildString {
        append("# GlacioTools fieldbook entry\n")
        kv("id", e.id)
        kv("type", e.type.name)
        kv("created", e.createdEpochMillis)
        kv("updated", e.updatedEpochMillis)
        kv("person", e.person)
        kv("campaign", e.campaignId)
        e.position?.let { p ->
            kvNum("pos.lat", p.latitude)
            kvNum("pos.lon", p.longitude)
            kvNum("pos.alt", p.altitudeMetres)
            kvNum("pos.acc", p.accuracyMetres)
            kv("pos.source", p.source.name)
            kv("pos.point_name", p.pointName)
            kv("pos.point_id", p.pointId)
            kv("pos.at", p.atEpochMillis)
        }
        append(SEPARATOR).append('\n')

        when (e.type) {
            EntryType.NOTE -> {
                // El titulo va en su propio bloque y no en la cabecera: la cabecera es lo
                // comun a los cuatro tipos, y un campo que solo tiene sentido en uno no
                // pertenece ahi.
                if (e.title.isNotBlank()) {
                    append("[note]\n")
                    kv("title", e.title)
                }
                e.items.forEach { item ->
                    append("[item]\n")
                    kv("kind", item.kind.name)
                    kv("at", item.atEpochMillis)
                    kv("text", item.text)
                    kv("file", item.file)
                    kv("duration_ms", item.durationMillis)
                }
            }

            EntryType.STAKE -> {
                append("[stake]\n")
                kv("name", e.stakeName)
                kvNum("length_cm", e.stakeLengthCm)
                Ablation.chronological(e.measurements).forEach { m ->
                    append("[measure]\n")
                    kv("at", m.atEpochMillis)
                    kv("person", m.person)
                    kvNum("height_cm", m.exposedHeightCm)
                    m.photos.forEach { kv("photo", it) }
                    m.gnss?.takeIf { !it.isEmpty }?.let { g ->
                        kv("gnss.receiver", g.receiver)
                        kv("gnss.start", g.startEpochMillis)
                        kv("gnss.end", g.endEpochMillis)
                        kv("gnss.planned_min", g.plannedMinutes)
                        kvNum("gnss.antenna_cm", g.antennaHeightCm)
                    }
                }
            }

            EntryType.GNSS -> {
                append("[gnss]\n")
                kv("name", e.pointName)
                e.gnss?.let { g ->
                    kv("receiver", g.receiver)
                    kv("start", g.startEpochMillis)
                    kv("end", g.endEpochMillis)
                    kv("planned_min", g.plannedMinutes)
                    kvNum("antenna_cm", g.antennaHeightCm)
                }
                e.photos.forEach { kv("photo", it) }
            }

            EntryType.DENDRO -> {
                append("[dendro]\n")
                kv("label", e.sampleLabel)
                kv("species", e.species)
                kvNum("height_cm", e.samplingHeightCm)
                kvNum("perimeter_cm", e.trunkPerimeterCm)
                kv("notes", e.notes)
                e.photos.forEach { kv("photo", it) }
            }
        }
    }

    // ------------------------------------ lectura ------------------------------------

    /** Un bloque leido: su nombre y sus claves, cada una con TODOS sus valores. */
    private class Block(val name: String) {
        val fields = LinkedHashMap<String, MutableList<String>>()
        fun add(k: String, v: String) { fields.getOrPut(k) { ArrayList() }.add(v) }
        fun one(k: String): String? = fields[k]?.firstOrNull()
        fun all(k: String): List<String> = fields[k] ?: emptyList()
        fun long(k: String): Long? = one(k)?.toLongOrNull()
        fun int(k: String): Int? = one(k)?.toIntOrNull()
        fun num(k: String): Double? = one(k)?.toDoubleOrNull()
    }

    /**
     * Lee una entrada. Devuelve null solo si no hay id o tipo: sin esos dos no hay entrada.
     *
     * Lo demas se lee a la ligera a proposito -- una clave desconocida se ignora, un numero
     * ilegible queda nulo. Una entrada de terreno a medias sigue valiendo mucho mas que un
     * error de lectura que la esconde entera.
     */
    fun parse(text: String): FieldEntry? {
        val lineas = text.lineSequence().toList()
        val corte = lineas.indexOfFirst { it.trim() == SEPARATOR }
        if (corte < 0) return null

        // El valor NO se recorta, solo la clave: un espacio al final de una nota es algo que
        // el usuario escribio, y una linea que vuelve distinta de como se guardo delata al
        // formato aunque la diferencia sea invisible.
        val cabecera = HashMap<String, String>()
        for (l in lineas.take(corte)) {
            val t = l.trimStart().trimEnd('\r')
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf('=')
            if (i <= 0) continue
            cabecera[t.substring(0, i).trim()] = unesc(t.substring(i + 1))
        }

        val id = cabecera["id"]?.takeIf { it.isNotBlank() } ?: return null
        val type = runCatching { EntryType.valueOf(cabecera["type"] ?: "") }.getOrNull()
            ?: return null

        val bloques = ArrayList<Block>()
        for (l in lineas.drop(corte + 1)) {
            val t = l.trimStart().trimEnd('\r')
            if (t.isEmpty() || t.startsWith("#")) continue
            val cerrado = t.trimEnd()
            if (cerrado.startsWith("[") && cerrado.endsWith("]")) {
                bloques.add(Block(cerrado.substring(1, cerrado.length - 1)))
                continue
            }
            val i = t.indexOf('=')
            if (i <= 0) continue
            bloques.lastOrNull()?.add(t.substring(0, i).trim(), unesc(t.substring(i + 1)))
        }

        val created = cabecera["created"]?.toLongOrNull() ?: 0L
        val pos = cabecera["pos.lat"]?.toDoubleOrNull()?.let { lat ->
            cabecera["pos.lon"]?.toDoubleOrNull()?.let { lon ->
                FieldPosition(
                    latitude = lat,
                    longitude = lon,
                    altitudeMetres = cabecera["pos.alt"]?.toDoubleOrNull(),
                    accuracyMetres = cabecera["pos.acc"]?.toDoubleOrNull(),
                    source = runCatching {
                        PositionSource.valueOf(cabecera["pos.source"] ?: "")
                    }.getOrDefault(PositionSource.PHONE),
                    pointName = cabecera["pos.point_name"],
                    pointId = cabecera["pos.point_id"],
                    atEpochMillis = cabecera["pos.at"]?.toLongOrNull() ?: 0L,
                )
            }
        }

        val base = FieldEntry(
            id = id,
            type = type,
            createdEpochMillis = created,
            updatedEpochMillis = cabecera["updated"]?.toLongOrNull() ?: created,
            person = cabecera["person"] ?: "",
            position = pos,
            campaignId = cabecera["campaign"]?.takeIf { it.isNotBlank() },
        )

        return when (type) {
            EntryType.NOTE -> base.copy(
                title = bloques.firstOrNull { it.name == "note" }?.one("title") ?: "",
                items = bloques.filter { it.name == "item" }.mapNotNull { b ->
                    val kind = runCatching {
                        NoteItemKind.valueOf(b.one("kind") ?: "")
                    }.getOrNull() ?: return@mapNotNull null
                    NoteItem(
                        kind = kind,
                        atEpochMillis = b.long("at") ?: created,
                        text = b.one("text") ?: "",
                        file = b.one("file"),
                        durationMillis = b.long("duration_ms"),
                    )
                })

            EntryType.STAKE -> {
                val s = bloques.firstOrNull { it.name == "stake" }
                base.copy(
                    stakeName = s?.one("name") ?: "",
                    stakeLengthCm = s?.num("length_cm"),
                    measurements = bloques.filter { it.name == "measure" }.map { b ->
                        val g = GnssSession(
                            receiver = b.one("gnss.receiver") ?: "",
                            startEpochMillis = b.long("gnss.start"),
                            endEpochMillis = b.long("gnss.end"),
                            plannedMinutes = b.int("gnss.planned_min"),
                            antennaHeightCm = b.num("gnss.antenna_cm"),
                        )
                        StakeMeasurement(
                            atEpochMillis = b.long("at") ?: created,
                            person = b.one("person") ?: "",
                            exposedHeightCm = b.num("height_cm"),
                            photos = b.all("photo"),
                            gnss = g.takeIf { !it.isEmpty },
                        )
                    })
            }

            EntryType.GNSS -> {
                val g = bloques.firstOrNull { it.name == "gnss" }
                val sesion = GnssSession(
                    receiver = g?.one("receiver") ?: "",
                    startEpochMillis = g?.long("start"),
                    endEpochMillis = g?.long("end"),
                    plannedMinutes = g?.int("planned_min"),
                    antennaHeightCm = g?.num("antenna_cm"),
                )
                base.copy(
                    pointName = g?.one("name") ?: "",
                    gnss = sesion.takeIf { !it.isEmpty },
                    photos = g?.all("photo") ?: emptyList(),
                )
            }

            EntryType.DENDRO -> {
                val d = bloques.firstOrNull { it.name == "dendro" }
                base.copy(
                    sampleLabel = d?.one("label") ?: "",
                    species = d?.one("species") ?: "",
                    samplingHeightCm = d?.num("height_cm"),
                    trunkPerimeterCm = d?.num("perimeter_cm"),
                    notes = d?.one("notes") ?: "",
                    photos = d?.all("photo") ?: emptyList(),
                )
            }
        }
    }
}
