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
    const val COLUMNS =
        "epochMillis,latitude,longitude,altitudeMetres,accuracyMetres,verticalAccuracyMetres"

    /**
     * Marca de tramo. Lleva el instante en que empezo el tramo y no un numero de orden: al
     * anadir muestras a un fichero ya escrito habria que saber por cual iba, y el instante
     * no depende de nada. Repetir la misma marca no abre un tramo nuevo, lo que hace que
     * escribirla al principio de cada guardado sea inofensivo.
     */
    const val SESSION_MARK = "#session,"

    /**
     * Separacion a partir de la cual se supone que hubo un corte, en ficheros escritos antes
     * de que existieran las marcas. Dos minutos: mas que cualquier hueco entre arreglos
     * seguidos y menos que cualquier pausa de verdad.
     */
    const val INFERRED_GAP_MS = 120_000L

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
        "${s.accuracyMetres?.let { fmt(it, 2) } ?: ""}," +
        "${s.verticalAccuracyMetres?.let { fmt(it, 2) } ?: ""}\n"

    /**
     * Un lote listo para anadir al final, con las marcas de tramo donde corresponda.
     *
     * La marca se escribe al principio SIEMPRE y dentro del lote cada vez que cambia el
     * tramo. Escribirla de mas no hace nada --una marca repetida no abre tramo nuevo-- y eso
     * es lo que permite que esto no necesite saber que habia ya en el fichero.
     */
    fun appendBlock(samples: List<GpsSample>): String {
        if (samples.isEmpty()) return ""
        val sb = StringBuilder()
        var tramo = Long.MIN_VALUE
        samples.forEach { s ->
            if (s.sessionStartMillis != tramo) {
                tramo = s.sessionStartMillis
                sb.append(SESSION_MARK).append(tramo).append('\n')
            }
            sb.append(sampleLine(s))
        }
        return sb.toString()
    }

    private fun fmt(v: Double, d: Int) = "%.${d}f".format(java.util.Locale.ROOT, v)

    data class Parsed(val header: Header, val samples: List<GpsSample>, val skipped: Int) {
        /** Los tramos que hay, en orden. */
        val sessionStarts: List<Long>
            get() = samples.map { it.sessionStartMillis }.distinct().sorted()
    }

    private fun inferirTramos(muestras: List<GpsSample>): List<GpsSample> {
        if (muestras.isEmpty()) return muestras
        var inicio = muestras.first().epochMillis
        var anterior = inicio
        return muestras.map { s ->
            if (s.epochMillis - anterior > INFERRED_GAP_MS) inicio = s.epochMillis
            anterior = s.epochMillis
            s.copy(sessionStartMillis = inicio)
        }
    }

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
        var tramo = 0L
        var huboMarcas = false
        for (l in lineas.drop(corte + 1)) {
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("epochMillis")) continue
            if (t.startsWith(SESSION_MARK)) {
                t.removePrefix(SESSION_MARK).toLongOrNull()?.let { tramo = it; huboMarcas = true }
                continue
            }
            // Cualquier otro comentario se ignora sin contarlo como linea rota.
            if (t.startsWith("#")) continue
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
                verticalAccuracyMetres =
                    c.getOrNull(5)?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                sessionStartMillis = tramo,
            ))
        }
        // Un fichero escrito antes de que existieran las marcas no dice donde acaba un tramo,
        // pero lo dicen los huecos: entre dos arreglos seguidos pasan segundos, y entre dos
        // visitas, horas o dias. Suponerlo es mejor que tratar dos visitas como una sola, que
        // es lo que haria creer que hay mucha mas informacion independiente de la que hay.
        val finales = if (huboMarcas) muestras else inferirTramos(muestras)
        return Parsed(Header(id, name, created), finales, saltadas)
    }
}
