package cl.umag.glaciertemp.core.fieldbook

import java.text.Normalizer

/**
 * Busqueda de texto en TODO lo guardado: notas y diario, de todas las campanas.
 *
 * POR QUE BUSCA TAMBIEN EN LO ARCHIVADO. Es justo donde esta lo que uno no recuerda. Al
 * volver a un glaciar despues de dos anos, la pregunta es "como se llamaba la baliza del
 * sector norte" o "que dijimos del puente de nieve", y la respuesta esta en una campana
 * cerrada hace mucho. Una busqueda limitada a la campana en curso solo encuentra lo que
 * todavia se tiene en la cabeza.
 *
 * SE IGNORAN MAYUSCULAS Y TILDES. En terreno se escribe con el telefono en la mano, con
 * guantes y con prisa, y "balizas", "Balizas" y "balízas" acaban todas en la libreta.
 * Exigir la tilde exacta convierte la busqueda en una loteria.
 */
object FieldbookSearch {

    enum class Kind { NOTE, JOURNAL }

    /**
     * Un resultado.
     *
     * Lleva el fragmento donde cayo la busqueda y el nombre del campo. Una lista de titulos
     * no basta: al buscar "grieta" y encontrar quince notas, lo que decide cual abrir es ver
     * la frase, no el titulo -- que muchas veces es solo el nombre de la baliza.
     */
    data class Hit(
        val kind: Kind,
        val id: String,
        val campaignId: String?,
        val campaignName: String,
        val epochMillis: Long,
        val title: String,
        /** Donde cayo: "Text", "Person", "Species"... */
        val field: String,
        /** El trozo de texto alrededor de lo encontrado. */
        val snippet: String,
    )

    /** Minusculas y sin tildes, para comparar. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    /**
     * Busca [query] en las notas y en el diario.
     *
     * Devuelve como mucho un resultado POR ENTRADA, el del primer campo que coincide. Una
     * nota que repite la palabra ocho veces es un resultado, no ocho: lo que se busca es la
     * nota.
     */
    fun search(
        query: String,
        entries: List<FieldEntry>,
        journal: List<JournalEntry> = emptyList(),
        campaignName: (String?) -> String = { "" },
        limit: Int = 300,
    ): List<Hit> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()

        val salida = ArrayList<Hit>()

        entries.forEach { e ->
            val campos = ArrayList<Pair<String, String>>()
            when (e.type) {
                EntryType.NOTE -> {
                    campos += "Title" to e.title
                    e.items.filter { it.kind == NoteItemKind.TEXT }
                        .forEach { campos += "Text" to it.text }
                }
                EntryType.STAKE -> {
                    campos += "Stake" to e.stakeName
                    e.measurements.forEach { campos += "Person" to it.person }
                }
                EntryType.GNSS -> {
                    campos += "Point" to e.pointName
                    e.gnss?.receiver?.let { campos += "Receiver" to it }
                }
                EntryType.DENDRO -> {
                    campos += "Sample" to e.sampleLabel
                    campos += "Species" to e.species
                    campos += "Notes" to e.notes
                }
            }
            campos += "Person" to e.person
            val cae = campos.firstOrNull { (_, v) -> v.isNotBlank() && fold(v).contains(q) }
            if (cae != null) salida += Hit(
                kind = Kind.NOTE, id = e.id, campaignId = e.campaignId,
                campaignName = campaignName(e.campaignId),
                epochMillis = e.createdEpochMillis, title = e.title(),
                field = cae.first, snippet = snippet(cae.second, q))
        }

        journal.forEach { j ->
            val campos = listOf("Title" to j.title, "Text" to j.text)
            val cae = campos.firstOrNull { (_, v) -> v.isNotBlank() && fold(v).contains(q) }
            if (cae != null) salida += Hit(
                kind = Kind.JOURNAL, id = j.id, campaignId = j.campaignId,
                campaignName = campaignName(j.campaignId),
                epochMillis = j.epochMillis,
                title = j.title.ifBlank { "(untitled entry)" },
                field = cae.first, snippet = snippet(cae.second, q))
        }

        // Lo mas reciente arriba, como en la lista de la libreta.
        return salida.sortedByDescending { it.epochMillis }.take(limit)
    }

    /**
     * El fragmento alrededor de lo encontrado.
     *
     * Se corta por ESPACIOS y no por posicion exacta: un fragmento que empieza a mitad de
     * palabra se lee peor que uno un poco mas largo. Las marcas "..." dicen que hay texto
     * antes o despues, para que nadie lea el fragmento como si fuera la nota entera.
     */
    fun snippet(texto: String, foldedQuery: String, contexto: Int = 40): String {
        val plano = texto.replace(Regex("\\s+"), " ").trim()
        val i = fold(plano).indexOf(foldedQuery)
        if (i < 0) return plano.take(contexto * 3)

        var desde = (i - contexto).coerceAtLeast(0)
        var hasta = (i + foldedQuery.length + contexto * 2).coerceAtMost(plano.length)
        if (desde > 0) {
            val esp = plano.indexOf(' ', desde)
            if (esp in desde until i) desde = esp + 1
        }
        if (hasta < plano.length) {
            val esp = plano.lastIndexOf(' ', hasta)
            if (esp > i + foldedQuery.length) hasta = esp
        }
        return buildString {
            if (desde > 0) append("…")
            append(plano.substring(desde, hasta))
            if (hasta < plano.length) append("…")
        }
    }
}
