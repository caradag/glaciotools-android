package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Un log leido de un CSV, listo para graficar igual que uno recien descargado. */
data class LoadedLog(val signature: Int, val records: List<Record>, val skipped: Int)

class CsvFormatException(message: String) : Exception(message)

/**
 * Lee de vuelta el CSV que produce [CsvExporter] --y que emite el comando LOGC del
 * firmware-- para poder revisar en el telefono una descarga hecha antes, sin la placa
 * delante.
 *
 * La pieza clave es que el `LOG_SIGNATURE` se RECONSTRUYE a partir de los nombres de la
 * cabecera. Todo lo que viene despues --el grafico, la estimacion de bateria, volver a
 * exportar-- trabaja con el signature, asi que un log cargado de fichero y uno recien
 * descargado son indistinguibles a partir de aqui. La alternativa, arrastrar una lista de
 * campos en paralelo, habria duplicado esa ruta entera.
 */
object CsvImporter {

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val BY_NAME = mapOf(
        "Volt" to 0x0001, "Temp" to 0x0002, "RH" to 0x0004, "HAtemp" to 0x0008,
        "A0" to 0x0020, "A1" to 0x0040, "A2" to 0x0080, "A3" to 0x0100,
    )

    /**
     * Deduce el signature de los nombres de columna. Devuelve null si alguno no se
     * reconoce, para poder decir CUAL falla en vez de un "formato invalido" generico.
     */
    fun signatureFromHeader(names: List<String>): Int? {
        var sig = LogFormat.FORMAT_VERSION shl 12
        var ds = 0
        // La columna de tiempo corregido no es un canal: describe la MISMA magnitud que
        // Time. Si contara para el signature, un fichero exportado con ella marcada no se
        // podria volver a abrir, y el error diria "columna desconocida" sin mas pistas.
        for (n in names.filter { !it.equals(TimeCorrection.COLUMN, ignoreCase = true) }) {
            val bit = BY_NAME[n]
            when {
                bit != null -> sig = sig or bit
                n.startsWith("DS") && n.drop(2).toIntOrNull() != null -> ds++
                else -> return null
            }
        }
        if (ds > 0) {
            if (ds > 8) return null
            sig = sig or LogFormat.DS_BIT or ((ds - 1) shl LogFormat.DS_SHIFT)
        }
        return sig
    }

    fun parse(text: String): LoadedLog {
        // Las lineas de comentario se descartan ANTES de buscar la cabecera. Sin esto, un
        // CSV exportado por esta misma app --que desde la cabecera de metadatos empieza por
        // '#'-- dejaria de poder abrirse aqui: la primera linea no vacia seria un comentario
        // y se leeria como si fuera la fila de nombres de columna.
        val lines = text.lineSequence()
            .map { it.trim().removeSuffix(",") }
            .filter { it.isNotEmpty() && !it.startsWith(CsvExporter.COMMENT) }
            .toList()
        if (lines.isEmpty()) throw CsvFormatException("The file is empty")

        val header = lines.first().split(',').map { it.trim() }
        if (header.size < 2 || !header.first().equals("Time", ignoreCase = true)) {
            throw CsvFormatException(
                "The first line should be the header and start with \"Time\"; " +
                "it starts with \"${header.firstOrNull().orEmpty()}\"")
        }
        // Indice de la columna corregida, para saltarla tambien al leer las filas.
        val correctedAt = header.indexOfFirst { it.equals(TimeCorrection.COLUMN, ignoreCase = true) }
        val names = header.drop(1).filter { !it.equals(TimeCorrection.COLUMN, ignoreCase = true) }
        val signature = signatureFromHeader(names) ?: throw CsvFormatException(
            "Unknown column: ${names.first { it !in BY_NAME && !it.startsWith("DS") }}")

        // El signature codifica QUE canales hay, no en que orden se escribieron. Si el
        // orden del fichero no es el que produce el firmware, las columnas se leerian
        // cambiadas de sitio y el grafico saldria plausible y equivocado.
        val expected = LogFormat.fields(signature).map { it.name }
        if (expected != names) {
            throw CsvFormatException(
                "Column order does not match the firmware.\n" +
                "expected: ${expected.joinToString(",")}\n" +
                "found:    ${names.joinToString(",")}")
        }

        val records = ArrayList<Record>(lines.size - 1)
        var skipped = 0
        for (line in lines.drop(1)) {
            val raw = line.split(',').map { it.trim() }
            val cols = if (correctedAt >= 0) raw.filterIndexed { i, _ -> i != correctedAt } else raw
            if (cols.size != names.size + 1) { skipped++; continue }
            val time = try {
                LocalDateTime.parse(cols[0], TS)
            } catch (_: DateTimeParseException) { skipped++; continue }
            // "NaN" es como el firmware marca una lectura fallida; cualquier otra cosa que
            // no sea un numero se trata igual, que es mejor que perder la fila entera.
            //
            // El "+ 0.0" convierte el cero negativo en cero. Un "-0.00" en el fichero se lee
            // como -0.0, y BigDecimal --que usa el exportador-- no tiene cero negativo, asi
            // que al reexportar saldria "0.00". Como en Kotlin -0.0 != 0.0, el mismo dato
            // dejaria de compararse igual consigo mismo tras una ida y vuelta.
            val values = cols.drop(1).map {
                it.toDoubleOrNull()?.takeIf { v -> !v.isNaN() }?.plus(0.0)
            }
            records += Record(time, values)
        }
        if (records.isEmpty()) {
            throw CsvFormatException("No data rows could be read")
        }
        return LoadedLog(signature, records, skipped)
    }
}
