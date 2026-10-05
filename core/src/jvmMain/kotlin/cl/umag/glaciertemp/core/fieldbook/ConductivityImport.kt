package cl.umag.glaciertemp.core.fieldbook

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Lee el fichero de un conductimetro con registro (logger) y saca horas y conductividades.
 *
 * NO HAY UN FORMATO: cada fabricante exporta el suyo. HOBO entrecomilla las cabeceras y mete
 * el huso en el nombre de la columna ("Date Time, GMT-04:00"), Solinst separa fecha y hora en
 * dos columnas, una hoja de calculo europea usa punto y coma y coma decimal, y casi todos
 * dejan unas lineas de preambulo antes de la cabecera. En vez de un lector por marca --que
 * fallaria con la primera que no este en la lista-- se busca lo que tiene que estar en
 * cualquiera: una fila de cabecera con una columna de conductividad, y otra (u otras dos) de
 * tiempo.
 *
 * LA FECHA SE PRUEBA CONTRA TODAS LAS FILAS, no contra la primera. "03/04/26" es 3 de abril o
 * 4 de marzo segun el pais, y la primera fila sola no lo decide; las demas casi siempre si,
 * porque en algun momento el dia pasa de doce. Se queda el formato que lee MAS filas, y a
 * igualdad el que las deja en orden.
 */
object ConductivityImport {

    /**
     * Una columna que puede ser la de conductividad.
     *
     * @param unitIndex si la unidad viene FILA A FILA en otra columna (`Ch1_Value` /
     *   `Ch1_Unit`), su indice; entonces [factorToMicroSiemens] no se usa y cada fila se
     *   convierte con la suya. Hay conductimetros que cambian de escala solos --de uS a mS
     *   cuando la sal sube-- y con un factor unico esas filas saldrian mil veces menores.
     */
    data class Column(val index: Int, val header: String, val factorToMicroSiemens: Double,
                      val unitNote: String?, val unitIndex: Int? = null)

    data class Result(
        val readings: List<ConductivityReading>,
        /** Todas las columnas que parecen de conductividad; la elegida es [column]. */
        val candidates: List<Column>,
        val column: Column?,
        /** Lo que se leyo de como estaba el tiempo, para decirlo en pantalla. */
        val timeDescription: String,
        val skippedRows: Int,
        val warnings: List<String>,
        val error: String? = null,
        /** Filas con la marca de fuera de escala del aparato (99999999). */
        val overRange: Int = 0,
        /** Filas con fecha muy lejos del resto: un reloj sin poner al encender el logger. */
        val farDated: Int = 0,
    )

    private fun fail(msg: String) = Result(emptyList(), emptyList(), null, "", 0, emptyList(), msg)

    // ------------------------------------ troceado ------------------------------------

    /** Separa una linea de CSV respetando comillas. Las cabeceras de HOBO llevan comas dentro. */
    internal fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var comillas = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && comillas && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> comillas = !comillas
                c == sep && !comillas -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    /**
     * El separador: el que reparte las lineas en el mismo numero de columnas, mas de una.
     *
     * No vale contar cuantas comas hay: con coma decimal y punto y coma de separador las
     * comas ganan siempre y el fichero se parte por la mitad de cada numero.
     */
    private fun separator(lines: List<String>): Char {
        val muestra = lines.filter { it.isNotBlank() }.takeLast(40)
        return listOf('\t', ';', ',').maxByOrNull { sep ->
            // Las lineas de una sola celda no votan: son el preambulo ("Serial_number:"), y
            // en un fichero corto son mayoria y dejaban la moda en 1 con cualquier separador.
            val cuentas = muestra.map { split(it, sep).size }.filter { it >= 2 }
            val moda = cuentas.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (moda == null) -1 else moda.value * 100 + moda.key
        } ?: ','
    }

    // ------------------------------------ columnas ------------------------------------

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace('μ', 'µ')

    private val CONDUCTIVIDAD = Regex(
        """cond|\bec\b|\bspc\b|\bsc\b|specific|µs|us/cm|ms/cm|low range|high range|full range""")

    private fun isConductivityHeader(h: String): Boolean {
        val n = norm(h)
        if (n.contains("temp")) return false
        return CONDUCTIVIDAD.containsMatchIn(n)
    }

