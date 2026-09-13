package cl.umag.glaciertemp.core.geo

import java.io.File

/** Lo que hace falta para pintar la lista, sin cargar las muestras. */
data class GpsPointSummary(
    val id: String,
    val name: String,
    val createdEpochMillis: Long,
    val samples: Int,
    val lastEpochMillis: Long,
)

/**
 * Los puntos guardados, un fichero por punto en una carpeta de la app.
 *
 * Vive en `core` --Java puro-- y recibe la carpeta desde fuera en vez de preguntarle a
 * Android donde esta. Asi todo el manejo de ficheros se prueba con un directorio temporal,
 * incluido lo que de verdad importa: que seguir promediando anada al final, que un fichero
 * a medias no se lleve por delante a los demas, y que dos puntos no puedan pisarse.
 *
 * No hay base de datos porque no hace falta ninguna: son decenas de puntos, se leen enteros
 * y el formato de texto se puede abrir con cualquier cosa dentro de diez anos. Una base de
 * datos aqui solo anadiria un esquema que migrar.
 */
class GpsPointStore(private val dir: File) {

    init { dir.mkdirs() }

    private fun file(id: String) = File(dir, "$id.gpspoint")

    /**
     * El id sale del reloj y de un sufijo al azar. El reloj solo no basta: crear dos puntos
     * en el mismo milisegundo es improbable pero el precio de que ocurra es que uno pise al
     * otro, y eso es perder una medida de terreno.
     */
    private fun newId(): String =
        "p" + System.currentTimeMillis() + "-" +
        (100000..999999).random()

    fun create(name: String, now: Long = System.currentTimeMillis()): String {
        var id = newId()
        while (file(id).exists()) id = newId()
        file(id).writeText(GpsPointFile.header(GpsPointFile.Header(id, name, now)))
        return id
    }

    /**
     * Anade muestras al final. Es la operacion de "seguir promediando", y es un append de
     * verdad: no se lee ni se reescribe lo que ya habia.
     */
    fun append(id: String, samples: List<GpsSample>) {
        if (samples.isEmpty()) return
        val f = file(id)
        if (!f.exists()) return
        f.appendText(GpsPointFile.appendBlock(samples))
    }

    fun load(id: String): GpsPointFile.Parsed? =
        file(id).takeIf { it.exists() }?.let { GpsPointFile.parse(it.readText()) }

    fun rename(id: String, name: String): Boolean {
        val p = load(id) ?: return false
        // Se reescribe entero, que es lo unico que se puede hacer al cambiar la cabecera.
        // A traves de un temporal: si el proceso muere a mitad, el fichero viejo sigue
        // intacto y lo que se pierde es el cambio de nombre, no las muestras.
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(GpsPointFile.header(p.header.copy(name = name)) +
                      GpsPointFile.appendBlock(p.samples))
        val ok = tmp.renameTo(file(id))
        if (!ok) tmp.delete()
        return ok
    }

    fun delete(id: String): Boolean = file(id).delete()

    /**
     * Todos los puntos, el mas reciente primero.
     *
     * Un fichero ilegible se SALTA en vez de tumbar la lista entera: si algo se corrompio,
     * lo que hace falta es poder llegar a los otros diecinueve puntos.
     */
    fun list(): List<GpsPointSummary> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".gpspoint") } ?: emptyArray())
            .mapNotNull { f ->
                val p = runCatching { GpsPointFile.parse(f.readText()) }.getOrNull() ?: return@mapNotNull null
                GpsPointSummary(
                    id = p.header.id,
                    name = p.header.name,
                    createdEpochMillis = p.header.createdEpochMillis,
                    samples = p.samples.size,
                    lastEpochMillis = p.samples.maxOfOrNull { it.epochMillis }
                        ?: p.header.createdEpochMillis,
                )
            }
            .sortedByDescending { it.lastEpochMillis }
}
