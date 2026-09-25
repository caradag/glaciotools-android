package cl.umag.glaciertemp.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cl.umag.glaciertemp.core.gnss.Constellation
import cl.umag.glaciertemp.core.gnss.Tle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * El almanaque guardado en el telefono, y su descarga.
 *
 * QUE SE DESCARGA Y POR QUE ASI. Un fichero TLE por constelacion desde CelesTrak, 24 KB en
 * total. Cuatro peticiones en vez de una a proposito: el grupo mezclado trae tambien SBAS y
 * sistemas regionales, y ahi "GSAT-8" es GAGAN --un SBAS indio-- mientras que "GSAT0101" si
 * es Galileo. Descargando un fichero por constelacion, cada uno se etiqueta entero y no hace
 * falta ninguna regla sobre los nombres que acabaria fallando con el proximo lanzamiento.
 *
 * SE ESCRIBE SOLO SI LAS CUATRO LLEGAN. Media descarga es peor que ninguna: dejaria un
 * almanaque con GPS nuevo y Galileo viejo, y la grafica mezclaria epocas sin decirlo. Se
 * bajan las cuatro a memoria y solo entonces se tocan los ficheros.
 *
 * NUNCA SE BORRA LO QUE HAY. Si la descarga falla a medias, lo guardado sigue en su sitio: en
 * terreno un almanaque de dos meses vale, y quedarse sin ninguno no.
 */
class AlmanacStore(private val dir: File) {

    data class Loaded(val tles: List<Tle>, val downloadedAtMillis: Long?)

    private val marca get() = File(dir, "almanac-epoch.txt")
    private fun fichero(c: Constellation) = File(dir, "tle-${c.name.lowercase()}.txt")

    /** Cuando se bajo lo que hay, o null si no hay nada. */
    fun downloadedAt(): Long? = runCatching {
        if (!marca.isFile) return null
        val t = marca.readText().trim().toLongOrNull() ?: return null
        if (Constellation.entries.all { fichero(it).isFile }) t else null
    }.getOrNull()

    /** Lo guardado, ya analizado. Lista vacia si no hay nada. */
    fun load(): Loaded {
        val t = downloadedAt() ?: return Loaded(emptyList(), null)
        val todos = Constellation.entries.flatMap { c ->
            runCatching { Tle.parse(fichero(c).readText(), c) }.getOrDefault(emptyList())
        }
        return Loaded(todos, t)
    }

    /** Si el telefono dice tener una red utilizable ahora mismo. */
    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * Baja las cuatro constelaciones. Devuelve el instante de la descarga, o null si fallo.
     *
     * Bloqueante: se llama desde un hilo de entrada/salida.
     */
    fun download(): Long? {
        val bajados = mutableMapOf<Constellation, String>()
        for (c in Constellation.entries) {
            val txt = traer(URL_BASE + grupo(c)) ?: return null
            // Una respuesta que no parece un TLE --una pagina de error, un portal cautivo de
            // hotel-- no debe sobrescribir un almanaque bueno.
            if (Tle.parse(txt, c).size < 4) return null
            bajados[c] = txt
        }
        val ahora = System.currentTimeMillis()
        return runCatching {
            dir.mkdirs()
            bajados.forEach { (c, txt) -> fichero(c).writeText(txt) }
            marca.writeText(ahora.toString())
            ahora
        }.getOrNull()
    }

    private fun traer(url: String): String? = runCatching {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        try {
            if (con.responseCode != 200) return null
            con.inputStream.bufferedReader().readText()
        } finally {
            con.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val URL_BASE = "https://celestrak.org/NORAD/elements/gp.php?FORMAT=tle&GROUP="

        private fun grupo(c: Constellation) = when (c) {
            Constellation.GPS -> "gps-ops"
            Constellation.GLONASS -> "glo-ops"
            Constellation.GALILEO -> "galileo"
            Constellation.BEIDOU -> "beidou"
        }
    }
}
