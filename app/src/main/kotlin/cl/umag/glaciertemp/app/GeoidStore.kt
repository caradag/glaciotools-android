package cl.umag.glaciertemp.app

import cl.umag.glaciertemp.core.geo.geoid.GeoidGrid
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Las teselas de geoide descargadas: donde estan y como se bajan.
 *
 * COMO EL ALMANAQUE: nunca se borra lo que hay para bajar algo nuevo, y lo que llega se
 * comprueba antes de escribirse. Cada tesela trae su SHA-256 en el manifiesto de la release,
 * y una respuesta que no cuadra --una pagina de error, un portal cautivo de hotel, una
 * descarga cortada-- no llega nunca a sustituir a una buena. Se escribe en un temporal y se
 * renombra: una tesela a medias no existe.
 *
 * POR ZONAS. La grilla de 1' del mundo son 470 MB; lo que hace falta en terreno son una o
 * dos teselas de 10x10 grados (~0,4 MB cada una). "Esta zona" es la tesela del punto y sus
 * ocho vecinas, para no quedarse sin datos al cruzar un borde durante la campana.
 */
class GeoidStore(
    private val root: File,
    private val fetch: (String) -> ByteArray? = ::httpGet,
    private val baseUrl: String = BASE_URL,
) {
    data class TileInfo(val name: String, val south: Int, val west: Int, val gzBytes: Long, val sha256: String)

    data class Outcome(val downloaded: Int, val alreadyThere: Int, val failed: Int, val bytes: Long)

    private val manifiestos = HashMap<GeoidModel, Map<String, TileInfo>>()

    fun dir(model: GeoidModel) = File(root, model.id)

    /** El manifiesto de la release: que teselas existen, cuanto pesan y su huella. */
    @Synchronized
    fun manifest(model: GeoidModel): Map<String, TileInfo>? {
        manifiestos[model]?.let { return it }
        val txt = fetch(baseUrl + "${model.id}-${model.resolutionArcMin}m-manifest.json")
            ?.decodeToString() ?: return null
        val m = runCatching {
            val arr = JSONObject(txt).getJSONArray("tiles")
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                TileInfo(t.getString("name"), t.getInt("south"), t.getInt("west"),
                         t.getLong("gz_bytes"), t.getString("sha256"))
            }.associateBy { it.name }
        }.getOrNull() ?: return null
        manifiestos[model] = m
        return m
    }

    /** Las teselas de "esta zona": la del punto y sus ocho vecinas que existan. */
    fun areaTiles(model: GeoidModel, latitude: Double, longitude: Double): List<Pair<Int, Int>> {
        val (s, w) = GeoidModel.tileCorner(latitude, longitude)
        return buildList {
            for (ds in -10..10 step 10) for (dw in -10..10 step 10) {
                val sur = s + ds
                if (sur < -90 || sur > 80) continue
                val oeste = ((w + dw + 180 + 360) % 360) - 180
                add(sur to oeste)
            }
        }.distinct()
    }

    /** Cuanto hay que bajar para esta zona, en bytes, o null sin manifiesto. */
    fun pendingBytes(model: GeoidModel, latitude: Double, longitude: Double): Long? {
        val m = manifest(model) ?: return null
        return areaTiles(model, latitude, longitude)
            .map { (s, w) -> GeoidModel.tileName(model, s, w) }
            .filter { !File(dir(model), it).isFile }
            .sumOf { m[it]?.gzBytes ?: 0L }
    }

    /** Baja lo que falte de la zona. Bloqueante: se llama desde un hilo de E/S. */
    fun downloadArea(model: GeoidModel, latitude: Double, longitude: Double,
                     progress: (Int, Int) -> Unit = { _, _ -> }): Outcome? {
        val m = manifest(model) ?: return null
        val d = dir(model).apply { mkdirs() }
        val nombres = areaTiles(model, latitude, longitude).map { (s, w) -> GeoidModel.tileName(model, s, w) }
        var bajadas = 0; var yaEstaban = 0; var fallos = 0; var bytes = 0L
        nombres.forEachIndexed { i, nombre ->
            progress(i, nombres.size)
            val info = m[nombre] ?: return@forEachIndexed
            val destino = File(d, nombre)
            if (destino.isFile) { yaEstaban++; return@forEachIndexed }
            val datos = fetch(baseUrl + nombre)
            if (datos == null || sha256(datos) != info.sha256 || !esTesela(datos, model)) {
                fallos++; return@forEachIndexed
            }
            val tmp = File(d, "$nombre.tmp")
            val ok = runCatching { tmp.writeBytes(datos); tmp.renameTo(destino) }.getOrDefault(false)
            if (ok) { bajadas++; bytes += datos.size } else { tmp.delete(); fallos++ }
        }
        progress(nombres.size, nombres.size)
        Geoids.invalidate(model)
        return Outcome(bajadas, yaEstaban, fallos, bytes)
    }

    /** Lo descargado de un modelo: teselas y bytes. */
    fun onDisk(model: GeoidModel): Pair<Int, Long> {
        val fs = dir(model).listFiles { f -> f.isFile && f.name.endsWith(".gtg.gz") }.orEmpty()
        return fs.size to fs.sumOf { it.length() }
    }

    fun delete(model: GeoidModel) {
        dir(model).listFiles().orEmpty().forEach { it.delete() }
        Geoids.invalidate(model)
    }

    /** Ademas del SHA-256: que se lea como tesela de ESE modelo. */
    private fun esTesela(gz: ByteArray, model: GeoidModel): Boolean = runCatching {
        GeoidGrid.parse(GZIPInputStream(gz.inputStream()).use { it.readBytes() }).model == model.id
    }.getOrDefault(false)

    companion object {
        /**
         * Donde se publican las teselas: una release por modelo en el repositorio de datos.
         * La generacion esta en tools/geoid/make_tiles.py.
         */
        const val BASE_URL = "https://github.com/caradag/glaciotools-data/releases/download/geoid-v1/"

        fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun httpGet(url: String): ByteArray? = runCatching {
            var u = URL(url)
            repeat(5) {                                   // GitHub redirige a su CDN
                val c = u.openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.instanceFollowRedirects = false
                when (c.responseCode) {
                    in 300..399 -> { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
                    200 -> return@runCatching c.inputStream.use { it.readBytes() }
                    else -> { c.disconnect(); return@runCatching null }
                }
            }
            null
        }.getOrNull()
    }
}
