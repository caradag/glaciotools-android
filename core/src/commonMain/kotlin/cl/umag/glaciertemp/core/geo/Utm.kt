package cl.umag.glaciertemp.core.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Coordenada UTM sobre WGS84.
 *
 * Se lleva la zona y el hemisferio DENTRO del dato, no aparte, porque un par de metros sin
 * zona no identifica ningun sitio: el mismo easting y northing existen en las sesenta zonas.
 */
data class UtmCoord(
    val zone: Int,
    val north: Boolean,
    val easting: Double,
    val northing: Double,
    /** Banda de latitud MGRS, la letra con la que se suele escribir la zona. */
    val band: Char,
) {
    /** "19F 371837 4107791", que es como se escribe en una libreta de terreno. */
    fun format(decimals: Int = 0): String =
        "$zone$band ${"%.${decimals}f".format(easting)} ${"%.${decimals}f".format(northing)}"
}

/**
 * Proyeccion UTM directa, serie de Snyder sobre el elipsoide WGS84.
 *
 * Por que a mano y no con una libreria: el modulo `core` es Java puro y sin dependencias a
 * proposito --es lo que permite probarlo entero en el escritorio-- y una libreria de
 * proyecciones entera para una sola formula pesa mas que la formula. La serie de Snyder da
 * milimetros dentro de su zona, que es varios ordenes de magnitud mas fino que el ruido de
 * un GPS de telefono.
 *
 * Los valores se contrastan contra PROJ en [UtmTest]; no es una implementacion escrita de
 * memoria y dada por buena.
 */
object Utm {

    private const val A = 6378137.0                      // semieje mayor WGS84
    private const val F = 1.0 / 298.257223563            // achatamiento
    private const val K0 = 0.9996                        // factor de escala UTM
    private val E2 = F * (2 - F)                         // primera excentricidad al cuadrado
    private val EP2 = E2 / (1 - E2)                      // segunda excentricidad al cuadrado

    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING_SOUTH = 10_000_000.0

    /** Las bandas van de 80S a 84N en franjas de 8 grados; se saltan la I y la O. */
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    /**
     * La zona que le corresponde a una posicion, con las dos excepciones que existen.
     *
     * No son un adorno historico: en el suroeste de Noruega la zona 32 se ensancha, y en
     * Svalbard hay cuatro zonas de anchura irregular. Una implementacion que las ignore da
     * coordenadas que no cuadran con ninguna carta de la zona, y el error es de cientos de
     * kilometros, no de metros.
     */
    fun zoneFor(latitude: Double, longitude: Double): Int {
        val lon = normalizeLongitude(longitude)
        if (latitude in 56.0..<64.0 && lon in 3.0..<12.0) return 32
        if (latitude in 72.0..<84.0) {
            when {
                lon in 0.0..<9.0 -> return 31
                lon in 9.0..<21.0 -> return 33
                lon in 21.0..<33.0 -> return 35
                lon in 33.0..<42.0 -> return 37
            }
        }
        return (floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
    }

    fun bandFor(latitude: Double): Char {
        if (latitude < -80.0) return 'A'      // zonas polares: UPS, no UTM
        if (latitude >= 84.0) return 'Y'
        val i = ((latitude + 80.0) / 8.0).toInt().coerceIn(0, BANDS.length - 1)
        return BANDS[i]
    }

    fun normalizeLongitude(longitude: Double): Double {
        var lon = longitude
        while (lon < -180.0) lon += 360.0
        while (lon >= 180.0) lon -= 360.0
        return lon
    }

    /**
     * Proyecta a UTM. Con [forceZone] se proyecta en una zona que no es la natural.
     *
     * Eso ultimo NO es un capricho. Al promediar un punto cerca de un meridiano de zona, el
     * ruido del GPS hace que unas muestras caigan a un lado y otras al otro, y sus eastings
     * difieren en cientos de kilometros. Fijar la zona con la primera muestra mantiene toda
     * la nube en un solo marco; dejarla flotar partiria la nube en dos y la mediana caeria
     * en medio del oceano.
     */
    fun fromLatLon(latitude: Double, longitude: Double, forceZone: Int? = null): UtmCoord {
        val lon = normalizeLongitude(longitude)
        val zone = forceZone ?: zoneFor(latitude, lon)
        val lon0 = (zone - 1) * 6.0 - 180.0 + 3.0

        val phi = Math.toRadians(latitude)
        val dLon = Math.toRadians(normalizeLongitude(lon - lon0))
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val n = A / sqrt(1 - E2 * sinPhi * sinPhi)
        val t = tanPhi * tanPhi
        val c = EP2 * cosPhi * cosPhi
        val a1 = dLon * cosPhi

        val m = A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi -
                     (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * phi) +
                     (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * phi) -
                     (35 * E2 * E2 * E2 / 3072) * sin(6 * phi))

        val a2 = a1 * a1
        val easting = K0 * n * (a1 +
            (1 - t + c) * a2 * a1 / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a2 * a2 * a1 / 120) + FALSE_EASTING

        var northing = K0 * (m + n * tanPhi * (a2 / 2 +
            (5 - t + 9 * c + 4 * c * c) * a2 * a2 / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a2 * a2 * a2 / 720))

        val north = latitude >= 0
        if (!north) northing += FALSE_NORTHING_SOUTH

        return UtmCoord(zone, north, easting, northing, bandFor(latitude))
    }

