package cl.umag.glaciertemp.core.fieldbook

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * La exportacion completa de la libreta: tres CSV, un ODT, y los medios en carpetas.
 *
 * Se escribe directamente sobre un [OutputStream] y no se construye en memoria. Una campana
 * con doscientas fotos son cientos de megabytes; armar el zip como ByteArray funcionaria en el
 * escritorio y mataria la app en el telefono justo al final de la campana, que es el peor
 * momento posible para perder algo.
 *
 * Los medios los aporta quien llama a traves de [Media], porque `core` no sabe abrir ficheros
 * de Android ni decodificar JPEG. Eso ademas es lo que permite probar la exportacion entera
 * --incluido el ODT con sus imagenes-- con una fuente falsa y sin telefono.
 */
object FieldbookExport {

    /** De donde salen los bytes de una foto o un audio. */
    interface Media {
        /** El fichero original, tal cual. Null si ya no esta en el telefono. */
        fun open(name: String): InputStream?

        /**
         * Una version reducida para incrustar en el ODT, o null si no se puede.
         *
         * Reducida y no la original: una campana de doscientas fotos de 4 MB daria un ODT de
         * 800 MB que ningun procesador de textos abre. La vista previa es para reconocer la
         * foto; la original va en su carpeta, intacta.
         */
        fun preview(name: String): OdtWriter.Image?
    }

    /** Una fuente vacia: sirve para probar el texto sin medios. */
    object NoMedia : Media {
        override fun open(name: String): InputStream? = null
        override fun preview(name: String): OdtWriter.Image? = null
    }

    const val GNSS_CSV = "gnss_measurements.csv"
    const val STAKE_CSV = "stake_measurements.csv"
    const val DENDRO_CSV = "dendro_samples.csv"
    const val NOTEBOOK_ODT = "fieldbook.odt"
    const val JOURNAL_ODT = "journal.odt"
    const val PICTURES = "Pictures"
    const val AUDIO = "Audio"

    /**
     * Fotos Y audios del diario, TODOS EN UNA CARPETA.
     *
     * A diferencia de los medios de las notas, que se reparten por punto o baliza, los del
     * diario no cuelgan de ningun objeto medido: cuelgan de un rato de un dia. Repartirlos
     * por entrada daria cien carpetas de una foto cada una. Lo que los ordena es la marca de
     * tiempo del nombre, que ademas es lo que los hace unicos.
     */
    const val JOURNAL_MEDIA = "Journal"

    /**
     * Donde va cada foto: `Pictures/<nombre del punto o baliza>/`.
     *
     * Dos puntos distintos pueden dar el mismo nombre de carpeta al limpiarlos ("E-12" y
     * "E 12"), y entonces sus fotos se mezclarian sin que nada lo dijera. Se desempata con un
     * sufijo numerico, que es feo pero no pierde nada.
     */
    fun folderFor(entries: List<FieldEntry>): Map<String, String> {
        val usados = HashMap<String, String>()   // carpeta -> id que la ocupo
        val salida = HashMap<String, String>()   // id de entrada -> carpeta
        entries.sortedBy { it.createdEpochMillis }.forEach { e ->
            val base = FieldbookCsv.folderName(when (e.type) {
                EntryType.STAKE -> e.stakeName.ifBlank { "unnamed stake" }
                EntryType.GNSS -> e.pointName.ifBlank { "unnamed point" }
                EntryType.DENDRO -> e.sampleLabel.ifBlank { "unlabelled sample" }
                EntryType.NOTE -> e.title().ifBlank { "note" }
            })
            var nombre = base
            var n = 2
            while (usados[nombre].let { it != null && it != e.id }) {
                nombre = "$base ($n)"; n++
            }
            usados[nombre] = e.id
            salida[e.id] = nombre
        }
        return salida
    }

    /**
     * Como se llama cada foto y cada audio del diario DENTRO DEL ZIP.
     *
     * El nombre interno ("m1790360309068-414081.jpg") no dice nada al abrirlo en un
     * ordenador. Se rebautizan con la marca de tiempo de SU ENTRADA, que es lo que permite
     * ordenar la carpeta por nombre y que salga el orden del terreno, y cruzarla con el
     * documento sin abrir cada fichero.
     *
     * Dos medios de la misma entrada caen en el mismo segundo, asi que el que repite lleva
     * un sufijo. Sin el, el segundo pisaria al primero dentro del zip.
     */
    fun journalMediaNames(
        entries: List<JournalEntry>,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Map<String, String> {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val usados = HashSet<String>()
        val salida = LinkedHashMap<String, String>()
        entries.sortedBy { it.epochMillis }.forEach { e ->
            val marca = f.format(java.time.Instant.ofEpochMilli(e.epochMillis).atZone(zone))
            e.mediaFiles().forEach { interno ->
                if (interno in salida) return@forEach
                val ext = interno.substringAfterLast('.', "").lowercase()
                val base = if (ext.isEmpty()) marca else "$marca.$ext"
                var nombre = base
                var n = 2
                while (nombre in usados) {
                    nombre = if (ext.isEmpty()) "${marca}_$n" else "${marca}_$n.$ext"
                    n++
                }
                usados += nombre
                salida[interno] = nombre
            }
        }
        return salida
    }

    /** Nombre propuesto para el fichero. Lleva la fecha porque se exporta mas de una vez. */
    fun suggestedName(campaign: Campaign?, now: Long = System.currentTimeMillis()): String {
        val t = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
            .format(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()))
        val quien = campaign?.let { FieldbookCsv.folderName(it.displayName()) } ?: "all notes"
        return "fieldbook_${quien.replace(' ', '_')}_$t.zip"
    }

