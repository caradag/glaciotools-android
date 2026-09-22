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
    const val PICTURES = "Pictures"
    const val AUDIO = "Audio"

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
    ): Result {
        val nombreCampana: (String?) -> String = { id ->
            campaigns.firstOrNull { it.id == id }?.displayName() ?: ""
        }
        val carpetas = folderFor(entries)
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
        return Result(entries.size, fotos, audios, ausentes)
    }

    data class Result(
        val entries: Int,
        val photos: Int,
        val audioNotes: Int,
        /** Medios que la libreta nombra y que ya no estan en el telefono. */
        val missingMedia: Int,
    ) {
        fun describe(): String = buildString {
            append("$entries entr").append(if (entries == 1) "y" else "ies")
            append(", $photos photo").append(if (photos == 1) "" else "s")
            append(", $audioNotes audio note").append(if (audioNotes == 1) "" else "s")
            if (missingMedia > 0) append("  ·  $missingMedia file(s) missing from the phone")
        }
    }
}
