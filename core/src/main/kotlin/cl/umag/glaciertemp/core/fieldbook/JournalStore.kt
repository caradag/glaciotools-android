package cl.umag.glaciertemp.core.fieldbook

import java.io.File

/**
 * El diario en disco.
 *
 * ALMACEN Y CARPETA DE MEDIOS PROPIOS, y lo segundo no es cosmetico:
 * `FieldbookStore.orphanMedia()` considera "en uso" solo lo que referencia algun `.fieldnote`
 * y borra el resto pasados diez minutos. Una foto del diario guardada en esa carpeta se
 * borraria sola, en silencio, poco despues de tomarla. Con carpeta aparte, ese barrido no la
 * ve.
 *
 * Un fichero por entrada, como la libreta y por lo mismo: escribir una entrada no puede
 * poner en riesgo las otras cuarenta, y un fichero que se corrompa se salta.
 */
class JournalStore(private val dir: File) {

    companion object {
        const val EXTENSION = ".journal"
        private const val TITULOS = "day-titles.txt"
    }

    /** Fotos y audios DEL DIARIO. Ver el comentario de la clase. */
    val mediaDir: File = File(dir, "media")

    init { dir.mkdirs(); mediaDir.mkdirs() }

    private fun file(id: String) = File(dir, "$id$EXTENSION")

    fun newId(): String = "j" + System.currentTimeMillis() + "-" + (100000..999999).random()

    fun newMediaFile(extension: String): File {
        var f = File(mediaDir, "m" + System.currentTimeMillis() + "-" +
                               (100000..999999).random() + "." + extension.trimStart('.'))
        while (f.exists()) {
            f = File(mediaDir, "m" + System.currentTimeMillis() + "-" +
                               (100000..999999).random() + "." + extension.trimStart('.'))
        }
        return f
    }

    fun media(name: String): File = File(mediaDir, name)

    /** Todas las entradas de una campana. */
    fun list(campaignId: String): List<JournalEntry> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray())
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .filter { it.campaignId == campaignId }
            .sortedBy { it.epochMillis }

    fun load(id: String): JournalEntry? =
        file(id).takeIf { it.exists() }
            ?.let { runCatching { parse(it.readText()) }.getOrNull() }

    /** Escritura atomica: fichero temporal y renombrado, como el resto de la libreta. */
    fun save(e: JournalEntry): Boolean {
        val tmp = File(dir, "${e.id}.tmp")
        return runCatching {
            tmp.writeText(write(e))
            val ok = tmp.renameTo(file(e.id))
            if (!ok) tmp.delete()
            ok
        }.getOrElse { tmp.delete(); false }
    }

    /** Borra la entrada y los medios que solo ella usaba. */
    fun delete(id: String): Boolean {
        val e = load(id)
        val borrado = file(id).delete()
        if (e != null) {
            val enUso = (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray())
                .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
                .flatMap { it.mediaFiles() }.toSet()
            e.mediaFiles().filter { it !in enUso }.forEach { File(mediaDir, it).delete() }
        }
        return borrado
    }

    /** Borra todo lo de una campana. Lo usa el borrado de campanas archivadas. */
    fun deleteCampaign(campaignId: String) {
        list(campaignId).forEach { delete(it.id) }
        escribirTitulos(titles().filterKeys { !it.startsWith("$campaignId|") })
    }

    // ------------------------------ titulos de cada dia ------------------------------

    /** Clave "campana|dia" -> titulo. Un solo fichero: son una linea por dia. */
    private fun titles(): Map<String, String> = runCatching {
        val f = File(dir, TITULOS)
        if (!f.exists()) return emptyMap()
        f.readLines().mapNotNull { l ->
            val p = l.split("|", limit = 3)
            if (p.size < 3) null else "${p[0]}|${p[1]}" to FieldbookFile.unesc(p[2])
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun escribirTitulos(m: Map<String, String>) {
        val tmp = File(dir, "$TITULOS.tmp")
        runCatching {
            tmp.writeText(m.entries.joinToString("\n") { (k, v) ->
                "$k|${FieldbookFile.esc(v)}"
            } + if (m.isEmpty()) "" else "\n")
            if (!tmp.renameTo(File(dir, TITULOS))) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    fun dayTitles(campaignId: String): Map<String, String> =
        titles().filterKeys { it.startsWith("$campaignId|") }
            .mapKeys { (k, _) -> k.substringAfter("|") }

    fun setDayTitle(campaignId: String, dayKey: String, title: String) {
        val m = titles().toMutableMap()
        if (title.isBlank()) m.remove("$campaignId|$dayKey") else m["$campaignId|$dayKey"] = title
        escribirTitulos(m)
    }

    // ------------------------------------ formato ------------------------------------

    internal fun write(e: JournalEntry): String = buildString {
        append("# GlacioTools journal entry\n")
        append("id=${e.id}\n")
        append("campaign=${e.campaignId}\n")
        append("at=${e.epochMillis}\n")
        append("title=${FieldbookFile.esc(e.title)}\n")
        append("text=${FieldbookFile.esc(e.text)}\n")
        e.photos.forEach { append("photo=${FieldbookFile.esc(it)}\n") }
        e.audio.forEach { append("audio=${FieldbookFile.esc(it.file)}|${it.durationMillis ?: ""}\n") }
    }

    internal fun parse(texto: String): JournalEntry? {
        var id: String? = null; var campana: String? = null; var at: Long? = null
        var titulo = ""; var cuerpo = ""
        val fotos = mutableListOf<String>()
        val audios = mutableListOf<JournalAudio>()
        for (l in texto.lineSequence()) {
            if (l.isBlank() || l.startsWith("#")) continue
            val k = l.substringBefore('=', ""); val v = l.substringAfter('=', "")
            when (k) {
                "id" -> id = v.trim()
                "campaign" -> campana = v.trim()
                "at" -> at = v.trim().toLongOrNull()
                "title" -> titulo = FieldbookFile.unesc(v)
                "text" -> cuerpo = FieldbookFile.unesc(v)
                "photo" -> fotos += FieldbookFile.unesc(v)
                "audio" -> {
                    val p = v.split("|", limit = 2)
                    audios += JournalAudio(FieldbookFile.unesc(p[0]),
                                           p.getOrNull(1)?.trim()?.toLongOrNull())
                }
            }
        }
        if (id == null || campana == null || at == null) return null
        return JournalEntry(id, campana, at, titulo, cuerpo, fotos, audios)
    }
}
