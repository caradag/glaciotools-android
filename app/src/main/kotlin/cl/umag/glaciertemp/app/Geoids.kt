package cl.umag.glaciertemp.app

import android.content.Context
import cl.umag.glaciertemp.core.geo.HeightReference
import cl.umag.glaciertemp.core.geo.HeightReport
import cl.umag.glaciertemp.core.geo.ReportedHeight
import cl.umag.glaciertemp.core.geo.geoid.GeoidGrid
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import cl.umag.glaciertemp.core.geo.geoid.GeoidProvider
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Las grillas de geoide del telefono: EGM96 incluida en la app y las teselas descargadas.
 *
 * SE CARGAN BAJO DEMANDA y se quedan en memoria las ultimas usadas: una tesela de 1' son
 * 0,7 MB y un dia de terreno toca una o dos. La EGM96 global son 2 MB y se lee la primera vez
 * que alguien pide una N.
 */
object Geoids : GeoidProvider {
    private var assets: android.content.res.AssetManager? = null
    /** filesDir/geoid/<modelo>/ : donde GeoidStore deja las teselas descargadas. */
    var dir: File? = null
        private set

    private var egm96: GeoidGrid? = null
    private val teselas = object : LinkedHashMap<String, GeoidGrid?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeoidGrid?>?) = size > 6
    }

    fun init(context: Context) {
        if (dir != null) return
        assets = context.applicationContext.assets
        dir = File(context.filesDir, "geoid").apply { mkdirs() }
    }

    /** Las descargas; las pruebas lo sustituyen por uno que no usa la red. */
    var store: GeoidStore? = null
        get() = field ?: dir?.let { GeoidStore(it) }?.also { field = it }

    /** Siempre en la carpeta del almacen de descargas: la que usa `store`, sea cual sea. */
    fun tileFile(model: GeoidModel, south: Int, west: Int): File? =
        store?.let { File(it.dir(model), GeoidModel.tileName(model, south, west)) }

    /** Si hay datos de ese modelo para ese punto en el telefono. */
    fun available(model: GeoidModel, latitude: Double, longitude: Double): Boolean {
        if (model.builtIn) return true
        val (s, w) = GeoidModel.tileCorner(latitude, longitude)
        return tileFile(model, s, w)?.isFile == true
    }

    /** Olvida lo cargado de un modelo (tras descargar o borrar teselas). */
    @Synchronized fun invalidate(model: GeoidModel) {
        teselas.keys.removeAll { it.startsWith(model.id + ":") }
    }

    @Synchronized
    override fun undulation(model: GeoidModel, latitude: Double, longitude: Double): Double? {
        val grid = if (model == GeoidModel.EGM96) {
            egm96 ?: runCatching {
                GeoidGrid.parse(assets!!.open("geoid/egm96-15m.gtg").use { it.readBytes() })
            }.getOrNull()?.also { egm96 = it }
        } else {
            val (s, w) = GeoidModel.tileCorner(latitude, longitude)
            val clave = "${model.id}:$s:$w"
            if (clave !in teselas) {
                teselas[clave] = tileFile(model, s, w)?.takeIf { it.isFile }?.let { f ->
                    runCatching { GeoidGrid.parse(GZIPInputStream(f.inputStream()).use { it.readBytes() }) }
                        .getOrNull()
                }
            }
            teselas[clave]
        }
        return grid?.undulation(latitude, longitude)
    }

    /**
     * El geoide a aplicar en una exportacion segun el ajuste, o null si es elipsoidal o no
     * hay datos del elegido para ese punto (entonces solo va la elipsoidal).
     */
    fun tagAt(latitude: Double, longitude: Double,
              reference: HeightReference = AppSettings.heightReference.value): cl.umag.glaciertemp.core.geo.GeoidTag? {
        val m = (reference as? HeightReference.Orthometric)?.model ?: return null
        val n = undulation(m, latitude, longitude) ?: return null
        return cl.umag.glaciertemp.core.geo.GeoidTag(m.title, n)
    }

    /** La altura segun el ajuste de Settings. */
    fun reportAt(fix: cl.umag.glaciertemp.core.GeoFix): ReportedHeight =
        report(fix.altitudeMetres, fix.latitude, fix.longitude)

    /** La altura segun el ajuste de Settings. */
    fun report(ellipsoidal: Double?, latitude: Double, longitude: Double,
               reference: HeightReference = AppSettings.heightReference.value): ReportedHeight =
        HeightReport.report(ellipsoidal, latitude, longitude, reference, this)
}

/** Los metadatos de una descarga con el geoide que corresponde a su posicion, para mostrarlos. */
fun cl.umag.glaciertemp.core.DownloadMetadata.withGeoid(): cl.umag.glaciertemp.core.DownloadMetadata =
    copy(geoid = position?.let { Geoids.tagAt(it.latitude, it.longitude) })
