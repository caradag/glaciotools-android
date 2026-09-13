package cl.umag.glaciertemp.core.geo

import kotlin.math.sqrt

/** Una lectura suelta del receptor. Es el dato crudo: no se promedia ni se filtra nada. */
data class GpsSample(
    val epochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double? = null,
    val accuracyMetres: Double? = null,
)

/**
 * Como se reparte una magnitud a lo largo de las muestras.
 *
 * Lleva TRES numeros y no uno porque responden a preguntas distintas:
 *
 *  - [median] es la estimacion. Mediana y no media porque un GPS suelta de vez en cuando una
 *    posicion muy lejos del resto, y una sola de esas arrastra la media varios metros
 *    mientras que a la mediana no le hace nada.
 *  - [sd] es lo que se dispersan las muestras. NO baja al seguir midiendo; describe al
 *    receptor y al sitio, no a la estimacion.
 *  - [standardError] es lo que baja. Es la incertidumbre de la propia mediana, y es el
 *    numero que de verdad mejora mientras uno deja el aparato quieto.
 *
 * Ensenar solo [sd] es lo que hace que alguien mire la pantalla diez minutos, vea que el
 * numero no baja y concluya que promediar no sirve para nada.
 */
data class Dispersion(
    val n: Int,
    val median: Double,
    val mean: Double,
    val sd: Double,
) {
    /**
     * Incertidumbre de la estimacion: sd partido por la raiz de n.
     *
     * OPTIMISTA a proposito conocido. La formula supone muestras independientes y las
     * posiciones sucesivas de un GPS no lo son ni de lejos -- la misma geometria de
     * satelites y la misma ionosfera sesgan minutos enteros en la misma direccion. El error
     * real baja mas despacio que esto. Sirve para saber CUANDO deja de valer la pena seguir,
     * no para escribir una barra de error en un articulo.
     */
    val standardError: Double get() = if (n > 1) sd / sqrt(n.toDouble()) else sd

    companion object {
        fun of(values: List<Double>): Dispersion {
            if (values.isEmpty()) return Dispersion(0, Double.NaN, Double.NaN, Double.NaN)
            val orden = values.sorted()
            val n = orden.size
            val mediana = if (n % 2 == 1) orden[n / 2]
                          else (orden[n / 2 - 1] + orden[n / 2]) / 2.0
            val media = values.sum() / n
            // Con n-1 y no con n: con una sola muestra no hay dispersion que medir, y
            // dividir por n daria cero, que se lee como "medida perfecta".
            val sd = if (n < 2) 0.0
                     else sqrt(values.sumOf { (it - media) * (it - media) } / (n - 1))
            return Dispersion(n, mediana, media, sd)
        }
    }
}

/** Lo que se sabe de un punto tras promediar. Todo se deriva de las muestras, nada se guarda. */
data class GpsPointStats(
    val samples: Int,
    val zone: Int,
    val north: Boolean,
    val band: Char,
    val easting: Dispersion,
    val northing: Dispersion,
    /** null cuando ninguna muestra trajo altitud: un arreglo 2D no la tiene. */
    val altitude: Dispersion?,
    val medianLatitude: Double,
    val medianLongitude: Double,
    val firstEpochMillis: Long,
    val lastEpochMillis: Long,
) {
    /** La mediana como coordenada UTM, que es como se ensena y como se escribe en la libreta. */
    val medianUtm: UtmCoord
        get() = UtmCoord(zone, north, easting.median, northing.median, band)

    /** Incertidumbre horizontal combinada de la estimacion, en metros. */
    val horizontalStandardError: Double
        get() = sqrt(easting.standardError * easting.standardError +
                     northing.standardError * northing.standardError)

    /** Dispersion horizontal de las muestras, en metros. */
    val horizontalSd: Double
        get() = sqrt(easting.sd * easting.sd + northing.sd * northing.sd)

    val durationSeconds: Long get() = (lastEpochMillis - firstEpochMillis) / 1000
}

/**
 * Acumula muestras de un punto y calcula lo que se ensena.
 *
 * LA ZONA SE FIJA CON LA PRIMERA MUESTRA y no se vuelve a mirar. Promediando junto a un
 * meridiano de zona, el ruido del receptor manda unas muestras a un lado y otras al otro;
 * sus eastings difieren en cientos de kilometros y la nube se parte en dos, con la mediana
 * cayendo en medio. Proyectarlo todo en la zona de la primera muestra mantiene un solo
 * marco, y las muestras de la zona vecina quedan con un easting fuera del rango habitual,
 * que es correcto y es lo que se quiere.
 *
 * No hay filtro de calidad. Descartar muestras por su precision declarada seria decidir por
 * el usuario con un numero que el propio receptor se inventa; la mediana ya se defiende de
 * los saltos, y las muestras quedan todas guardadas para poder rehacer el calculo despues.
 */
class GpsAverager(primera: GpsSample? = null) {

    private val muestras = ArrayList<GpsSample>()
    private val este = ArrayList<Double>()
    private val norte = ArrayList<Double>()

    /** Zona y hemisferio en los que se proyecta TODO, fijados por la primera muestra. */
    var zone: Int = 0; private set
    var north: Boolean = true; private set
    var band: Char = ' '; private set

    /** Cuantas muestras cayeron en una zona que no es la fijada. */
    var outOfZone: Int = 0; private set

    init { primera?.let { add(it) } }

    val size: Int get() = muestras.size
    fun samples(): List<GpsSample> = muestras.toList()

    fun add(s: GpsSample) {
        if (muestras.isEmpty()) {
            zone = Utm.zoneFor(s.latitude, s.longitude)
            north = s.latitude >= 0
            band = Utm.bandFor(s.latitude)
        } else if (Utm.zoneFor(s.latitude, s.longitude) != zone || (s.latitude >= 0) != north) {
            outOfZone++
        }
        val u = Utm.fromLatLon(s.latitude, s.longitude, forceZone = zone)
        // El hemisferio tambien se hereda: una muestra que cruzara el ecuador traeria un
        // northing con diez millones de diferencia, y eso si partiria la nube de verdad.
        val n = if (north == u.north) u.northing
                else if (north) u.northing - 10_000_000.0
                else u.northing + 10_000_000.0
        muestras.add(s)
        este.add(u.easting)
        norte.add(n)
    }

    fun addAll(list: List<GpsSample>) = list.forEach { add(it) }

    fun stats(): GpsPointStats? {
        if (muestras.isEmpty()) return null
        val alturas = muestras.mapNotNull { it.altitudeMetres }
        val e = Dispersion.of(este)
        val n = Dispersion.of(norte)
        val (lat, lon) = Utm.toLatLon(UtmCoord(zone, north, e.median, n.median, band))
        return GpsPointStats(
            samples = muestras.size,
            zone = zone, north = north, band = band,
            easting = e, northing = n,
            altitude = if (alturas.isEmpty()) null else Dispersion.of(alturas),
            medianLatitude = lat, medianLongitude = lon,
            firstEpochMillis = muestras.minOf { it.epochMillis },
            lastEpochMillis = muestras.maxOf { it.epochMillis },
        )
    }

    /** Las muestras proyectadas, para dibujar la nube. Mismo orden que [samples]. */
    fun projected(): List<Pair<Double, Double>> = este.indices.map { este[it] to norte[it] }
}
