package cl.umag.glaciertemp.core

import java.time.Duration
import java.time.LocalDateTime

/**
 * De donde salio la hora contra la que se midio el reloj de la placa.
 *
 * La distincion no es cosmetica: el reloj del telefono puede ir tan mal como el de la placa
 * si lleva semanas sin red, y entonces la correccion de [TimeCorrection] arrastraria el
 * error en vez de quitarlo. Queda escrito en el CSV para que un dato corregido contra una
 * referencia dudosa se pueda identificar despues.
 */
enum class ClockReference(val label: String) {
    GPS("GPS"),
    PHONE("phone"),
}

/**
 * Una posicion con lo que hace falta para juzgarla.
 *
 * [ageSeconds] es lo que decide si sirve: la posicion de una descarga es en la practica la
 * del sitio, que no se mueve, asi que un arreglo de hace diez minutos alli mismo vale igual
 * de bien. Uno de esa manana en el alojamiento, no -- y sin la antiguedad los dos son
 * indistinguibles.
 */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    // A partir de aqui, SOLO por nombre. Insertar un campo en medio de una lista de Double
    // cambia en silencio el significado de cualquier llamada posicional: al anadir la
    // altitud, `GeoFix(lat, lon, 12.0, 5)` paso a decir "altitud 12 m" en vez de
    // "exactitud 12 m", y solo se detecto porque el cuarto argumento era un Int.
    // Kotlin no tiene forma de exigirlo, asi que queda escrito aqui.
    /**
     * Altitud sobre el elipsoide WGS84 en metros, o null si el arreglo no la trae.
     *
     * Null y no cero: en un glaciar, cero es una altitud PLAUSIBLE y un dato ausente
     * disfrazado de medida es peor que un hueco. Un arreglo de red casi nunca la da, y uno
     * de GPS con pocos satelites tampoco.
     *
     * Es altura sobre el ELIPSOIDE y no sobre el nivel del mar: el GPS mide contra el
     * elipsoide, y la diferencia con el geoide llega a decenas de metros. Se anota tal cual
     * para que quien la use sepa contra que esta referida y pueda convertirla si le hace
     * falta.
     */
    val altitudeMetres: Double? = null,
    val accuracyMetres: Double? = null,
    val ageSeconds: Long = 0,
    /**
     * Segundos que el reloj del SATELITE va por delante del del telefono, medidos al recibir
     * el arreglo. Solo tiene valor en un arreglo fresco del proveedor GPS, cuyo campo de
     * tiempo viene de los satelites y no del telefono; en cualquier otro caso es null.
     *
     * Es lo que permite usar la hora GPS como referencia del desfase de la placa: un
     * telefono que lleva semanas sin red puede ir tan mal como la placa, y corregir contra
     * el arrastraria el error en vez de quitarlo.
     */
    val clockSkewSeconds: Long? = null,
)

/**
 * Lo que se sabe de UNA descarga: cuando se hizo, de que placa, contra que reloj y desde
 * donde. Viaja a la cabecera del CSV y alimenta la correccion de marcas de tiempo.
 *
 * Vive en `:core` y no en `:app` porque de el depende el CSV, que se prueba sin Android.
 */
