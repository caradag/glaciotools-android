package cl.umag.glaciertemp.core.gnss

import kotlin.math.PI
import kotlin.math.pow

/** A que sistema pertenece un satelite. Una linea por constelacion en la grafica. */
enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU }

/**
 * Un juego de elementos orbitales de dos lineas (TLE).
 *
 * POR QUE TLE Y NO EL ALMANAQUE DE GPS. El almanaque nativo solo cubre GPS, y la herramienta
 * tiene que contar las cuatro constelaciones. Los TLE de CelesTrak las traen todas en el
 * mismo formato y en 24 KB, que es una descarga que cabe en cualquier sitio.
 *
 * LA CONSTELACION NO SE DEDUCE DEL NOMBRE. Se descarga un fichero por constelacion y cada
 * uno se etiqueta entero. Parece un rodeo --son cuatro peticiones en vez de una-- pero el
 * grupo mezclado trae tambien SBAS y sistemas regionales, y ahi "GSAT-8" es GAGAN, un SBAS
 * indio, mientras que "GSAT0101" si es Galileo. Una regla sobre el nombre que acierte con
 * los dos es una regla que se rompera cuando lancen el siguiente.
 */
data class Tle(
    val name: String,
    val constellation: Constellation,
    /** Epoca de los elementos, en milisegundos desde 1970 UTC. */
    val epochMillis: Long,
    val inclinationRad: Double,
    val raanRad: Double,
    val eccentricity: Double,
    val argPerigeeRad: Double,
    val meanAnomalyRad: Double,
    /** Movimiento medio, revoluciones por dia. */
    val meanMotionRevPerDay: Double,
) {
    /**
     * El numero con el que el receptor identifica este satelite, si se puede saber.
     *
     * SOLO GPS Y BEIDOU. Sus nombres traen "(PRN 22)" y "(C06)", que son exactamente lo que
     * GnssStatus devuelve como svid. Galileo trae "GSAT0101", que es el numero de serie de
     * la nave y NO el numero E que emite; GLONASS trae "(720)", que es el numero GLONASS y
     * no la ranura orbital. Emparejar esos dos exigiria una tabla externa que envejece.
     *
     * Se devuelve null antes que adivinar: un emparejamiento equivocado convertiria la
     * comprobacion contra el cielo real --que existe para detectar errores-- en una fuente
     * de errores propia.
     */
    val svid: Int?
        get() = when (constellation) {
            Constellation.GPS -> Regex("""PRN\s*(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
            Constellation.BEIDOU -> Regex("""\(C(\d+)\)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
            else -> null
        }

    /** Movimiento medio en radianes por segundo. */
    val meanMotionRadPerSec: Double get() = meanMotionRevPerDay * 2.0 * PI / 86400.0

    /** Semieje mayor en km, de la tercera ley de Kepler. */
    val semiMajorAxisKm: Double
        get() = (MU_EARTH / (meanMotionRadPerSec * meanMotionRadPerSec)).pow(1.0 / 3.0)

    companion object {
        const val MU_EARTH = 398_600.4418      // km^3/s^2
        const val RE_KM = 6378.137             // radio ecuatorial WGS84
        const val J2 = 1.082_626_68e-3

        /**
         * Analiza un fichero TLE de tres lineas por satelite.
         *
         * Tolerante a proposito: una linea ilegible se salta en vez de tirar el fichero
         * entero. Un satelite de menos no cambia la grafica; quedarse sin almanaque en
         * terreno por un caracter raro, si.
         */
        fun parse(texto: String, constellation: Constellation): List<Tle> {
            val lineas = texto.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
            val salida = mutableListOf<Tle>()
            var i = 0
            while (i + 2 < lineas.size + 1) {
                if (i + 2 >= lineas.size) break
                val nombre = lineas[i].trim()
                val l1 = lineas[i + 1]
                val l2 = lineas[i + 2]
                i += 3
                if (!l1.startsWith("1 ") || !l2.startsWith("2 ")) continue
                runCatching { desdeLineas(nombre, constellation, l1, l2) }
                    .getOrNull()?.let { salida += it }
            }
            return salida
        }

        private fun desdeLineas(nombre: String, c: Constellation, l1: String, l2: String): Tle {
            // Columnas fijas, contadas desde 1 como en la especificacion del formato.
            fun t(l: String, desde: Int, hasta: Int) = l.substring(desde - 1, hasta).trim()

            val epoca = t(l1, 19, 32).toDouble()
            val aa = (epoca / 1000.0).toInt()
            val anio = if (aa < 57) 2000 + aa else 1900 + aa
            val diaDelAnio = epoca - aa * 1000.0

            // Excentricidad: punto decimal implicito delante de los siete digitos.
            val exc = ("0." + t(l2, 27, 33)).toDouble()

            return Tle(
                name = nombre,
                constellation = c,
                epochMillis = epocaAMillis(anio, diaDelAnio),
                inclinationRad = t(l2, 9, 16).toDouble() * PI / 180.0,
                raanRad = t(l2, 18, 25).toDouble() * PI / 180.0,
                eccentricity = exc,
                argPerigeeRad = t(l2, 35, 42).toDouble() * PI / 180.0,
                meanAnomalyRad = t(l2, 44, 51).toDouble() * PI / 180.0,
                meanMotionRevPerDay = t(l2, 53, 63).toDouble(),
            )
        }

        /** Dia fraccionario del anio -> milisegundos UTC. El dia 1.0 es el 1 de enero a las 00:00. */
        internal fun epocaAMillis(anio: Int, diaDelAnio: Double): Long {
            val eneroUno = java.time.LocalDate.of(anio, 1, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            return eneroUno + ((diaDelAnio - 1.0) * 86_400_000.0).toLong()
        }
    }
}
