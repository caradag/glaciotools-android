package cl.umag.glaciertemp.core.fieldbook

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Los tres CSV de la exportacion: mediciones GNSS, muestras dendro y lecturas de baliza.
 *
 * Una fila por MEDICION y no por entrada. Una baliza con seis lecturas son seis filas, y una
 * medicion GNSS hecha sobre una baliza es una fila mas del fichero de GNSS: quien abra el CSV
 * de GNSS quiere todos los puntos ocupados, le den igual de donde cuelguen en la libreta.
 *
 * LAS FOTOS NO VAN EN EL CSV, va su NUMERO. La columna `associated_images` dice cuantas hay y
 * la carpeta con el nombre del punto las contiene. Meter los nombres de fichero en la celda
 * habria hecho una columna de longitud variable que ninguna hoja de calculo sabe usar, y que
 * ademas se rompe en cuanto una foto se renombra.
 */
object FieldbookCsv {

    /** ISO 8601 local con offset: se lee a ojo y ninguna hoja de calculo lo reinterpreta mal. */
    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    fun time(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        ms?.let { TS.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""

    /**
     * Una celda de CSV segun RFC 4180.
     *
     * Se entrecomilla en cuanto hay coma, comilla o salto de linea -- y las notas de terreno
     * llevan las tres cosas. Sin esto, una nota con una coma desplaza todas las columnas que
     * vienen detras y el fichero se lee mal sin dar ningun error.
     */
    fun cell(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Double -> num(v)
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
    }

    /** Numeros con punto decimal SIEMPRE, sea cual sea el idioma del telefono. */
    fun num(v: Double?): String = v?.let {
        if (it == it.toLong().toDouble()) it.toLong().toString()
        else "%.4f".format(java.util.Locale.ROOT, it).trimEnd('0').trimEnd('.')
    } ?: ""

    private fun row(vararg cells: Any?): String = cells.joinToString(",") { cell(it) } + "\n"

    // --------------------------------- mediciones GNSS ---------------------------------

    /** Una ocupacion de punto, venga de una entrada GNSS o de la lectura de una baliza. */
    data class GnssRow(
        val entryId: String,
        val pointName: String,
        /** GNSS o STAKE: de donde cuelga en la libreta. */
        val source: String,
        val person: String,
        val session: GnssSession,
        val position: FieldPosition?,
        val photos: Int,
        val campaign: String,
    )

    /**
     * Todas las ocupaciones de punto de la libreta, en orden cronologico.
     *
     * Las de baliza heredan la coordenada y la persona de su lectura, no de la entrada: la
     * lectura es la que tiene el dato de esa visita, y una baliza visitada tres veces tiene
     * tres personas posibles.
     */
    fun gnssRows(entries: List<FieldEntry>, campaignName: (String?) -> String): List<GnssRow> =
        entries.flatMap { e ->
            when (e.type) {
                EntryType.GNSS -> e.gnss?.let {
                    listOf(GnssRow(e.id, e.pointName.ifBlank { "(unnamed point)" }, "GNSS",
                                   e.person, it, e.position, e.photos.size,
                                   campaignName(e.campaignId)))
                } ?: emptyList()

                EntryType.STAKE -> Ablation.chronological(e.measurements).mapNotNull { m ->
                    m.gnss?.takeIf { !it.isEmpty }?.let { g ->
                        GnssRow(e.id, e.stakeName.ifBlank { "(unnamed stake)" }, "STAKE",
                                m.person.ifBlank { e.person }, g, e.position, m.photos.size,
                                campaignName(e.campaignId))
                    }
                }

                else -> emptyList()
            }
        }.sortedBy { it.session.startEpochMillis ?: 0L }

    fun gnss(entries: List<FieldEntry>, campaignName: (String?) -> String = { "" }): String =
        buildString {
            append(row("point_name", "source", "campaign", "observer", "receiver",
                       "antenna_height_cm", "start_time", "end_time", "duration_s",
                       "planned_duration_min", "latitude", "longitude", "altitude_m_wgs84",
                       "position_accuracy_m", "position_source", "associated_images",
                       "entry_id"))
            gnssRows(entries, campaignName).forEach { r ->
                append(row(
                    r.pointName, r.source, r.campaign, r.person, r.session.receiver,
                    r.session.antennaHeightCm,
                    time(r.session.startEpochMillis), time(r.session.endEpochMillis),
                    r.session.durationMillis?.let { it / 1000 },
                    r.session.plannedMinutes,
                    r.position?.latitude, r.position?.longitude,
                    r.position?.altitudeMetres, r.position?.accuracyMetres,
                    r.position?.source?.name ?: "",
                    r.photos, r.entryId))
            }
        }

    // ------------------------------------- balizas -------------------------------------

    /**
     * Una fila por LECTURA, con la tasa contra la lectura anterior de esa misma baliza.
     *
     * La tasa se calcula aqui y no se deja para la hoja de calculo porque depende del orden
     * cronologico dentro de cada baliza, que es justo lo que se pierde al ordenar la tabla por
     * otra columna. Calculada, sigue siendo correcta se mire como se mire.
     */
    fun stakes(entries: List<FieldEntry>, campaignName: (String?) -> String = { "" }): String =
        buildString {
            append(row("stake_name", "campaign", "reading_index", "measured_at", "measured_by",
                       "exposed_height_cm", "stake_length_cm", "days_since_previous",
                       "ablation_rate_cm_per_day", "gnss_receiver", "gnss_antenna_height_cm",
                       "gnss_start_time", "gnss_end_time", "latitude", "longitude",
                       "associated_images", "entry_id"))
            entries.filter { it.type == EntryType.STAKE }
                .sortedBy { it.createdEpochMillis }
                .forEach { e ->
                    val orden = Ablation.chronological(e.measurements)
                    val tasas = Ablation.rates(e.measurements)
                    orden.forEachIndexed { i, m ->
                        val dias = if (i == 0) null
                                   else (m.atEpochMillis - orden[i - 1].atEpochMillis) /
                                        Ablation.MILLIS_PER_DAY
                        append(row(
                            e.stakeName.ifBlank { "(unnamed stake)" },
                            campaignName(e.campaignId),
                            i + 1,
                            time(m.atEpochMillis),
                            m.person.ifBlank { e.person },
                            m.exposedHeightCm,
                            e.stakeLengthCm,
                            dias,
                            tasas.getOrNull(i),
                            m.gnss?.receiver ?: "",
                            m.gnss?.antennaHeightCm,
                            time(m.gnss?.startEpochMillis),
                            time(m.gnss?.endEpochMillis),
                            e.position?.latitude, e.position?.longitude,
                            m.photos.size, e.id))
                    }
                }
        }

    // -------------------------------------- dendro --------------------------------------

    fun dendro(entries: List<FieldEntry>, campaignName: (String?) -> String = { "" }): String =
        buildString {
            append(row("sample_label", "campaign", "collected_at", "collected_by", "species",
                       "sampling_height_cm", "trunk_perimeter_cm", "latitude", "longitude",
                       "altitude_m_wgs84", "position_source", "notes", "associated_images",
                       "entry_id"))
            entries.filter { it.type == EntryType.DENDRO }
                .sortedBy { it.createdEpochMillis }
                .forEach { e ->
                    append(row(
                        e.sampleLabel.ifBlank { "(unlabelled sample)" },
                        campaignName(e.campaignId),
                        time(e.createdEpochMillis), e.person, e.species,
                        e.samplingHeightCm, e.trunkPerimeterCm,
                        e.position?.latitude, e.position?.longitude,
                        e.position?.altitudeMetres,
                        e.position?.source?.name ?: "",
                        e.notes, e.photos.size, e.id))
                }
        }

    // --------------------------- nombres de carpeta de medios ---------------------------

    /**
     * El nombre de carpeta de un punto o una baliza.
     *
     * Se limpia a lo que sobrevive en cualquier sistema de ficheros: dos nombres distintos
     * pueden quedar iguales despues de limpiar --"E-12" y "E 12"-- asi que quien las crea
     * tiene que desempatar. Se hace en [FieldbookExport], no aqui.
     */
    fun folderName(raw: String): String {
        val limpio = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
            .take(60)
        return limpio.ifBlank { "unnamed" }
    }
}
