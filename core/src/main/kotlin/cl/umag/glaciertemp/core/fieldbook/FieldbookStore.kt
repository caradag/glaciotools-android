package cl.umag.glaciertemp.core.fieldbook

import java.io.File

/**
 * Las entradas de la libreta en disco: un fichero por entrada, mas una carpeta de medios.
 *
 * Recibe la carpeta desde fuera en vez de preguntarle a Android donde esta, igual que
 * [cl.umag.glaciertemp.core.geo.GpsPointStore]. Asi todo esto se prueba con un directorio
 * temporal, incluido lo que de verdad importa: que una escritura interrumpida no se lleve por
 * delante lo anterior, que borrar una entrada borre TAMBIEN sus fotos, y que dos entradas
 * creadas en el mismo milisegundo no puedan pisarse.
 */
class FieldbookStore(private val dir: File) {

    companion object {
        const val EXTENSION = ".fieldnote"
    }

    /** Fotos y audios. Aparte de las entradas para que listar la libreta no los recorra. */
    val mediaDir: File = File(dir, "media")

    init { dir.mkdirs(); mediaDir.mkdirs() }

    private fun file(id: String) = File(dir, "$id$EXTENSION")

    /**
     * El id sale del reloj y de un sufijo al azar, como el de los puntos de GPS. El reloj
     * solo no basta: dos entradas en el mismo milisegundo es improbable, pero el precio de
     * que ocurra es que una pise a la otra, y eso es perder trabajo de terreno.
     */
    fun newId(): String = "e" + System.currentTimeMillis() + "-" + (100000..999999).random()

    fun create(type: EntryType, now: Long = System.currentTimeMillis(), person: String = ""):
        FieldEntry {
        var id = newId()
        while (file(id).exists()) id = newId()
        val e = FieldEntry(id = id, type = type, createdEpochMillis = now,
                           updatedEpochMillis = now, person = person)
        save(e)
        return e
    }

    /**
     * Escribe la entrada entera, a traves de un temporal.
     *
     * El rename es atomico dentro del mismo sistema de ficheros: si el proceso muere a mitad
     * de la escritura, lo que queda en disco es la version anterior completa y no un fichero
     * truncado. Escribir directamente sobre el destino cambiaria "se pierde el ultimo cambio"
     * por "se pierde la entrada".
     */
    fun save(entry: FieldEntry): Boolean {
        val tmp = File(dir, "${entry.id}.tmp")
        return runCatching {
            tmp.writeText(FieldbookFile.write(entry))
            val ok = tmp.renameTo(file(entry.id))
            if (!ok) tmp.delete()
            ok
        }.getOrElse { tmp.delete(); false }
    }

    fun load(id: String): FieldEntry? =
        file(id).takeIf { it.exists() }
            ?.let { runCatching { FieldbookFile.parse(it.readText()) }.getOrNull() }

    /**
     * Borra la entrada y los medios que solo ella usaba.
     *
     * Se comprueba contra el resto de la libreta antes de borrar un fichero de medios: una
     * foto podria estar referenciada por dos entradas si alguna vez se copia una, y borrar la
     * copia no puede dejar a la original sin su foto.
     */
    fun delete(id: String): Boolean {
        val e = load(id)
        val borrado = file(id).delete()
        if (e != null) {
            val enUso = list().filter { it.id != id }.flatMap { it.mediaFiles() }.toSet()
            e.mediaFiles().filter { it !in enUso }.forEach { File(mediaDir, it).delete() }
        }
        return borrado
    }

    /**
     * Todas las entradas, la mas reciente primero.
     *
     * Se cargan ENTERAS y no como resumen, al reves que los puntos de GPS: un punto lleva
     * miles de muestras y leerlas para pintar una lista seria absurdo, mientras que una
     * entrada de libreta son unos pocos kilobytes incluso con cincuenta mediciones, y la
     * lista necesita ya casi todo lo que trae --quien, cuando, donde, cuantas mediciones--.
     * Un resumen aqui solo anadiria un segundo modelo que mantener de acuerdo con el primero.
     *
     * Un fichero ilegible se SALTA en vez de tumbar la lista entera: si algo se corrompio, lo
     * que hace falta es poder llegar a las otras cuarenta entradas.
     */
    fun list(): List<FieldEntry> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray())
            .mapNotNull { f -> runCatching { FieldbookFile.parse(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedEpochMillis }

    /** Un nombre libre para un fichero de medios nuevo. La extension la pone quien llama. */
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

    /**
     * Ficheros de medios que ya no nombra ninguna entrada.
     *
     * Se acumulan cuando se hace una foto y se descarta la entrada sin guardarla, o cuando se
     * quita una foto de una entrada. Limpiarlos es un barrido explicito y no un efecto de
     * cualquier operacion: un barrido que corre solo, el dia que la lista falle al leerse,
     * borraria las fotos de toda la libreta.
     */
    fun orphanMedia(): List<File> {
        val enUso = list().flatMap { it.mediaFiles() }.toSet()
        return (mediaDir.listFiles { f -> f.isFile } ?: emptyArray())
            .filter { it.name !in enUso }
    }

    /**
     * Borra los huerfanos que lleven un rato parados, y devuelve cuantos.
     *
     * LA EDAD MINIMA NO ES COSMETICA. Un fichero de foto se crea ANTES de lanzar la camara y
     * no lo nombra ninguna entrada hasta que la camara vuelve; un barrido sin esa guarda
     * borraria justo el fichero en el que la camara esta escribiendo. Diez minutos son mas
     * que cualquier captura y menos que cualquier sesion.
     */
    fun purgeOrphanMedia(minAgeMillis: Long = 10 * 60_000L,
                         now: Long = System.currentTimeMillis()): Int =
        orphanMedia().filter { now - it.lastModified() > minAgeMillis }
            .count { it.delete() }
}