    /**
     * La unidad sale de la cabecera. mS/cm se pasa a uS/cm; S/m es 10.000 uS/cm. Sin unidad
     * escrita se supone uS/cm --lo que dan todos los conductimetros de campo para agua de
     * rio-- y se dice, porque si en realidad eran mS/cm el caudal sale mil veces menor.
     */
    private fun unit(h: String): Pair<Double, String?> {
        val n = norm(h)
        return when {
            "ms/cm" in n -> 1000.0 to "mS/cm converted to µS/cm"
            "µs/cm" in n || "us/cm" in n -> 1.0 to null
            Regex("""\bs/m\b""").containsMatchIn(n) -> 10_000.0 to "S/m converted to µS/cm"
            Regex("""\bms/m\b""").containsMatchIn(n) -> 10.0 to "mS/m converted to µS/cm"
            else -> 1.0 to "no unit in the column name: assumed µS/cm"
        }
    }

    /**
     * Orden de preferencia entre varias columnas de conductividad.
     *
     * La conductancia ESPECIFICA (compensada a 25 °C) primero: si el agua cambia de
     * temperatura durante la medida, la cruda cambia sin que haya sal. Despues la de "rango
     * bajo" de HOBO, que es la que tiene resolucion en aguas de deshielo. Es solo el valor
     * por defecto: la pantalla deja elegir.
     */
    private fun score(h: String): Int {
        val n = norm(h)
        return when {
            "specific" in n || "spc" in n || "25" in n -> 0
            "low range" in n -> 1
            "cond" in n -> 2
            "full range" in n -> 3
            "high range" in n -> 4
            else -> 5
        }
    }

    // -------------------------------------- tiempo --------------------------------------

    private fun fmt(p: String): DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(p)
        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        .toFormatter(Locale.US)

