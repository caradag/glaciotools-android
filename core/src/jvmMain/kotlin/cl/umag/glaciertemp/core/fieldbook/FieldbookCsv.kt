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

    /**
     * Numeros con punto decimal SIEMPRE, sea cual sea el idioma del telefono.
     *
     * OCHO decimales, no cuatro. Por esta funcion pasan las latitudes y longitudes, y cuatro
     * decimales de grado son 11 metros: bastaba para no cuadrar con el punto que el receptor
     * habia medido al centimetro. Ocho son del orden del milimetro, mas fino que cualquier
     * dato que la app pueda producir. Los ceros de mas se recortan, asi que una altura de
     * antena de 1,5 cm sigue saliendo "1.5" y no "1.50000000".
     */
    fun num(v: Double?): String = v?.let {
        if (it == it.toLong().toDouble()) it.toLong().toString()
        else "%.8f".format(java.util.Locale.ROOT, it).trimEnd('0').trimEnd('.')
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

    /**
     * Las muestras cosmogenicas.
     *
     * LAS DESCRIPCIONES VAN EN EL CSV, aunque sean parrafos. Esta tabla es lo que se abre
     * junto a los resultados del laboratorio, y el sitio donde hay que mirar cuando una
     * edad sale rara es "que decia la nota de esa muestra". Dejarlas solo en el documento
     * obligaria a cruzar dos ficheros a mano, que es cuando se cruzan mal.
     *
     * El horizonte NO cabe aqui --son setenta y dos numeros por fila-- y va en el documento.
     */
    fun cosmo(entries: List<FieldEntry>, campaignName: (String?) -> String = { "" }): String =
        buildString {
            append(row("sample_name", "campaign", "collected_at", "collected_by",
                       "latitude", "longitude", "altitude_m_wgs84", "position_source",
                       "height_max_m", "height_min_m", "long_axis_m", "short_axis_m",
                       "strike_deg", "dip_deg", "shielding_factor",
                       "shielding_from_phone", "shielding_from_hand", "horizon_measured",
                       "horizon_points_manual",
                       "site", "place", "boulder", "surface",
                       "associated_images", "entry_id"))
            entries.filter { it.type == EntryType.COSMO }
                .sortedBy { it.createdEpochMillis }
                .forEach { e ->
                    val c = e.cosmo
                    append(row(
                        e.cosmoName.ifBlank { "(unnamed cosmo sample)" },
                        campaignName(e.campaignId),
                        time(e.createdEpochMillis), e.person,
                        e.position?.latitude, e.position?.longitude,
                        e.position?.altitudeMetres,
                        e.position?.source?.name ?: "",
                        c?.heightMaxM, c?.heightMinM, c?.longAxisM, c?.shortAxisM,
                        c?.strikeDeg, c?.dipDeg, c?.shieldingFactor,
                        c?.shieldingFromPhone, c?.shieldingFromManual,
                        if (c?.horizonDeg?.isNotEmpty() == true) "yes" else "no",
                        c?.manualAzimuths?.size ?: 0,
                        c?.site ?: "", c?.place ?: "", c?.boulder ?: "", c?.surface ?: "",
                        e.photos.size, e.id))
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
    // --------------------------------- aforos de caudal ---------------------------------

    /**
     * UN aforo, en UN fichero propio, con cabecera comentada.
     *
     * POR QUE UNO POR AFORO y no todos juntos como los demas tipos. Un aforo no es una fila:
     * es una tabla de treinta y cinco filas que solo significan algo juntas y en orden. Diez
     * aforos en un mismo CSV serian trescientas cincuenta filas que hay que volver a separar
     * a mano antes de poder hacer nada con ellas, y el primer paso de cualquiera que lo abra
     * seria deshacer la union. Separados, cada fichero se abre y ya es el perfil.
     *
     * LA CABECERA VA COMENTADA CON # y lleva TODO lo de la nota, no un resumen. Un CSV de
     * caudales sin saber donde, cuando ni con que ancho de tramo se midio no vale nada, y
     * adjuntar esos datos en otro fichero es garantizar que algun dia viajen separados.
     * Comentada con # porque asi las hojas de calculo y `pandas` la saltan solas.
     */
    fun gauging(
        e: FieldEntry,
        campaign: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        val g = e.gauging ?: StreamGauging()
        val tramos = Gauging.bins(g.widthM, g.intervalM)
        val r = Gauging.summarize(g)

        fun c(clave: String, valor: Any?) {
            val v = when (valor) { null -> ""; is Double -> num(valor); else -> valor.toString() }
            if (v.isNotBlank()) append("# ").append(clave).append(": ").append(v).append('\n')
        }
        fun hora(ms: Long?) = ms?.let { time(it, zone) }

        append("# GlacioTools stream gauging\n")
        c("profile", e.profileName)
        c("campaign", campaign)
        c("observer", e.person)
        c("created", hora(e.createdEpochMillis))
        c("entry_id", e.id)
        e.position?.let { p ->
            c("latitude", p.latitude)
            c("longitude", p.longitude)
            c("altitude_m_wgs84", p.altitudeMetres)
            c("position_accuracy_m", p.accuracyMetres)
            c("position_source", p.source.name)
            c("position_point", p.pointName)
            c("position_at", hora(p.atEpochMillis.takeIf { it > 0 }))
        }
        c("section_width_m", g.widthM)
        c("bin_interval_m", g.intervalM)
        c("bins", r.binCount)
        c("bins_complete", r.completeBins)
        // El metodo, escrito en el fichero. Dentro de diez anos nadie se acuerda de a que
        // profundidad se midio, y es lo que decide si el numero significa lo que dice.
        c("velocity_method", "single point at " +
                             num(Gauging.VELOCITY_DEPTH_FRACTION) + " of depth from surface")
        c("velocity_depth_shown_from", if (g.depthFromBed) "bed" else "surface")
        c("discharge_m3s", r.dischargeM3s)
        c("discharge_is_complete", if (r.isComplete) "yes" else "no")
        c("wetted_area_m2", r.areaM2)
        c("mean_velocity_mps", r.meanVelocityMps)
        c("max_velocity_mps", r.maxVelocityMps)
        c("mean_depth_m", r.meanDepthM)
        c("max_depth_m", r.maxDepthM)
        c("depth_first_measured", hora(r.depthTimes.firstMillis))
        c("depth_last_measured", hora(r.depthTimes.lastMillis))
        c("depth_median_time", hora(r.depthTimes.medianMillis))
        c("velocity_first_measured", hora(r.velocityTimes.firstMillis))
        c("velocity_last_measured", hora(r.velocityTimes.lastMillis))
        c("velocity_median_time", hora(r.velocityTimes.medianMillis))
        c("photos", e.photos.size.takeIf { it > 0 })
        // Los comentarios pueden traer saltos de linea, y una cabecera de # no los admite:
        // cada linea lleva su propio #, porque partir el comentario en dos dejaria la
        // segunda mitad como una fila de datos ilegible.
        if (g.comments.isNotBlank()) {
            append("# comments:\n")
            g.comments.lineSequence().forEach { append("#   ").append(it).append('\n') }
        }

        append(row("bin", "start_m", "end_m", "centre_m", "bin_width_m", "depth_m",
                   "velocity_depth_m", "velocity_depth_from", "velocity_mps",
                   "area_m2", "discharge_m3s",
                   "depth_first_edit", "depth_last_edit",
                   "velocity_first_edit", "velocity_last_edit"))
        tramos.forEach { t ->
            val b = g.bins.getOrElse(t.index) { GaugingBin() }
            append(row(
                t.index + 1, t.startM, t.endM, t.centreM, t.widthM, b.depthM,
                Gauging.measurementDepthM(b.depthM, g.depthFromBed),
                if (g.depthFromBed) "bed" else "surface",
                b.velocityMps,
                Gauging.binArea(t, b), Gauging.binDischarge(t, b),
                hora(b.depthFirstEditMillis), hora(b.depthLastEditMillis),
                hora(b.velocityFirstEditMillis), hora(b.velocityLastEditMillis)))
        }
    }

    fun folderName(raw: String): String {
        val limpio = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
            .take(60)
        return limpio.ifBlank { "unnamed" }
    }
}
