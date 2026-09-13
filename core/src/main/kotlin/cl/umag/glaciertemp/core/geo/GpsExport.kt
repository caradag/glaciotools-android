package cl.umag.glaciertemp.core.geo

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Los dos formatos en que un punto sale de la app, en sus dos alcances.
 *
 * CSV para meterlo en una hoja de calculo o en un script; GPX para abrirlo en un GIS o
 * cargarlo en un navegador de mano. Y de cada punto, o bien la estimacion sola --que es lo
 * que uno lleva al mapa-- o bien las muestras enteras, que es lo que permite rehacer el
 * calculo, ver si el receptor estaba divagando, o promediar de otra manera.
 */
object GpsExport {

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    private fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))
    private fun f(v: Double, d: Int) = "%.${d}f".format(java.util.Locale.ROOT, v)

    /** Todas las muestras, con su proyeccion ya hecha para no tener que repetirla fuera. */
    fun csvSamples(name: String, stats: GpsPointStats, samples: List<GpsSample>): String =
        buildString {
            append("# GlacioTools GPS point: ").append(name).append('\n')
            append("# samples: ").append(samples.size)
                .append("  sessions: ").append(stats.sessions)
                .append("  zone: ").append(stats.zone).append(stats.band).append('\n')
            append("# projection: UTM WGS84, zone fixed by the first sample\n")
            append("index,session,time_utc,latitude,longitude,altitude_m," +
                   "accuracy_m,vertical_accuracy_m,easting_m,northing_m\n")
            val a = GpsAverager()
            samples.forEach { a.add(it) }
            val proyectadas = a.projected()
            val tramos = samples.map { it.sessionStartMillis }.distinct().sorted()
            samples.forEachIndexed { i, s ->
                val (e, n) = proyectadas[i]
                append(i + 1).append(',')
                // El tramo sale en el fichero porque sin el no se puede rehacer el calculo:
                // quien lo lea fuera necesita saber que muestras van juntas.
                append(tramos.indexOf(s.sessionStartMillis) + 1).append(',')
                append(iso(s.epochMillis)).append(',')
                append(f(s.latitude, 8)).append(',').append(f(s.longitude, 8)).append(',')
                append(s.altitudeMetres?.let { f(it, 3) } ?: "").append(',')
                append(s.accuracyMetres?.let { f(it, 2) } ?: "").append(',')
                append(s.verticalAccuracyMetres?.let { f(it, 2) } ?: "").append(',')
                append(f(e, 3)).append(',').append(f(n, 3)).append('\n')
            }
        }

    /**
     * Solo la estimacion, en una fila con cabecera.
     *
     * Van las tres cifras de cada eje --mediana, dispersion e incertidumbre-- y no solo la
     * mediana: un punto sin una medida de su calidad no se puede combinar con otro ni
     * descartar cuando no da la talla, y el que exporta hoy no es siempre el que usa el
     * fichero dentro de dos anos.
     */
    fun csvAverage(name: String, stats: GpsPointStats): String =
        csvAverageHeader() + csvAverageRow(name, stats)

    private fun csvAverageHeader(): String =
        "name,samples,sessions,effective_n,rejected,duration_s,zone,band,hemisphere," +
        "easting_m,northing_m,latitude,longitude," +
        "easting_sd_m,northing_sd_m,horizontal_sd_m," +
        "easting_se_m,northing_se_m,horizontal_se_m," +
        "altitude_m,altitude_sd_m,altitude_se_m," +
        "easting_median_m,northing_median_m,first_utc,last_utc\n"

    private fun csvAverageRow(name: String, stats: GpsPointStats): String = buildString {
        append(csvQuote(name)).append(',')
        append(stats.samples).append(',').append(stats.sessions).append(',')
        // El n efectivo va en el fichero porque es lo que explica la incertidumbre: sin el,
        // alguien vera "3000 muestras" al lado de "± 0,4 m" y no entendera la relacion.
        append(f(stats.easting.nEffective, 1)).append(',')
        append(stats.rejected).append(',')
        append(stats.durationSeconds).append(',')
        append(stats.zone).append(',').append(stats.band).append(',')
        append(if (stats.north) 'N' else 'S').append(',')
        append(f(stats.easting.estimate, 3)).append(',')
        append(f(stats.northing.estimate, 3)).append(',')
        append(f(stats.estimateLatitude, 8)).append(',')
        append(f(stats.estimateLongitude, 8)).append(',')
        append(f(stats.easting.sd, 3)).append(',')
        append(f(stats.northing.sd, 3)).append(',')
        append(f(stats.horizontalSd, 3)).append(',')
        append(f(stats.easting.standardError, 3)).append(',')
        append(f(stats.northing.standardError, 3)).append(',')
        append(f(stats.horizontalStandardError, 3)).append(',')
        append(stats.altitude?.let { f(it.median, 3) } ?: "").append(',')
        append(stats.altitude?.let { f(it.sd, 3) } ?: "").append(',')
        append(stats.altitude?.let { f(it.standardError, 3) } ?: "").append(',')
        // La mediana sin ponderar, como contraste: si se aparta mucho de la estimacion, la
        // ponderacion esta haciendo algo fuerte y conviene mirar las muestras.
        append(f(stats.easting.median, 3)).append(',')
        append(f(stats.northing.median, 3)).append(',')
        append(iso(stats.firstEpochMillis)).append(',')
        append(iso(stats.lastEpochMillis)).append('\n')
    }

    /**
     * Varios puntos en un solo fichero, una fila por punto.
     *
     * Solo las soluciones finales: un fichero con las muestras de veinte puntos serian
     * decenas de miles de filas en las que la informacion que se busca --donde esta cada
     * estaca-- queda enterrada. Para eso esta la exportacion de un punto suelto.
     */
    fun csvAverages(points: List<Pair<String, GpsPointStats>>): String = buildString {
        append(csvAverageHeader())
        points.forEach { (name, st) -> append(csvAverageRow(name, st)) }
    }

    /** Lo mismo en GPX: un waypoint por punto, que es como se lleva un conjunto al mapa. */
    fun gpxAverages(points: List<Pair<String, GpsPointStats>>): String = buildString {
        append(gpxHeader())
        points.forEach { (name, st) -> append(gpxWaypoint(name, st)) }
        append("</gpx>\n")
    }

    /** Un solo waypoint: la estimacion. Es lo que se lleva al mapa. */
    fun gpxAverage(name: String, stats: GpsPointStats): String =
        gpxHeader() + gpxWaypoint(name, stats) + "</gpx>\n"

    private fun gpxWaypoint(name: String, stats: GpsPointStats): String = buildString {
        append("  <wpt lat=\"").append(f(stats.estimateLatitude, 8))
            .append("\" lon=\"").append(f(stats.estimateLongitude, 8)).append("\">\n")
        stats.altitude?.let { append("    <ele>").append(f(it.estimate, 3)).append("</ele>\n") }
        append("    <time>").append(iso(stats.lastEpochMillis)).append("</time>\n")
        append("    <name>").append(xml(name)).append("</name>\n")
        // La calidad va en la descripcion porque GPX no tiene sitio para ella. Un waypoint
        // sin ella no se distingue de uno marcado con el dedo sobre el mapa.
        append("    <desc>").append(xml(
            "Weighted mean of ${stats.samples} fixes in ${stats.sessions} session(s) over " +
            "${stats.durationSeconds} s; effective independent samples " +
            "${f(stats.easting.nEffective, 1)}. " +
            "Horizontal scatter (sd) ${f(stats.horizontalSd, 2)} m, " +
            "uncertainty of the estimate ${f(stats.horizontalStandardError, 2)} m. " +
            "UTM ${stats.estimateUtm.format()}."))
            .append("</desc>\n")
        append("    <src>GlacioTools averaged GNSS</src>\n")
        append("  </wpt>\n")
    }

    /**
     * Las muestras enteras, como traza.
     *
     * Traza y no un waypoint por muestra a proposito: mil waypoints apilados en el mismo
     * sitio hacen ilegible cualquier mapa, mientras que una traza se dibuja como lo que es
     * --una nube con su evolucion en el tiempo-- y todo GIS sabe leerla.
     */
    fun gpxSamples(name: String, samples: List<GpsSample>): String = buildString {
        append(gpxHeader())
        append("  <trk>\n    <name>").append(xml(name)).append(" (all fixes)</name>\n")
        append("    <trkseg>\n")
        samples.forEach { s ->
            append("      <trkpt lat=\"").append(f(s.latitude, 8))
                .append("\" lon=\"").append(f(s.longitude, 8)).append("\">\n")
            s.altitudeMetres?.let {
                append("        <ele>").append(f(it, 3)).append("</ele>\n")
            }
            append("        <time>").append(iso(s.epochMillis)).append("</time>\n")
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun gpxHeader(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GlacioTools\" " +
        "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
        "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
        "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"

    /** Un nombre escrito por una persona puede traer cualquier cosa; el XML no perdona. */
    internal fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    internal fun csvQuote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' })
            "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\""
        else s
}