    private val FECHAS = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd/MM/yy", "MM/dd/yy",
        "dd.MM.yyyy", "dd-MM-yyyy")
    private val HORAS = listOf("HH:mm:ss.SSS", "HH:mm:ss", "HH:mm", "hh:mm:ss a", "hh:mm a")

    /** Patrones fecha+hora, con separador espacio o T. */
    private val PATRONES: List<Pair<String, DateTimeFormatter>> =
        FECHAS.flatMap { f -> HORAS.flatMap { h ->
            listOf("$f $h" to fmt("$f $h"), "$f'T'$h" to fmt("$f'T'$h"))
        } }

    /** El huso escrito en la cabecera, como "GMT-04:00", "UTC" o "UTC+1". */
    internal fun zoneFromHeader(h: String): ZoneOffset? {
        val m = Regex("""(?:GMT|UTC)\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?""", RegexOption.IGNORE_CASE).find(h)
        if (m != null) {
            val signo = if (m.groupValues[1] == "-") -1 else 1
            val hh = m.groupValues[2].toInt()
            val mm = m.groupValues[3].ifEmpty { "0" }.toInt()
            return runCatching { ZoneOffset.ofHoursMinutes(signo * hh, signo * mm) }.getOrNull()
        }
        if (Regex("""\b(GMT|UTC)\b""", RegexOption.IGNORE_CASE).containsMatchIn(h)) return ZoneOffset.UTC
        return null
    }

    /**
     * Unidad de conductividad escrita en una celda de datos ("uS", "mS", "µS/cm"), como factor
     * a uS/cm; null si no es una unidad de conductividad (por ejemplo "Degree_C").
     */
    internal fun rowUnitFactor(cell: String?): Double? {
        val n = norm(cell?.trim() ?: return null).replace(" ", "")
        return when (n) {
            "us", "µs", "us/cm", "µs/cm" -> 1.0
            "ms", "ms/cm" -> 1000.0
            "s/m" -> 10_000.0
            "ms/m" -> 10.0
            else -> null
        }
    }

    /**
     * Pares valor/unidad de la cabecera: `Ch1_Value` con `Ch1_Unit`, `Value 2` con `Unit 2`...
     * Devuelve (columna del valor, columna de la unidad).
     */
    private fun paresValorUnidad(cab: List<String>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        cab.forEachIndexed { i, h ->
            val n = norm(h)
            if (!n.contains("value")) return@forEachIndexed
            val clave = n.replace("value", "unit")
            val j = cab.indexOfFirst { norm(it) == clave }
            if (j >= 0 && j != i) out += i to j
        }
        return out
    }

    /** La marca de fuera de escala: todo nueves, como 99999999. */
    private fun fueraDeEscala(cell: String?): Boolean =
        cell?.trim()?.let { Regex("""9{6,}(\.0*)?""").matches(it) } == true

    private fun isTimeHeader(h: String): Boolean {
        val n = norm(h)
        return "date" in n || "time" in n || "fecha" in n || "hora" in n || "timestamp" in n
    }

    /**
     * Lee el fichero tal como llega del disco: decide si es texto y en que codificacion.
     *
     * UN .XLS PUEDE SER DOS COSAS. Los conductimetros como el del PIRP 2024 escriben texto
     * separado por tabuladores y lo llaman `.XLS` para que Excel lo abra con doble clic; y si
     * alguien lo guarda despues desde Excel, sale un libro binario con el mismo nombre. El
     * primero se lee; el segundo no tiene nada legible y se dice por que, en vez de un "no hay
     * columna de conductividad" que manda a buscar el problema donde no esta.
     *
     * Texto en UTF-8 si lo es; si no, Latin-1, que es lo que escriben los programas viejos
     * de Windows y donde la µ de uS es el byte 0xB5.
     */
    fun parseBytes(bytes: ByteArray, zone: ZoneId, elapsedOrigin: Long? = null,
                   column: Int? = null): Result {
        fun empieza(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        if (empieza(0xD0, 0xCF, 0x11, 0xE0) || empieza(0x50, 0x4B, 0x03, 0x04)) return fail(
            "This is an Excel workbook, not a text file. Import the logger's own export " +
            "(text, CSV or TSV) or save the sheet from Excel as CSV.")
        val utf8 = Charsets.UTF_8.newDecoder()
        val texto = runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        return parse(texto, zone, elapsedOrigin, column)
    }

    /**
     * Lee el fichero.
     *
     * @param zone el huso en que estan las horas si el fichero no lo dice. Es el del telefono:
     *   el logger se configura con el reloj del ordenador o del telefono que lo programo.
     * @param elapsedOrigin la hora de inyeccion. Pone el cero si el fichero trae segundos
     *   transcurridos en vez de horas --es desde donde cuenta cualquiera que cronometre-- y
     *   desempata dia/mes contra mes/dia cuando el fichero no lo decide.
     * @param column indice de la columna elegida, si el usuario ya eligio una.
     */
    fun parse(text: String, zone: ZoneId, elapsedOrigin: Long? = null, column: Int? = null): Result {
        val lineas = text.removePrefix("﻿").lines().map { it.trimEnd('\r') }
        if (lineas.none { it.isNotBlank() }) return fail("The file is empty.")
        val sep = separator(lineas)

        // La cabecera: la primera linea con una columna de conductividad, o con un par
        // valor/unidad (los conductimetros que escriben "Ch1_Value" y la unidad aparte).
        val iCab = lineas.indexOfFirst { l ->
            val c = split(l, sep)
            c.any { isConductivityHeader(it) } || paresValorUnidad(c).isNotEmpty()
        }
        val sinColumna = "No conductivity column found. The file needs a header row with a " +
            "column whose name mentions conductivity, EC, SpC or µS/cm, or a value column " +
            "with its unit (µS, mS) in the next one."
        if (iCab < 0) return fail(sinColumna)
        val cab = split(lineas[iCab], sep)
        val filas = lineas.drop(iCab + 1).filter { it.isNotBlank() }.map { split(it, sep) }
        val coma = sep != ','

        // Los pares valor/unidad cuentan si su columna de unidad DICE conductividad en los
        // datos: "Ch2_Value" con "Degree_C" es la temperatura, y no se ofrece.
        val porUnidad = paresValorUnidad(cab).filter { (_, j) ->
            val vistas = filas.asSequence().mapNotNull { it.getOrNull(j)?.trim() }
                .filter { it.isNotEmpty() }.take(500).toList()
            vistas.isNotEmpty() && vistas.count { rowUnitFactor(it) != null } * 2 > vistas.size
        }.map { (i, j) -> Column(i, cab[i], 1.0, null, unitIndex = j) }
        val candidatos = (cab.withIndex().filter { (i, h) ->
                              isConductivityHeader(h) && porUnidad.none { it.index == i } }
                              .map { (i, h) -> val (f, nota) = unit(h); Column(i, h, f, nota) } +
                          porUnidad)
            .sortedBy { if (it.unitIndex != null) 2 else score(it.header) }
        if (candidatos.isEmpty()) return fail(sinColumna)
        val elegida = candidatos.firstOrNull { it.index == column } ?: candidatos.first()

        fun numero(s: String?): Double? {
            val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return (if (coma) t.replace(',', '.') else t).toDoubleOrNull()
        }

        // ---- el tiempo: una columna fecha-hora, dos columnas fecha y hora, o segundos ----
        val deTiempo = cab.withIndex().filter { (i, h) -> isTimeHeader(h) && i != elegida.index }
        val husoCab = cab.firstNotNullOfOrNull { zoneFromHeader(it) }
        val huso: ZoneId = husoCab ?: zone

        data class Lector(val desc: String, val leer: (List<String>) -> Long?)
        val lectores = ArrayList<Lector>()

        for ((i, h) in deTiempo) {
            // ISO con desfase incluido: no hay nada que adivinar.
            lectores += Lector("“$h” (ISO 8601)") { f ->
                f.getOrNull(i)?.let { s -> runCatching {
                    OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli() }.getOrNull() }
            }
            for ((p, df) in PATRONES) {
                lectores += Lector("“$h” as $p") { f ->
                    f.getOrNull(i)?.let { s -> runCatching {
                        LocalDateTime.parse(s.trim(), df).atZone(huso).toInstant().toEpochMilli()
                    }.getOrNull() }
                }
            }
        }
        // Fecha y hora en columnas separadas (Solinst, muchas hojas de calculo).
        val iFecha = cab.indexOfFirst { val n = norm(it); ("date" in n || "fecha" in n) && "time" !in n }
        val iHora = cab.indexOfFirst { val n = norm(it); ("time" in n || "hora" in n) && "date" !in n }
        if (iFecha >= 0 && iHora >= 0 && iFecha != iHora) {
            for (fp in FECHAS) for (hp in HORAS) {
                val df = fmt(fp); val hf = fmt(hp)
                lectores += Lector("“${cab[iFecha]}” as $fp + “${cab[iHora]}” as $hp") { f ->
                    runCatching {
                        val d = LocalDate.parse(f[iFecha].trim(), df)
                        val t = LocalTime.parse(f[iHora].trim(), hf)
                        LocalDateTime.of(d, t).atZone(huso).toInstant().toEpochMilli()
                    }.getOrNull()
                }
            }
        }
        // Segundos transcurridos: solo si no hay nada mejor y se sabe desde donde contar.
        val iSeg = cab.indexOfFirst { val n = norm(it)
            "elapsed" in n || n == "s" || n == "sec" || n == "seconds" || n == "t (s)" || n == "time (s)" }
        if (iSeg >= 0 && elapsedOrigin != null) {
            lectores += Lector("“${cab[iSeg]}” as seconds after injection") { f ->
                numero(f.getOrNull(iSeg))?.let { elapsedOrigin + (it * 1000).toLong() }
            }
        }
        if (lectores.isEmpty()) return fail(
            "No time column found next to the conductivity. The file needs a date-time " +
            "column (or a date and a time column).")

        // El formato se elige con una MUESTRA repartida por el fichero --principio, final y
        // medio-- y no con todas las filas: son ochenta formatos candidatos, cada intento
        // fallido es una excepcion, y un logger a un segundo trae miles de filas. El final
        // importa tanto como el principio: es donde el dia suele haber pasado de doce.
        val muestra = (filas.indices.toList().take(25) + filas.indices.toList().takeLast(25) +
                       (0 until 20).map { it * filas.size / 20 }).distinct().sorted()
        //
        // A igualdad de filas leidas y en orden --un fichero de un solo dia con dia <= 12 se
        // lee igual de bien como dia/mes que como mes/dia-- gana el que deja las horas mas
        // cerca de la inyeccion, que es cuando se sabe que se estaba midiendo.
        data class Nota(val l: Lector, val ok: Int, val orden: Int, val lejos: Long)
        val notas = lectores.map { l ->
            val ts = muestra.mapNotNull { l.leer(filas[it]) }
            val lejos = if (elapsedOrigin == null || ts.isEmpty()) 0L
                        else kotlin.math.abs(ts.sorted()[ts.size / 2] - elapsedOrigin)
            Nota(l, ts.size, ts.zipWithNext().count { (a, b) -> b >= a }, lejos)
        }
        val lector = notas.sortedWith(compareByDescending<Nota> { it.ok }
            .thenByDescending { it.orden }.thenBy { it.lejos }).first().l
        val tiempos = filas.map { lector.leer(it) }
        if (tiempos.none { it != null }) return fail(
            "The time column could not be read. Tried day/month and month/day orders, " +
            "12 and 24 h clocks, and ISO 8601.")

        val lecturas = ArrayList<ConductivityReading>()
        var saltadas = 0
        var fuera = 0
        var mS = 0
        filas.forEachIndexed { k, f ->
            val t = tiempos[k]
            val celda = f.getOrNull(elegida.index)
            // FUERA DE ESCALA NO ES UN DATO: 99999999 es lo que escribe el aparato cuando la
            // lectura no cabe. Tomado como numero, una sola fila asi dejaria Sigma en millones.
            if (fueraDeEscala(celda)) { fuera++; return@forEachIndexed }
            val v = numero(celda)
            val factor = if (elegida.unitIndex != null) rowUnitFactor(f.getOrNull(elegida.unitIndex))
                         else elegida.factorToMicroSiemens
            if (t == null || v == null || !v.isFinite() || factor == null) {
                saltadas++; return@forEachIndexed
            }
            if (factor == 1000.0 && elegida.unitIndex != null) mS++
            lecturas += ConductivityReading(t, v * factor)
        }
        if (lecturas.size < 2) return fail("Fewer than two rows had both a time and a conductivity.")

        // Horas repetidas: la ultima gana, como al teclear (y como en SaltDilutionMath).
        var unicas = lecturas.associateBy { it.atEpochMillis }.values.sortedBy { it.atEpochMillis }
        val repetidas = lecturas.size - unicas.size

        // UN RELOJ SIN PONER. Un logger que se enciende sin hora empieza a contar desde su
        // fecha de fabrica (el 1 de enero de 2000 en el del PIRP 2024) hasta que alguien la
        // pone. Esas filas, metidas en el grafico, estiran el eje del tiempo veinticuatro
        // anos y dejan la medida en un punto. Fuera las que distan mas de 180 dias de la
        // mediana, y se dice cuantas.
        val mediana = unicas[unicas.size / 2].atEpochMillis
        val lejos = 180L * 86_400_000L
        val cerca = unicas.filter { kotlin.math.abs(it.atEpochMillis - mediana) <= lejos }
        val lejanas = unicas.size - cerca.size
        unicas = cerca

        val avisos = ArrayList<String>()
        elegida.unitNote?.let { avisos += it }
        if (mS > 0) avisos += "$mS reading(s) in mS were converted to µS."
        if (fuera > 0) avisos += "$fuera reading(s) marked as over range (99999999) were left out."
        if (lejanas > 0) avisos += "$lejanas reading(s) dated far from the rest (a logger clock " +
                                   "not set yet?) were left out."
        if (husoCab == null && !lector.desc.contains("ISO") && !lector.desc.contains("seconds"))
            avisos += "The file gives no time zone: times read as ${zone.id}."
        // Contadas ANTES de quitar las lejanas: despues, la resta las sumaba como repetidas.
        if (repetidas > 0)
            avisos += "$repetidas row(s) repeated a time and were merged."

        return Result(
            readings = unicas,
            candidates = candidatos,
            column = elegida,
            timeDescription = lector.desc + (husoCab?.let { ", zone $it from the header" } ?: ""),
            skippedRows = saltadas,
            warnings = avisos,
            overRange = fuera,
            farDated = lejanas,
        )
    }

    /**
     * Las lecturas alrededor de la inyeccion: de [beforeMin] minutos antes a [afterMin]
     * despues.
     *
     * Un fichero de logger trae dias, a veces varias inyecciones (el del PIRP 2024: tres pasos
     * de sal el mismo dia y pruebas en salmuera dos dias antes). La nota es UNA medicion, y
     * guardar el fichero entero metia en ella las demas. Antes de la inyeccion se guarda un
     * rato para poder elegir la base en el tramo plano; despues, lo que tarde la nube en
     * pasar, que la guia acota en 15-20 minutos.
     */
    fun aroundInjection(readings: List<ConductivityReading>, injectionMillis: Long,
                        beforeMin: Int = 15, afterMin: Int = 60): List<ConductivityReading> =
        readings.filter {
            it.atEpochMillis >= injectionMillis - beforeMin * 60_000L &&
            it.atEpochMillis <= injectionMillis + afterMin * 60_000L
        }
}
