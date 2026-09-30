package cl.umag.glaciertemp.core.geo.geoid

import kotlin.math.floor

/**
 * Los modelos de geoide que ofrece la app.
 *
 * ITRF2008, ITRF2014 e ITRF2020 NO estan aqui porque no son geoides: son marcos de referencia
 * (realizaciones de coordenadas), y la altura elipsoidal de un punto difiere entre ellos en
 * menos de un centimetro. La diferencia entre altura geoidal y elipsoidal la da un modelo de
 * geoide, que es lo que se elige aqui.
 *
 * Los tres van en el sistema de marea "tide-free", el de WGS84/ITRF; pasar a "mean-tide"
 * cambiaria N en unos centimetros segun la latitud.
 */
enum class GeoidModel(
    val id: String,
    val title: String,
    /** Paso de la grilla que usa la app, en minutos de arco. */
    val resolutionArcMin: Int,
    val builtIn: Boolean,
    val description: String,
) {
    EGM96("egm96", "EGM96", 15, true,
          "NGA 1996, degree 360. Usually what a GNSS receiver uses for its 'sea level' " +
          "(NMEA) height. Built into the app (15′ grid, ≤0.17 m interpolation error)."),
    EGM2008("egm2008", "EGM2008", 1, false,
            "NGA 2008, degree 2190. The current standard. 1′ grid downloaded by area " +
            "(10°×10° tiles, ≈0.4 MB each; ≤3 mm interpolation error)."),
    XGM2019E("xgm2019e", "XGM2019e", 1, false,
             "GFZ/TUM 2019, combines GOCE satellite gravity; better than EGM2008 in the Andes " +
             "in published comparisons. 1′ grid downloaded by area.");

    companion object {
        /**
         * Los que la app OFRECE. XGM2019e queda fuera mientras no haya teselas publicadas: el
         * servicio de ICGEM que las calcularia no acepta trabajos ("high load", 2026-09-30), y
         * ofrecer un geoide que no se puede descargar solo produciria un error que parece de red.
         */
        val offered: List<GeoidModel> get() = listOf(EGM96, EGM2008)

        fun fromId(id: String?): GeoidModel? = offered.firstOrNull { it.id == id }

        /** La tesela de 10x10 grados que contiene el punto: su esquina SO. */
        fun tileCorner(latitude: Double, longitude: Double): Pair<Int, Int> {
            val lon = ((longitude % 360) + 540) % 360 - 180
            val s = (floor(latitude / 10) * 10).toInt().coerceIn(-90, 80)
            val w = (floor(lon / 10) * 10).toInt().coerceIn(-180, 170)
            return s to w
        }

        /** Nombre del fichero de una tesela, el mismo que escribe tools/geoid/make_tiles.py. */
        fun tileName(model: GeoidModel, south: Int, west: Int): String {
            val ns = if (south < 0) "S" else "N"
            val ew = if (west < 0) "W" else "E"
            val sur = kotlin.math.abs(south).toString().padStart(2, '0')
            val oeste = kotlin.math.abs(west).toString().padStart(3, '0')
            return "${model.id}-${model.resolutionArcMin}m-$ns$sur$ew$oeste.gtg.gz"
        }
    }
}

/** De donde sale N: la app lo implementa con la grilla incluida y las teselas descargadas. */
fun interface GeoidProvider {
    /** N en metros, o null si ese modelo no tiene datos para ese punto en este telefono. */
    fun undulation(model: GeoidModel, latitude: Double, longitude: Double): Double?
}