    /**
     * Vuelta de UTM a latitud y longitud, la serie inversa de Snyder.
     *
     * Hace falta porque la posicion que se ensena y se guarda es la MEDIANA de la nube, y esa
     * mediana se calcula en easting y northing --que es donde se ve la nube y donde tienen
     * sentido los metros--. Para escribir un GPX hay que volver a grados, y volver desde la
     * mediana proyectada y no desde una mediana de latitudes es lo que hace que el fichero
     * exportado describa exactamente el punto que la pantalla estaba ensenando.
     */
    fun toLatLon(coord: UtmCoord): Pair<Double, Double> {
        val x = coord.easting - FALSE_EASTING
        val y = if (coord.north) coord.northing else coord.northing - FALSE_NORTHING_SOUTH
        val lon0 = (coord.zone - 1) * 6.0 - 180.0 + 3.0

        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256))

        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu) +
            (1097 * e1 * e1 * e1 * e1 / 512) * sin(8 * mu)

        val sinP = sin(phi1)
        val cosP = cos(phi1)
        val tanP = tan(phi1)
        val c1 = EP2 * cosP * cosP
        val t1 = tanP * tanP
        val n1 = A / sqrt(1 - E2 * sinP * sinP)
        val r1 = A * (1 - E2) / Math.pow(1 - E2 * sinP * sinP, 1.5)
        val d = x / (n1 * K0)
        val d2 = d * d

        val lat = phi1 - (n1 * tanP / r1) * (d2 / 2 -
            (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d2 * d2 / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) *
                d2 * d2 * d2 / 720)

        val lon = Math.toRadians(lon0) + (d -
            (1 + 2 * t1 + c1) * d2 * d / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) *
                d2 * d2 * d / 120) / cosP

        return Math.toDegrees(lat) to normalizeLongitude(Math.toDegrees(lon))
    }

    /**
     * Si dos posiciones caen en zonas distintas. Lo consulta quien promedia para avisar de
     * que las muestras se estan proyectando en una zona prestada.
     */
    fun sameZone(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
        zoneFor(aLat, aLon) == zoneFor(bLat, bLon) && (aLat >= 0) == (bLat >= 0)

    /** Distancia plana entre dos UTM de la MISMA zona, en metros. */
    fun planarDistance(a: UtmCoord, b: UtmCoord): Double {
        require(a.zone == b.zone && a.north == b.north) {
            "distancia entre zonas UTM distintas: ${a.zone}${if (a.north) "N" else "S"} " +
            "y ${b.zone}${if (b.north) "N" else "S"}"
        }
        val de = a.easting - b.easting
        val dn = a.northing - b.northing
        return sqrt(de * de + dn * dn)
    }

    /** Para los mensajes: cuanto se aparta un valor de otro, sin signo. */
    internal fun spread(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else abs(values.max() - values.min())
}
