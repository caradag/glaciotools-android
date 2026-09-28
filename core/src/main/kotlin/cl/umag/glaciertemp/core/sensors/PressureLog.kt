package cl.umag.glaciertemp.core.sensors

import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Una lectura de presion tomada en un sitio concreto.
 *
 * LLEVA LA POSICION porque sin ella el registro no significa nada. La presion cae unos 12 hPa
 * por cada cien metros de altura: una muestra tomada cien metros mas arriba parece una caida
 * de presion enorme, cuando lo unico que paso es que se subio la cuesta. Guardando donde se
 * tomo, se puede avisar.
 */
data class PressureSample(
    val epochMillis: Long,
    val hPa: Double,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMetres: Double? = null,
)

/** Un sitio con nombre y su serie de lecturas. */
data class PressurePlace(
    val id: String,
    val name: String,
    val samples: List<PressureSample> = emptyList(),
) {
    /** La posicion de referencia: la de la primera lectura que la traiga. */
    fun reference(): PressureSample? =
        samples.firstOrNull { it.latitude != null && it.longitude != null }
}

/**
 * Que dice una serie de presiones sobre el tiempo que viene.
 *
 * LA TENDENCIA ES EL DATO, no la presion. Saber que hay 985 hPa no dice nada sin conocer la
 * altura del sitio; saber que han caido cuatro en seis horas dice que se acerca un frente, y
 * eso vale igual a nivel del mar que a mil metros. Por eso el registro es por LUGAR: la
 * altura se mantiene constante y lo que se mide es el cambio.
 */
object PressureTrend {

    /** Umbrales de la escala de los partes: lo que cuenta como cambio marcado en 3 h. */
    const val MARCADO_3H = 3.5
    const val MODERADO_3H = 1.6

    /** El cambio en las ultimas [horas], o null si no hay con que compararlo. */
    fun change(samples: List<PressureSample>, horas: Double,
               now: Long = System.currentTimeMillis()): Double? {
        if (samples.size < 2) return null
        val ultima = samples.maxByOrNull { it.epochMillis } ?: return null
        val limite = now - (horas * 3_600_000).toLong()
        // La mas antigua DENTRO de la ventana; si no hay ninguna, la mas cercana al limite
        // por debajo, para no callar solo porque las lecturas son espaciadas.
        val previa = samples.filter { it.epochMillis in limite until ultima.epochMillis }
            .minByOrNull { it.epochMillis }
            ?: samples.filter { it.epochMillis < ultima.epochMillis }
                .maxByOrNull { it.epochMillis }
            ?: return null
        return ultima.hPa - previa.hPa
    }

    /**
     * El cambio en palabras, con el tiempo real que cubre.
     *
     * SE DICE SOBRE CUANTO TIEMPO. "Bajando 4 hPa" no significa lo mismo en tres horas que en
     * tres dias, y en un registro tomado a mano los intervalos no son regulares.
     */
    fun describe(samples: List<PressureSample>, now: Long = System.currentTimeMillis()): String? {
        if (samples.size < 2) return null
        val ordenadas = samples.sortedBy { it.epochMillis }
        val ultima = ordenadas.last()
        val previa = ordenadas[ordenadas.size - 2]
        val d = ultima.hPa - previa.hPa
        val horas = (ultima.epochMillis - previa.epochMillis) / 3_600_000.0
        val cuanto = when {
            horas < 1.0 -> "%.0f min".format(horas * 60)
            horas < 48.0 -> "%.1f h".format(horas)
            else -> "%.0f days".format(horas / 24)
        }
        val verbo = when {
            abs(d) < 0.3 -> "steady"
            d > 0 -> "rising"
            else -> "falling"
        }
        return if (verbo == "steady") "Steady over the last $cuanto"
               else "%s %.1f hPa over the last %s".format(
                   verbo.replaceFirstChar { it.uppercase() }, abs(d), cuanto)
    }

    /**
     * El aviso de la escala de los partes, normalizado a tres horas.
     *
     * Es donde la tendencia deja de ser una curiosidad: una caida marcada en tres horas es la
     * senal clasica de que hay que replegar.
     */
    fun warning(samples: List<PressureSample>, now: Long = System.currentTimeMillis()): String? {
        val d3 = change(samples, 3.0, now) ?: return null
        return when {
            d3 <= -MARCADO_3H ->
                "Falling fast — %.1f hPa in about three hours. That is the classic sign of a ".format(-d3) +
                "front arriving."
            d3 <= -MODERADO_3H -> "Falling steadily. Worth watching."
            d3 >= MARCADO_3H -> "Rising fast — usually clearing, often with wind first."
            else -> null
        }
    }
}

/**
 * Lo que se copia de un lugar: la serie entera, no solo la ultima.
 *
 * COMO TABLA Y NO COMO PROSA. Esto acaba pegado en una hoja de calculo para dibujarlo o
 * cruzarlo con otra cosa, y una frase bonita habria que deshacerla a mano. Una fila por
 * lectura, con la fecha en formato ordenable, la presion y donde se tomo.
 */
object PressureReport {

