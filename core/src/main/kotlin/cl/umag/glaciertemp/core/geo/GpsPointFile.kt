package cl.umag.glaciertemp.core.geo

/**
 * Como se guarda un punto en disco.
 *
 * Cabecera de `clave=valor`, una linea en blanco con `---`, y luego las muestras en CSV, una
 * por linea. Lo importante del formato es que se pueda APPEND: seguir promediando un punto
 * en otra visita anade lineas al final y no reescribe nada, asi que una interrupcion --que
 * se acabe la bateria a mitad de escritura-- cuesta como mucho la ultima muestra y no el
 * punto entero.
 *
 * Es texto plano y no JSON porque `core` no tiene dependencias a proposito --es lo que
 * permite probarlo entero en el escritorio-- y porque un fichero que se puede abrir con
 * cualquier cosa es una propiedad util para un dato de terreno que alguien querra mirar
 * dentro de diez anos.
 */
object GpsPointFile {

    const val SEPARATOR = "---"
    const val COLUMNS = "epochMillis,latitude,longitude,altitudeMetres,accuracyMetres"

    /** Lo que identifica a un punto, sin sus muestras. */
    data class Header(
        val id: String,
        val name: String,
        val createdEpochMillis: Long,
    )

    fun header(h: Header): String = buildString {
        append("# GlacioTools GPS point\n")
        append("id=").append(h.id).append('\n')
        // El nombre lo escribe una persona y puede traer saltos de linea pegados de otro
        // sitio. Se aplanan aqui: uno solo partiria el fichero en dos.
        append("name=").append(h.name.replace('\n', ' ').replace('\r', ' ')).append('\n')
        append("created=").append(h.createdEpochMillis).append('\n')
        append(SEPARATOR).append('\n')
        append(COLUMNS).append('\n')
    }

    fun sampleLine(s: GpsSample): String =
        "${s.epochMillis},${fmt(s.latitude, 8)},${fmt(s.longitude, 8)}," +
        "${s.altitudeMetres?.let { fmt(it, 3) } ?: ""}," +
        "${s.accuracyMetres?.let { fmt(it, 2) } ?: ""}\n"

    private fun fmt(v: Double, d: Int) = "%.${d}f".format(java.util.Locale.ROOT, v)

    data class Parsed(val header: Header, val samples: List<GpsSample>, val skipped: Int)

    /**
     * Lee un fichero de punto. Las lineas que no se entienden se CUENTAN y se saltan.
     *
     * No se aborta ante una linea rota: la ultima puede estar a medias porque el proceso
     * murio escribiendola, y tirar cientos de muestras buenas por culpa de una es perder
     * trabajo de campo que no se puede repetir.
     */
    fun parse(text: String): Parsed? {
        val lineas = text.lineSequence().toList()
        val corte = lineas.indexOfFirst { it.trim() == SEPARATOR }
        if (corte < 0) return null

        var id: String? = null
        var name = ""
        var created = 0L
        for (l in lineas.take(corte)) {
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf('=')
            if (i <= 0) continue
            when (t.substring(0, i)) {
                "id" -> id = t.substring(i + 1)
                "name" -> name = t.substring(i + 1)
                "created" -> created = t.substring(i + 1).toLongOrNull() ?: 0L
            }
        }
        if (id.isNullOrBlank()) return null

        val muestras = ArrayList<GpsSample>()
        var saltadas = 0
        for (l in lineas.drop(corte + 1)) {
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("epochMillis")) continue
            val c = t.split(",")
            if (c.size < 3) { saltadas++; continue }
            val ms = c[0].toLongOrNull()
            val lat = c[1].toDoubleOrNull()
            val lon = c[2].toDoubleOrNull()
            if (ms == null || lat == null || lon == null) { saltadas++; continue }
            muestras.add(GpsSample(
                epochMillis = ms, latitude = lat, longitude = lon,
                altitudeMetres = c.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                accuracyMetres = c.getOrNull(4)?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
            ))
        }
        return Parsed(Header(id, name, created), muestras, saltadas)
    }
}
