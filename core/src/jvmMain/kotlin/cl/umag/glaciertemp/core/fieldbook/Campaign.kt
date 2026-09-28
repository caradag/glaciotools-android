package cl.umag.glaciertemp.core.fieldbook

import java.io.File

/**
 * Una campana de terreno: el periodo al que pertenecen unas anotaciones.
 *
 * Archivar una campana no borra ni mueve nada -- solo la marca con una fecha. Las entradas
 * siguen donde estaban y siguen apuntando a su campana por ID; lo unico que cambia es que la
 * lista principal deja de mostrarlas. Mover ficheros a otra carpeta al archivar habria sido
 * la otra forma de hacerlo, y es la que convierte una operacion reversible en una que puede
 * perder datos a mitad.
 */
data class Campaign(
    val id: String,
    val name: String,
    val startedEpochMillis: Long,
    /** null mientras la campana sigue abierta. */
    val archivedEpochMillis: Long? = null,
) {
    val archived: Boolean get() = archivedEpochMillis != null

    /** Como se llama en pantalla una campana a la que nadie ha puesto nombre todavia. */
    fun displayName(): String = name.trim().ifBlank { "Unnamed campaign" }
}

/**
 * Las campanas en disco: un fichero de texto con una linea por campana.
 *
 * Un solo fichero y no uno por campana, al reves que las entradas: son pocas --unas cuantas
 * al ano-- se leen siempre todas juntas para decidir cual esta abierta, y no llevan nada
 * dentro. Un fichero por campana solo anadiria una carpeta que recorrer.
 *
 * El nombre puede llevar cualquier cosa que el usuario escriba, asi que va escapado igual que
 * en las entradas: un salto de linea pegado de otro sitio partiria el fichero en dos.
 */
class CampaignStore(private val file: File) {

    init { file.parentFile?.mkdirs() }

    private fun newId(): String = "c" + System.currentTimeMillis() + "-" + (100000..999999).random()

    fun list(): List<Campaign> =
        runCatching {
            if (!file.exists()) return emptyList()
            file.readLines().mapNotNull { parseLine(it) }
        }.getOrDefault(emptyList())

    private fun parseLine(line: String): Campaign? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        // id|started|archived|name   -- el nombre va el ULTIMO y escapado, asi que puede
        // contener cualquier cosa sin necesidad de contar separadores.
        val p = t.split("|", limit = 4)
        if (p.size < 4) return null
        val id = p[0].takeIf { it.isNotBlank() } ?: return null
        return Campaign(
            id = id,
            name = FieldbookFile.unesc(p[3]),
            startedEpochMillis = p[1].toLongOrNull() ?: 0L,
            archivedEpochMillis = p[2].toLongOrNull(),
        )
    }

    private fun write(campaigns: List<Campaign>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(campaigns.joinToString("\n", postfix = "\n") { c ->
                "${c.id}|${c.startedEpochMillis}|${c.archivedEpochMillis ?: ""}|" +
                FieldbookFile.esc(c.name)
            })
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    /**
     * La campana abierta, o null si no hay ninguna.
     *
     * Solo puede haber UNA abierta a la vez. No es una restriccion tecnica sino lo que
     * significa: una campana es donde se esta ahora, y estar en dos sitios a la vez no es un
     * caso de uso, es un error de datos que habria que resolver al anotar cada entrada.
     */
    fun active(): Campaign? = list().lastOrNull { !it.archived }

    fun archivedCampaigns(): List<Campaign> =
        list().filter { it.archived }.sortedByDescending { it.archivedEpochMillis ?: 0L }

    fun byId(id: String?): Campaign? = id?.let { i -> list().firstOrNull { it.id == i } }

    /**
     * Abre una campana nueva. Si ya habia una abierta, la devuelve sin tocar nada.
     *
     * Idempotente a proposito: lo llama la libreta cada vez que se crea una entrada, para que
     * empezar a anotar no exija haber creado antes una campana a mano. El nombre se puede
     * poner despues -- y de hecho casi siempre se pone al terminar, que es cuando uno sabe
     * como se llamo aquello.
     */
    fun openOrCurrent(name: String = "", now: Long = System.currentTimeMillis()): Campaign {
        active()?.let { return it }
        val c = Campaign(newId(), name, now, null)
        write(list() + c)
        return c
    }

    fun rename(id: String, name: String) {
        write(list().map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Cierra la campana. Las entradas no se tocan: solo dejan de salir en la lista principal. */
    fun archive(id: String, now: Long = System.currentTimeMillis()) {
        write(list().map {
            if (it.id == id && !it.archived) it.copy(archivedEpochMillis = now) else it
        })
    }

    /** Vuelve a abrirla. Solo si no hay otra abierta: dos a la vez no significan nada. */
    fun unarchive(id: String): Boolean {
        if (active() != null) return false
        write(list().map { if (it.id == id) it.copy(archivedEpochMillis = null) else it })
        return true
    }

    /**
     * Borra la campana de la lista. Las entradas que la nombraban NO se borran.
     *
     * Quedan sin campana, que es lo mismo que estaban antes de que existieran las campanas, y
     * vuelven a aparecer en la lista principal. Borrar con ellas el trabajo de terreno seria
     * convertir un cambio de organizacion en una perdida de datos.
     */
    fun delete(id: String) {
        write(list().filterNot { it.id == id })
    }
}