data class DownloadMetadata(
    val downloadedAt: LocalDateTime,
    /** El corto, con la forma GT001-XXXXXX. */
    val boardId: String? = null,
    /** Los 64 bits de fabrica: la identidad canonica, la que desempata. */
    val boardFullId: String? = null,
    val boardTime: LocalDateTime? = null,
    val referenceTime: LocalDateTime? = null,
    val reference: ClockReference? = null,
    val position: GeoFix? = null,
    /** Por que no hay posicion, cuando no la hay. Un hueco explicado vale mas que un hueco. */
    val positionNote: String? = null,
    /** Nota libre que el usuario escribe al exportar. */
    val note: String? = null,
) {
    /**
     * Segundos que la placa iba adelantada (positivo) o atrasada (negativo) respecto a la
     * referencia, en el momento de empezar la descarga. Null si falta alguno de los dos.
     */
    val offsetSeconds: Long?
        get() {
            val b = boardTime ?: return null
            val r = referenceTime ?: return null
            return Duration.between(r, b).seconds
        }

    /**
     * La posicion en una linea, y la misma que va al CSV.
     *
     * Se formatea AQUI y no en la pantalla ni en el exportador para que las dos vistas no
     * puedan divergir: lo que se lee en las estadisticas es exactamente lo que quedara
     * escrito en la cabecera del fichero, y si alguna vez se anade un campo aparece en los
     * dos sitios sin tener que acordarse del segundo.
     */
    fun positionDescription(): String? {
        val p = position ?: return null
        return "%.5f, %.5f".format(p.latitude, p.longitude)
    }

    /** Altitud, exactitud y antiguedad: lo que permite juzgar si la posicion sirve. */
    fun positionDetail(): String? {
        val p = position ?: return null
        val partes = ArrayList<String>()
        p.altitudeMetres?.let { partes += "%.0f m (WGS84)".format(it) }
        p.accuracyMetres?.let { partes += "accuracy %.0f m".format(it) }
        partes += "fix ${BoardClock.format(p.ageSeconds)} old"
        return partes.joinToString("  ·  ")
    }

    /** El desfase en palabras, porque un "+37 s" a secas se interpreta al reves. */
    fun offsetDescription(): String? {
        val s = offsetSeconds ?: return null
        val sign = if (s >= 0) "+" else "-"
        val sense = when {
            s > 0 -> " (board ahead)"
            s < 0 -> " (board behind)"
            else -> ""
        }
        return "$sign${BoardClock.format(s)}$sense"
    }
}

/**
 * Correccion lineal de las marcas de tiempo a partir del desfase medido al descargar.
 *
 * Se supone que el desfase se fue acumulando de forma constante desde el primer registro:
 * la correccion vale cero en la primera muestra y crece hasta el desfase completo en el
 * instante de la descarga.
 *
 *     t_corr(i) = t(i) - offset * (t(i) - t(0)) / (t_descarga - t(0))
 *
 * La suposicion de deriva constante no tiene por que cumplirse -- un DS3231 deriva sobre
 * todo con la temperatura, y estas placas viven justamente donde la temperatura cambia --
 * pero reduce la incongruencia acumulada. Lo que hace la aproximacion aceptable es que la
 * columna original se conserva intacta: nadie pierde el dato de partida.
 */
object TimeCorrection {

    /** Nombre de la columna. Fuera de aqui nadie deberia escribirlo a mano. */
    const val COLUMN = "Time_corrected"

    /**
     * Las marcas corregidas, o null si no hay nada que corregir. Los tres casos en que se
     * devuelve null se resuelven aqui y no en quien llama, para que no haya dos criterios:
     * menos de dos registros, desfase nulo o cero, y una descarga que no es posterior al
     * primer registro -- que es dato incoherente, no una correccion de cero.
     */
    fun corrected(records: List<Record>, meta: DownloadMetadata?): List<LocalDateTime>? {
        val f = corrector(records, meta) ?: return null
        return records.map(f)
    }

    /**
     * La correccion como FUNCION de un registro, o null si no hay nada que corregir.
     *
     * Depende solo del primer registro, del instante de la descarga y del desfase: tres
     * valores fijos. Devolverla como funcion permite aplicarla a las doce filas de la vista
     * previa en vez de a los cien mil registros del log, que es lo que hacia que el teclado
     * tardara segundos en responder al escribir la nota.
     */
    fun corrector(records: List<Record>, meta: DownloadMetadata?): ((Record) -> LocalDateTime)? {
        if (meta == null || records.size < 2) return null
        val offset = meta.offsetSeconds ?: return null
        if (offset == 0L) return null

        val t0 = records.first().time
        val span = Duration.between(t0, meta.downloadedAt).seconds
        if (span <= 0) return null

        return { r ->
            val elapsed = Duration.between(t0, r.time).seconds
            // Redondeo al segundo mas cercano y no truncado: truncar sesga la correccion
            // entera hacia cero, que es justo el error que se trata de quitar.
            r.time.minusSeconds(Math.round(offset.toDouble() * elapsed / span))
        }
    }

    /** Si la casilla de la columna corregida se puede ofrecer con estos datos. */
    fun isAvailable(records: List<Record>, meta: DownloadMetadata?): Boolean =
        corrector(records, meta) != null
}