    /**
     * Escribe el zip entero.
     *
     * Un medio que ya no esta en el telefono se SALTA y se cuenta; no aborta la exportacion.
     * Perder una foto es malo, pero quedarse sin exportar la campana entera por culpa de una
     * foto borrada es peor -- y el resumen dice cuantas faltaron, que es lo que permite darse
     * cuenta.
     */
    fun writeZip(
        out: OutputStream,
        entries: List<FieldEntry>,
        media: Media = NoMedia,
        campaigns: List<Campaign> = emptyList(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        journal: List<JournalEntry> = emptyList(),
        dayTitles: Map<String, String> = emptyMap(),
        // El diario guarda sus medios en OTRA carpeta, asi que necesita su propia fuente.
        journalMedia: Media = NoMedia,
    ): Result {
        val nombreCampana: (String?) -> String = { id ->
            campaigns.firstOrNull { it.id == id }?.displayName() ?: ""
        }
        val carpetas = folderFor(entries)
        val nombresDiario = journalMediaNames(journal, zone)
        var fotos = 0
        var audios = 0
        var ausentes = 0

        ZipOutputStream(out).use { zip ->
            fun texto(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            texto(GNSS_CSV, FieldbookCsv.gnss(entries, nombreCampana))
            texto(STAKE_CSV, FieldbookCsv.stakes(entries, nombreCampana))
            texto(DENDRO_CSV, FieldbookCsv.dendro(entries, nombreCampana))

            zip.putNextEntry(ZipEntry(NOTEBOOK_ODT))
            zip.write(FieldbookOdt.build(entries, media, campaigns, zone))
            zip.closeEntry()

            if (journal.isNotEmpty()) {
                val deQuien = campaigns.firstOrNull { c -> journal.any { it.campaignId == c.id } }
                zip.putNextEntry(ZipEntry(JOURNAL_ODT))
                zip.write(JournalOdt.build(
                    journal, dayTitles, journalMedia, nombresDiario,
                    deQuien?.displayName() ?: "Field journal", zone))
                zip.closeEntry()

                journal.forEach { e ->
                    e.photos.forEach { interno ->
                        val entrada = journalMedia.open(interno)
                        if (entrada == null) { ausentes++; return@forEach }
                        zip.putNextEntry(ZipEntry(
                            "$JOURNAL_MEDIA/${nombresDiario[interno] ?: interno}"))
                        entrada.use { it.copyTo(zip) }
                        zip.closeEntry()
                        fotos++
                    }
                    e.audio.map { it.file }.forEach { interno ->
                        val entrada = journalMedia.open(interno)
                        if (entrada == null) { ausentes++; return@forEach }
                        zip.putNextEntry(ZipEntry(
                            "$JOURNAL_MEDIA/${nombresDiario[interno] ?: interno}"))
                        entrada.use { it.copyTo(zip) }
                        zip.closeEntry()
                        audios++
                    }
                }
            }

            entries.forEach { e ->
                val carpeta = carpetas[e.id] ?: FieldbookCsv.folderName(e.id)
                val fotosDe = e.photos + e.measurements.flatMap { it.photos } +
                              e.items.filter { it.kind == NoteItemKind.PHOTO }.mapNotNull { it.file }
                fotosDe.forEach { nombre ->
                    val entrada = media.open(nombre)
                    if (entrada == null) { ausentes++; return@forEach }
                    zip.putNextEntry(ZipEntry("$PICTURES/$carpeta/$nombre"))
                    entrada.use { it.copyTo(zip) }
                    zip.closeEntry()
                    fotos++
                }
                e.items.filter { it.kind == NoteItemKind.AUDIO }.mapNotNull { it.file }
                    .forEach { nombre ->
                        val entrada = media.open(nombre)
                        if (entrada == null) { ausentes++; return@forEach }
                        zip.putNextEntry(ZipEntry("$AUDIO/$carpeta/$nombre"))
                        entrada.use { it.copyTo(zip) }
                        zip.closeEntry()
                        audios++
                    }
            }
        }
        return Result(entries.size, fotos, audios, ausentes, journal.size)
    }

    data class Result(
        val entries: Int,
        val photos: Int,
        val audioNotes: Int,
        /** Medios que la libreta nombra y que ya no estan en el telefono. */
        val missingMedia: Int,
        val journalEntries: Int = 0,
    ) {
        fun describe(): String = buildString {
            append("$entries entr").append(if (entries == 1) "y" else "ies")
            if (journalEntries > 0)
                append(", $journalEntries journal entr").append(if (journalEntries == 1) "y" else "ies")
            append(", $photos photo").append(if (photos == 1) "" else "s")
            append(", $audioNotes audio note").append(if (audioNotes == 1) "" else "s")
            if (missingMedia > 0) append("  ·  $missingMedia file(s) missing from the phone")
        }
    }
}
