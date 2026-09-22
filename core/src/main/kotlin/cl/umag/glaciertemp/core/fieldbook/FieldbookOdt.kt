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

    private fun tipoEnPalabras(t: EntryType): String = when (t) {
        EntryType.NOTE -> "General note"
        EntryType.STAKE -> "Stake measurement"
        EntryType.GNSS -> "GNSS measurement"
        EntryType.DENDRO -> "Dendro sample"
    }
}
