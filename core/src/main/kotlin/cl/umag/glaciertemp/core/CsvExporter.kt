package cl.umag.glaciertemp.core

import java.time.format.DateTimeFormatter

/**
 * Genera el mismo CSV que decode_logh.py y que el comando LOGC del firmware, con una
 * cabecera opcional de metadatos en lineas de comentario y una columna opcional de tiempo
 * corregido.
 *
 * Las lineas de metadatos van prefijadas por '#'. Eso obliga a que [CsvImporter] las salte:
 * si no, la app dejaria de poder abrir sus propios ficheros, que es una funcion que ya
 * existe y que nadie asociaria con haber anadido una cabecera.
 */
object CsvExporter {

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Prefijo de comentario. Lo reconoce [CsvImporter] y lo ignora cualquier lector de CSV. */
    const val COMMENT = "#"

    fun header(signature: Int, corrected: Boolean = false): String {
        val first = if (corrected) "Time,${TimeCorrection.COLUMN}" else "Time"
        return first + "," + LogFormat.fields(signature).joinToString(",") { it.name }
    }

    fun row(record: Record, signature: Int): String = row(record, signature, null)

    private fun row(record: Record, signature: Int, correctedTime: String?): String {
        val fields = LogFormat.fields(signature)
        val cols = record.values.mapIndexed { i, v -> LogDecoder.formatValue(v, fields[i].decimals) }
        val stamp = TS.format(record.time)
        val head = if (correctedTime != null) "$stamp,$correctedTime" else stamp
        return head + "," + cols.joinToString(",")
    }

    /**
     * La cabecera de metadatos, una linea de comentario por dato. Se omite cada campo que
     * falte en vez de escribirlo vacio: una linea ausente se lee como "no se sabe", y una
     * linea vacia como "se midio y dio esto".
     */
    fun metadataLines(meta: DownloadMetadata): List<String> {
        val out = ArrayList<String>()
        fun add(text: String) = out.add("$COMMENT $text")

        add("GlacioTools export")
        add("downloaded: ${TS.format(meta.downloadedAt)}")
        meta.boardId?.let { add("board: $it") }
        meta.boardFullId?.let { add("board hardware id: $it") }

        val p = meta.position
        if (p != null) {
            // Las coordenadas se formatean en DownloadMetadata y no aqui: la pantalla de
            // estadisticas las muestra tambien, y dos `%.5f` en dos sitios acabarian
            // divergiendo el dia que alguien cambie uno.
            val extra = ArrayList<String>()
            p.accuracyMetres?.let {
                extra += "accuracy %.0f m".format(java.util.Locale.ROOT, it)
            }
            // El origen sustituye a la antiguedad cuando lo hay, igual que en la pantalla:
            // la antiguedad de un punto promediado no dice nada malo de el.
            extra += p.sourceLabel ?: "fix ${BoardClock.format(p.ageSeconds)} old"
            add("position: ${meta.positionDescription()}  (${extra.joinToString(", ")})")
            // En su propia linea y solo si la hay. Escribirla como "0 m" cuando falta seria
            // una altitud perfectamente plausible y un dato ausente disfrazado de medida.
            p.altitudeMetres?.let {
                add("altitude: %.0f m (WGS84 ellipsoid)".format(java.util.Locale.ROOT, it))
            }
        } else {
            meta.positionNote?.let { add("position: not recorded -- $it") }
        }

        meta.reference?.let { add("clock reference: ${it.label}") }
        meta.boardTime?.let { add("board clock: ${TS.format(it)}") }
        meta.offsetDescription()?.let { add("board clock offset: $it") }

        // Una nota de varias lineas lleva su propio '#' en cada una. Sin eso, el fichero
        // deja de ser un CSV legible a partir de la segunda linea.
        meta.note?.takeIf { it.isNotBlank() }?.let { note ->
            note.trim().lines().forEachIndexed { i, l ->
                add(if (i == 0) "note: $l" else "      $l")
            }
        }
        return out
    }

    fun export(records: List<Record>, signature: Int): String =
        export(records, signature, null, false)

    /**
     * Las primeras [maxRows] filas, para la vista previa.
     *
     * Existe porque generarla con [export] y quedarse con el principio significa formatear
     * el log ENTERO cada vez. Con cien mil registros eso son casi seis megas de texto por
     * cada pulsacion en el campo de la nota, y el teclado tardaba segundos en responder.
     */
    fun preview(
        records: List<Record>,
        signature: Int,
        meta: DownloadMetadata? = null,
        corrected: Boolean = false,
        maxRows: Int = 12,
    ): String {
        val cabeza = records.take(maxRows)
        // La correccion se pide como FUNCION y se aplica solo a las filas que se ven: sus
        // tres parametros salen del log completo, pero calcularla entera para ensenar doce
        // lineas es lo que hacia que escribir la nota costara segundos por letra.
        val fix = if (corrected) TimeCorrection.corrector(records, meta) else null
        return buildString {
            meta?.let { metadataLines(it).forEach { l -> append(l).append('\n') } }
            append(header(signature, fix != null)).append('\n')
            cabeza.forEach { r ->
                append(row(r, signature, fix?.let { TS.format(it(r)) })).append('\n')
            }
            if (records.size > cabeza.size) {
                append("... ${records.size - cabeza.size} more rows\n")
            }
        }
    }

    /**
     * [corrected] solo tiene efecto si [TimeCorrection] puede calcular algo con [meta]; si
     * no, la columna se omite en silencio en vez de escribirse igual a la original, que
     * seria afirmar que se ha corregido algo.
     */
    fun export(
        records: List<Record>,
        signature: Int,
        meta: DownloadMetadata?,
        corrected: Boolean,
    ): String {
        val fix = if (corrected) TimeCorrection.corrector(records, meta) else null
        return buildString {
            meta?.let { metadataLines(it).forEach { l -> append(l).append('\n') } }
            append(header(signature, fix != null)).append('\n')
            records.forEach { r ->
                append(row(r, signature, fix?.let { TS.format(it(r)) })).append('\n')
            }
        }
    }
}
