package cl.umag.glaciertemp.core.fieldbook

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * La libreta entera como documento de texto, en orden cronologico.
 *
 * Es la unica pieza de la exportacion pensada para LEERSE, no para procesarse: los CSV tienen
 * las cifras y este tiene el relato. Por eso lleva todo lo que se puede decir con palabras
 * --incluidos los campos que en el CSV son columnas-- mas las vistas previas de las fotos y
 * un aviso de que hay audio. Un audio no se puede poner en un documento, pero saber que
 * existe y como se llama su fichero es lo que permite ir a buscarlo a su carpeta.
 *
 * EL ORDEN ES CRONOLOGICO Y CRUZA LOS CUATRO TIPOS. Es lo contrario de los CSV, que agrupan
 * por tipo. Un dia de terreno no ocurre por tipos: ocurre en orden, y leerlo asi es lo que
 * permite reconstruir que se hizo.
 */
object FieldbookOdt {

    private val FECHA = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
    private val HORA = DateTimeFormatter.ofPattern("HH:mm")
    private val FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun build(
        entries: List<FieldEntry>,
        media: FieldbookExport.Media = FieldbookExport.NoMedia,
        campaigns: List<Campaign> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ByteArray {
        val doc = OdtWriter()
        val orden = entries.sortedBy { it.createdEpochMillis }

        val campana = campaigns.firstOrNull { c -> orden.any { it.campaignId == c.id } }
        doc.title(campana?.displayName() ?: "Field notebook")
        doc.meta(buildString {
            append("${orden.size} entr").append(if (orden.size == 1) "y" else "ies")
            orden.firstOrNull()?.let {
                append("  ·  from ").append(fecha(it.createdEpochMillis, zone))
            }
            orden.lastOrNull()?.takeIf { orden.size > 1 }?.let {
                append(" to ").append(fecha(it.createdEpochMillis, zone))
            }
        })
        campana?.archivedEpochMillis?.let {
            doc.meta("Campaign archived ${FECHA_HORA.format(instante(it, zone))}")
        }

        // Un encabezado por DIA. Es la division que tiene el trabajo de terreno, y sin ella
        // treinta entradas seguidas se leen como una lista y no como una campana.
        var diaActual: String? = null
        orden.forEach { e ->
            val dia = fecha(e.createdEpochMillis, zone)
            if (dia != diaActual) { diaActual = dia; doc.heading1(dia) }
            entrada(doc, e, media, zone)
            doc.rule()
        }
        if (orden.isEmpty()) doc.body("This notebook has no entries.")
        return doc.build()
    }

    private fun instante(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)
    private fun fecha(ms: Long, zone: ZoneId) = FECHA.format(instante(ms, zone))
    private fun hora(ms: Long, zone: ZoneId) = HORA.format(instante(ms, zone))
    private fun fechaHora(ms: Long?, zone: ZoneId) =
        ms?.let { FECHA_HORA.format(instante(it, zone)) } ?: "—"

    private fun entrada(
        doc: OdtWriter, e: FieldEntry, media: FieldbookExport.Media, zone: ZoneId,
    ) {
        doc.heading2("${hora(e.createdEpochMillis, zone)}  ·  ${e.title()}")

        val cabecera = ArrayList<String>()
        cabecera += tipoEnPalabras(e.type)
        if (e.person.isNotBlank()) cabecera += e.person
        e.position?.let { cabecera += "${it.describe()} (${it.detail()})" }
        doc.meta(cabecera.joinToString("  ·  "))

        when (e.type) {
            EntryType.NOTE -> e.items.forEach { item ->
                when (item.kind) {
                    NoteItemKind.TEXT -> if (item.text.isNotBlank()) {
                        doc.meta(hora(item.atEpochMillis, zone))
                        doc.multiline(item.text)
                    }
                    NoteItemKind.PHOTO -> item.file?.let {
                        foto(doc, media, it, "${hora(item.atEpochMillis, zone)}  ·  $it")
                    }
                    // El audio no cabe en un documento, asi que va su ficha: sin ella, el
                    // fichero de la carpeta Audio no se sabria a que anotacion pertenece.
                    NoteItemKind.AUDIO -> doc.meta(buildString {
                        append(hora(item.atEpochMillis, zone)).append("  ·  audio note")
                        item.durationMillis?.takeIf { it > 0 }?.let {
                            append(" (").append(it / 1000).append(" s)")
                        }
                        item.file?.let { append("  ·  ").append(it) }
                    })
                }
            }

            EntryType.STAKE -> {
                e.stakeLengthCm?.let { doc.body("Total stake length: ${FieldbookCsv.num(it)} cm") }
                val orden = Ablation.chronological(e.measurements)
                val tasas = Ablation.rates(e.measurements)
                if (orden.isEmpty()) doc.body("No readings.")
                orden.forEachIndexed { i, m ->
                    doc.body(buildString {
                        append(fechaHora(m.atEpochMillis, zone))
                        append("  ·  exposed ")
                        append(m.exposedHeightCm?.let { "${FieldbookCsv.num(it)} cm" } ?: "—")
                        tasas.getOrNull(i)?.let {
                            append("  ·  ").append("%+.1f".format(java.util.Locale.ROOT, it))
                            append(" cm/day")
                        }
                        if (m.person.isNotBlank()) append("  ·  ").append(m.person)
                    })
                    m.gnss?.takeIf { !it.isEmpty }?.let { sesionGnss(doc, it, zone) }
                    m.photos.forEach { foto(doc, media, it, it) }
                }
                if (orden.size > 1) {
                    doc.meta("Rate is the change in exposed height over the days between two " +
                             "consecutive readings; positive means ablation.")
                }
            }

            EntryType.GNSS -> {
                e.gnss?.let { sesionGnss(doc, it, zone) } ?: doc.body("No measurement recorded.")
                e.photos.forEach { foto(doc, media, it, it) }
            }

            EntryType.DENDRO -> {
                val campos = ArrayList<String>()
                if (e.species.isNotBlank()) campos += "Species: ${e.species}"
                e.samplingHeightCm?.let { campos += "Sampling height: ${FieldbookCsv.num(it)} cm" }
                e.trunkPerimeterCm?.let { campos += "Trunk perimeter: ${FieldbookCsv.num(it)} cm" }
                campos.forEach { doc.body(it) }
                if (e.notes.isNotBlank()) doc.multiline(e.notes)
                e.photos.forEach { foto(doc, media, it, it) }
            }

            EntryType.COSMO -> {
                val c = e.cosmo
                if (c != null) {
                    // EN EL ORDEN EN QUE SE MIRA EN TERRENO: primero donde estamos, luego el
                    // bloque, luego la superficie de la que sale la muestra, y al final el
                    // cielo que la rodea. Quien lea esto meses despues reconstruye la visita.
                    if (c.site.isNotBlank()) { doc.body("Site"); doc.multiline(c.site) }
                    if (c.place.isNotBlank()) { doc.body("Place"); doc.multiline(c.place) }

                    val medidas = ArrayList<String>()
                    c.heightMaxM?.let { medidas += "max height ${FieldbookCsv.num(it)} m" }
                    c.heightMinM?.let { medidas += "min height ${FieldbookCsv.num(it)} m" }
                    c.longAxisM?.let { medidas += "long axis ${FieldbookCsv.num(it)} m" }
                    c.shortAxisM?.let { medidas += "short axis ${FieldbookCsv.num(it)} m" }
                    if (medidas.isNotEmpty()) doc.body("Boulder: " + medidas.joinToString(", "))
                    if (c.boulder.isNotBlank()) doc.multiline(c.boulder)
                    if (c.surface.isNotBlank()) {
                        doc.body("Sampled surface"); doc.multiline(c.surface)
                    }

                    val apantalla = ArrayList<String>()
                    c.strikeDeg?.let { apantalla += "strike ${FieldbookCsv.num(it)}°" }
                    c.dipDeg?.let { apantalla += "dip ${FieldbookCsv.num(it)}°" }
                    c.shieldingFactor?.let { apantalla += "shielding factor %.4f".format(java.util.Locale.ROOT, it) }
                    c.shieldingFromPhone?.let { apantalla += "from phone sweep %.4f".format(java.util.Locale.ROOT, it) }
                    c.shieldingFromManual?.let { apantalla += "from hand survey %.4f".format(java.util.Locale.ROOT, it) }
                    if (apantalla.isNotEmpty())
                        doc.body("Shielding: " + apantalla.joinToString(", "))
                    if (c.manualAzimuths.isNotEmpty()) {
                        doc.meta("Horizon surveyed by hand, azimuth then elevation in degrees:")
                        doc.meta(c.manualAzimuths.joinToString(" ") { FieldbookCsv.num(it) })
                        doc.meta(c.manualElevations.joinToString(" ") { FieldbookCsv.num(it) })
                    }
                    if (c.horizonDeg.isNotEmpty()) {
                        // El perfil ENTERO va al documento: es la medida, y el factor solo
                        // una cuenta hecha sobre ella que cualquiera puede querer rehacer.
                        doc.meta("Horizon, elevation in degrees every ${c.horizonBinDeg}° " +
                                 "of azimuth from north:")
                        doc.meta(c.horizonDeg.joinToString(" ") { FieldbookCsv.num(it) })
                    }
                }
                e.photos.forEach { foto(doc, media, it, it) }
            }

            EntryType.GAUGING -> {
                val g = e.gauging
                if (g != null) {
                    val r = Gauging.summarize(g)
                    val cab = ArrayList<String>()
                    g.widthM?.let { cab += "width ${FieldbookCsv.num(it)} m" }
                    g.intervalM?.let { cab += "interval ${FieldbookCsv.num(it)} m" }
                    cab += "${r.binCount} bin(s)"
                    doc.body("Section: " + cab.joinToString(", "))

                    // EL CAUDAL, Y SI ESTA COMPLETO. Un total parcial y uno terminado se
                    // parecen demasiado escritos a secas, y solo el segundo se puede citar.
                    r.dischargeM3s?.let {
                        doc.body("Discharge: ${FieldbookCsv.num(it)} m3/s" +
                                 if (r.isComplete) ""
                                 else "  (PARTIAL: ${r.completeBins} of ${r.binCount} bins)")
                    }
                    r.areaM2?.let { doc.body("Wetted area: ${FieldbookCsv.num(it)} m2") }
                    val vel = ArrayList<String>()
                    r.meanVelocityMps?.let { vel += "mean ${FieldbookCsv.num(it)} m/s" }
                    r.maxVelocityMps?.let { vel += "max ${FieldbookCsv.num(it)} m/s" }
                    if (vel.isNotEmpty()) doc.body("Velocity: " + vel.joinToString(", "))

                    if (!r.depthTimes.isEmpty) doc.meta(
                        "Depths measured " + rango(r.depthTimes, zone))
                    if (!r.velocityTimes.isEmpty) doc.meta(
                        "Velocities measured " + rango(r.velocityTimes, zone))

                    // LA TABLA NO VA AQUI: va a su propio CSV, uno por aforo. Treinta y
                    // cinco filas de numeros dentro de un documento de texto no se leen ni
                    // se pueden usar para nada, y el CSV se abre en una hoja de calculo.
                    doc.meta("The full table of bins is in its own CSV file.")
                    if (g.comments.isNotBlank()) {
                        doc.body("Comments"); doc.multiline(g.comments)
                    }

                    // LA DILUCION, si se midio. El mismo perfil por los dos metodos es justo
                    // lo que se quiere poder comparar, asi que van uno debajo del otro.
                    val s = g.salt
                    if (s != null && s.readings.isNotEmpty()) {
                        val rs = SaltDilutionMath.compute(s)
                        doc.body("Salt dilution: " + (rs.dischargeM3s?.let {
                            "discharge ${FieldbookCsv.num(it)} m3/s"
                        } ?: "discharge not calculated"))
                        val det = ArrayList<String>()
                        s.saltMassG?.let { det += "salt ${FieldbookCsv.num(it)} g" }
                        s.calibrationFactor?.let { det += "Cal ${FieldbookCsv.num(it)} (mg/L)/(µS/cm)" }
                        s.baseConductivity?.let { det += "base ${FieldbookCsv.num(it)} µS/cm" }
                        rs.peakExcess?.let { det += "peak +${FieldbookCsv.num(it)} µS/cm" }
                        s.injectionDistanceM?.let { det += "injected ${FieldbookCsv.num(it)} m upstream" }
                        if (det.isNotEmpty()) doc.meta(det.joinToString(", "))
                        doc.meta("${s.readings.size} conductivity reading(s), in their own CSV file.")
                        if (s.injectionNotes.isNotBlank()) {
                            doc.body("Injection point"); doc.multiline(s.injectionNotes)
                        }
                    }
                }
                e.photos.forEach { foto(doc, media, it, it) }
            }
        }
    }

    private fun sesionGnss(doc: OdtWriter, g: GnssSession, zone: ZoneId) {
        doc.body(buildString {
            append("GNSS: ").append(g.receiver.ifBlank { "receiver not recorded" })
            append("  ·  antenna ")
            append(g.antennaHeightCm?.let { "${FieldbookCsv.num(it)} cm" } ?: "NOT RECORDED")
        })
        doc.body(buildString {
            append("Start ").append(fechaHora(g.startEpochMillis, zone))
            append("  ·  end ").append(fechaHora(g.endEpochMillis, zone))
            g.durationMillis?.let {
                append("  ·  ").append(it / 60000).append(" min")
            }
            g.plannedMinutes?.let { append("  (planned ").append(it).append(" min)") }
        })
    }

    /**
     * Una foto, o una linea que dice que falta.
     *
     * Decirlo importa: un hueco silencioso en el documento se lee como "no habia foto", y lo
     * que pasa es que la foto estaba y ya no esta en el telefono.
     */
    private fun foto(doc: OdtWriter, media: FieldbookExport.Media, nombre: String,
                     pie: String) {
        val img = media.preview(nombre)
        if (img != null) doc.photo(img, pie)
        else doc.meta("[photo $nombre is no longer on the phone]")
    }

    /** "de 10:05 a 11:12, mediana 10:39" -- lo que fecha un aforo que duro una hora. */
    private fun rango(t: Gauging.TimeSpan, zone: ZoneId): String = buildString {
        append("from ").append(hora(t.firstMillis ?: 0L, zone))
        append(" to ").append(hora(t.lastMillis ?: 0L, zone))
        t.medianMillis?.let { append(", median ").append(hora(it, zone)) }
    }

    private fun tipoEnPalabras(t: EntryType): String = when (t) {
        EntryType.NOTE -> "General note"
        EntryType.STAKE -> "Stake measurement"
        EntryType.GNSS -> "GNSS measurement"
        EntryType.DENDRO -> "Dendro sample"
        EntryType.COSMO -> "Cosmogenic isotopes sample"
        EntryType.GAUGING -> "Stream gauging"
    }
}