    fun clipboardText(place: PressurePlace,
                      zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        buildString {
            appendLine("GlacioTools — pressure at " + place.name.ifBlank { "(unnamed place)" })
            PressureTrend.describe(place.samples)?.let { appendLine(it) }
            appendLine()
            appendLine("timestamp	hPa	latitude	longitude	altitude_m")
            val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            place.samples.sortedBy { it.epochMillis }.forEach { s ->
                appendLine("%s	%.2f	%s	%s	%s".format(
                    f.format(java.time.Instant.ofEpochMilli(s.epochMillis).atZone(zone)),
                    s.hPa,
                    s.latitude?.let { "%.6f".format(it) } ?: "",
                    s.longitude?.let { "%.6f".format(it) } ?: "",
                    s.altitudeMetres?.let { "%.1f".format(it) } ?: ""))
            }
            append("Raw sensor pressure, not corrected to sea level. ")
            append("Only the change means anything, and only between readings taken at the ")
            append("same spot.")
        }
}

/**
 * Los lugares y sus lecturas, en disco.
 *
 * Un fichero por lugar, como la libreta y por lo mismo: anadir una lectura a un sitio no
 * puede poner en riesgo los otros, y un fichero corrupto se salta.
 */
class PressureStore(private val dir: File) {

    companion object {
        const val EXTENSION = ".pressure"
        private const val SEPARATOR = "---"
    }

    init { dir.mkdirs() }

    private fun file(id: String) = File(dir, "$id$EXTENSION")

    fun newId(): String = "b" + System.currentTimeMillis() + "-" + (100000..999999).random()

    fun create(name: String): String {
        var id = newId()
        while (file(id).exists()) id = newId()
        file(id).writeText("# GlacioTools pressure place\nid=$id\nname=" +
                           name.replace('\n', ' ') + "\n$SEPARATOR\n")
        return id
    }

    /** Anade una lectura al final. Es un append de verdad: no se reescribe lo que habia. */
    fun append(id: String, s: PressureSample) {
        val f = file(id)
        if (!f.exists()) return
        f.appendText("%d,%.3f,%s,%s,%s\n".format(
            s.epochMillis, s.hPa,
            s.latitude?.let { "%.6f".format(it) } ?: "",
            s.longitude?.let { "%.6f".format(it) } ?: "",
            s.altitudeMetres?.let { "%.1f".format(it) } ?: ""))
    }

    fun rename(id: String, name: String): Boolean {
        val p = load(id) ?: return false
        val tmp = File(dir, "$id.tmp")
        tmp.writeText("# GlacioTools pressure place\nid=$id\nname=" +
                      name.replace('\n', ' ') + "\n$SEPARATOR\n" +
                      p.samples.joinToString("") { s ->
                          "%d,%.3f,%s,%s,%s\n".format(
                              s.epochMillis, s.hPa,
                              s.latitude?.let { "%.6f".format(it) } ?: "",
                              s.longitude?.let { "%.6f".format(it) } ?: "",
                              s.altitudeMetres?.let { "%.1f".format(it) } ?: "")
                      })
        val ok = tmp.renameTo(file(id))
        if (!ok) tmp.delete()
        return ok
    }

    fun delete(id: String): Boolean = file(id).delete()

    fun load(id: String): PressurePlace? =
        file(id).takeIf { it.exists() }?.let { f ->
            runCatching { parse(f.readText()) }.getOrNull()
        }

    /** Todos los lugares, el de lectura mas reciente primero. */
    fun list(): List<PressurePlace> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray())
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .sortedByDescending { it.samples.maxOfOrNull { s -> s.epochMillis } ?: 0L }

    internal fun parse(texto: String): PressurePlace? {
        val lineas = texto.lines()
        val corte = lineas.indexOfFirst { it.trim() == SEPARATOR }
        if (corte < 0) return null
        var id: String? = null
        var nombre = ""
        lineas.take(corte).forEach { l ->
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val i = t.indexOf('=')
            if (i <= 0) return@forEach
            when (t.substring(0, i)) {
                "id" -> id = t.substring(i + 1)
                "name" -> nombre = t.substring(i + 1)
            }
        }
        val real = id ?: return null
        val muestras = lineas.drop(corte + 1).mapNotNull { l ->
            val c = l.trim().split(",")
            if (c.size < 2) return@mapNotNull null
            val t = c[0].toLongOrNull() ?: return@mapNotNull null
            val p = c[1].toDoubleOrNull() ?: return@mapNotNull null
            PressureSample(t, p,
                           c.getOrNull(2)?.toDoubleOrNull(),
                           c.getOrNull(3)?.toDoubleOrNull(),
                           c.getOrNull(4)?.toDoubleOrNull())
        }.sortedBy { it.epochMillis }
        return PressurePlace(real, nombre, muestras)
    }
}

/** Distancia sobre la esfera, en metros. Basta de sobra para "estoy o no en el mismo sitio". */
object GreatCircle {
    private const val RADIO_M = 6_371_000.0

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * RADIO_M * asin(sqrt(a).coerceIn(0.0, 1.0))
    }
}
