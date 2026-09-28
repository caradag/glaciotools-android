package cl.umag.glaciertemp.core.fieldbook

import java.io.File

/**
 * Una lista de nombres reutilizables: las personas, los receptores GNSS.
 *
 * Un nombre por linea, la mas reciente primero. Ese orden no es cosmetico: hace dos cosas de
 * una vez. La primera es que el desplegable ofrece arriba lo que se acaba de usar, que en
 * terreno es casi siempre lo que se va a volver a usar. La segunda es que da gratis el valor
 * por defecto que pide la especificacion --una nota nueva trae el nombre de quien hizo la
 * anterior-- sin guardar un "ultimo usado" aparte, que es un segundo dato que se puede
 * desincronizar del primero.
 *
 * BORRAR UN NOMBRE DE AQUI NO TOCA NINGUN REGISTRO. Las entradas guardan el nombre como
 * texto, no como referencia a esta lista, asi que una medicion hecha con un receptor sigue
 * mostrandolo despues de que el receptor desaparezca del desplegable. Borrar solo quita la
 * opcion de las entradas nuevas, que es exactamente lo que se pidio.
 */
class NameStore(private val file: File) {

    init { file.parentFile?.mkdirs() }

    /**
     * Los nombres, el usado mas recientemente primero.
     *
     * Un fichero que no se puede leer devuelve lista vacia en vez de reventar: la libreta
     * tiene que seguir funcionando aunque se pierda la lista de sugerencias, porque el campo
     * admite escribir un nombre nuevo de todas formas.
     */
    fun list(): List<String> =
        runCatching {
            if (!file.exists()) emptyList()
            else file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())

    private fun write(names: List<String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(names.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    /**
     * Anade un nombre, o lo sube al principio si ya estaba.
     *
     * Se compara ignorando mayusculas y espacios de los extremos para que "camilo" y "Camilo"
     * no acaben siendo dos personas en el desplegable; se conserva la grafia recien escrita,
     * porque es la ultima que el usuario decidio.
     */
    fun remember(name: String) {
        val limpio = name.trim()
        if (limpio.isEmpty()) return
        val resto = list().filterNot { it.equals(limpio, ignoreCase = true) }
        write(listOf(limpio) + resto)
    }

    fun remove(name: String) {
        val limpio = name.trim()
        if (limpio.isEmpty()) return
        write(list().filterNot { it.equals(limpio, ignoreCase = true) })
    }

    fun clear() { write(emptyList()) }

    /** El que se propone al abrir una entrada nueva: el ultimo que se uso. */
    fun mostRecent(): String? = list().firstOrNull()
}
