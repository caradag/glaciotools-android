package cl.umag.glaciertemp.core.fieldbook

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * El diario de campana como documento de texto.
 *
 * VA AL REVES QUE LA PANTALLA, y es deliberado. En la app los dias van del mas nuevo al mas
 * viejo porque lo que se busca es escribir lo de hoy. El documento se LEE, y un relato se lee
 * desde el principio: exportado al reves habria que empezar por el final para entender como
 * se llego alli.
 *
 * Las fotos van incrustadas en pequeno y los audios nombrados. El nombre que se escribe aqui
 * es el que el fichero tiene DENTRO DEL ZIP, no el interno del telefono: es lo unico que
 * permite pasar del documento a la carpeta de medios sin adivinar.
 */
object JournalOdt {

    private val FECHA = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
    private val HORA = DateTimeFormatter.ofPattern("HH:mm")

    fun build(
        entries: List<JournalEntry>,
        dayTitles: Map<String, String> = emptyMap(),
        media: FieldbookExport.Media = FieldbookExport.NoMedia,
        exportNames: Map<String, String> = emptyMap(),
        campaignName: String = "Field journal",
        zone: ZoneId = ZoneId.systemDefault(),
    ): ByteArray {
        val doc = OdtWriter()
        val tz = TimeZone.getTimeZone(zone)
        // Ascendente en los dos niveles: el dia mas viejo arriba y, dentro del dia, la
        // primera hora arriba. JournalDays.group() ordena los dias al reves para la pantalla.
        val dias = JournalDays.group(entries, dayTitles, tz).reversed()

        doc.title(campaignName)
        doc.meta(buildString {
            append("Journal  ·  ${entries.size} entr").append(if (entries.size == 1) "y" else "ies")
            append("  ·  ${dias.size} day").append(if (dias.size == 1) "" else "s")
            dias.firstOrNull()?.entries?.firstOrNull()?.let {
                append("  ·  from ").append(fecha(it.epochMillis, zone))
            }
            dias.lastOrNull()?.entries?.lastOrNull()?.takeIf { dias.size > 1 }?.let {
                append(" to ").append(fecha(it.epochMillis, zone))
            }
        })

        dias.forEach { dia ->
            val cuando = dia.entries.firstOrNull()?.epochMillis
            // Fecha SIEMPRE, titulo del dia si lo tiene. El titulo es lo que uno recuerda
            // ("el dia del temporal"), la fecha es lo que permite cruzarlo con los datos.
            doc.heading1(cuando?.let { fecha(it, zone) } ?: dia.key)
            if (dia.title.isNotBlank()) doc.meta(dia.title)

            dia.entries.forEach { e ->
                doc.heading2(hora(e.epochMillis, zone) +
                             (if (e.title.isBlank()) "" else "  ${e.title}"))
                if (e.text.isNotBlank())
                    e.text.split("\n").filter { it.isNotBlank() }.forEach { doc.body(it) }

                e.photos.forEach { nombre ->
                    val pie = exportNames[nombre] ?: nombre
                    val img = media.preview(nombre)
                    if (img != null) doc.photo(img, pie)
                    else doc.meta("[photo $pie is no longer on the phone]")
                }
                e.audio.forEach { a ->
                    val pie = exportNames[a.file] ?: a.file
                    doc.meta("Audio note: $pie" +
                             (a.durationMillis?.takeIf { it > 0 }?.let { "  (${duracion(it)})" } ?: ""))
                }
            }
            doc.rule()
        }

        if (dias.isEmpty()) doc.body("This journal has no entries.")
        return doc.build()
    }

    private fun instante(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)
    private fun fecha(ms: Long, zone: ZoneId) = FECHA.format(instante(ms, zone))
    private fun hora(ms: Long, zone: ZoneId) = HORA.format(instante(ms, zone))

    private fun duracion(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
