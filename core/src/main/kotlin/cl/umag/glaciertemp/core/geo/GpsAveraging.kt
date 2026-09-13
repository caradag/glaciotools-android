package cl.umag.glaciertemp.core.geo

import kotlin.math.sqrt

/**
 * Una lectura suelta del receptor. Es el dato crudo: no se promedia ni se filtra nada.
 *
 * [sessionStartMillis] dice a que TRAMO CONTINUO de medida pertenece, y se identifica por el
 * instante en que empezo ese tramo en vez de por un numero de orden. Un instante significa
 * lo mismo se lea el fichero cuando se lea y se mezcle con lo que se mezcle; un indice
 * depende de cuantos tramos hubiera antes, y al anadir muestras a un fichero ya escrito
 * habria que saber por cual iba.
 *
 * El tramo importa porque dos muestras del mismo tramo se parecen mucho mas entre si que dos
 * de tramos distintos, y de eso depende cuanta informacion hay de verdad. Ver [WeightedEstimate].
 */
data class GpsSample(
    val epochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double? = null,
    /** Radio de confianza del 68 % en horizontal, tal como lo declara el receptor. */
    val accuracyMetres: Double? = null,
    /** Lo mismo en vertical. Es peor que el horizontal, tipicamente por un factor de dos. */
    val verticalAccuracyMetres: Double? = null,
    val sessionStartMillis: Long = 0L,
)

/** Lo que se sabe de un punto tras promediar. Todo se deriva de las muestras, nada se guarda. */
data class GpsPointStats(
    val samples: Int,
    val sessions: Int,
    val zone: Int,
    val north: Boolean,
    val band: Char,
    val easting: Axis,
    val northing: Axis,
    /** null cuando ninguna muestra trajo altitud: un arreglo 2D no la tiene. */
    val altitude: Axis?,
    val estimateLatitude: Double,
    val estimateLongitude: Double,
    val firstEpochMillis: Long,
    val lastEpochMillis: Long,
) {
    /** La estimacion como coordenada UTM, que es como se escribe en la libreta. */
    val estimateUtm: UtmCoord
        get() = UtmCoord(zone, north, easting.estimate, northing.estimate, band)

    /** Incertidumbre horizontal combinada de la estimacion, en metros. */
    val horizontalStandardError: Double
        get() = sqrt(easting.standardError * easting.standardError +
                     northing.standardError * northing.standardError)

    /** Dispersion horizontal de las muestras, en metros. */
    val horizontalSd: Double
        get() = sqrt(easting.sd * easting.sd + northing.sd * northing.sd)

    /** Cuantas muestras se descartaron por apartarse demasiado del resto. */
    val rejected: Int get() = maxOf(easting.rejected, northing.rejected)

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

    /**
     * El tramo al que se apuntan las muestras nuevas.
     *
     * Cada vez que se empieza o se reanuda la recogida se abre uno: entre pulsar Pausa y
     * pulsar Reanudar pasa tiempo, y ese tiempo decorrelaciona. Tratar los dos lados de la
     * pausa como un solo tramo prometeria menos incertidumbre de la que hay.
     */
    var currentSession: Long = 0L; private set

    fun startSession(atMillis: Long = System.currentTimeMillis()) { currentSession = atMillis }

    /** Zona y hemisferio en los que se proyecta TODO, fijados por la primera muestra. */
    var zone: Int = 0; private set
    var north: Boolean = true; private set
    var band: Char = ' '; private set

    /** Cuantas muestras cayeron en una zona que no es la fijada. */
    var outOfZone: Int = 0; private set

    init { primera?.let { add(it) } }

    val size: Int get() = muestras.size
    fun samples(): List<GpsSample> = muestras.toList()

    fun add(raw: GpsSample) {
        // La muestra se apunta al tramo abierto salvo que ya venga con uno --que es lo que
        // pasa al cargar de disco, donde el tramo ya esta decidido.
        val s = if (raw.sessionStartMillis != 0L) raw
                else raw.copy(sessionStartMillis =
                    if (currentSession != 0L) currentSession else raw.epochMillis)
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
        // Los tramos se numeran de forma densa a partir de sus instantes de inicio: al
        // estimador le basta con saber cuales van juntas, no cuando fue cada una.
        val orden = muestras.map { it.sessionStartMillis }.distinct().sorted()
        val tramo = muestras.map { orden.indexOf(it.sessionStartMillis) }

        val e = WeightedEstimate.of(este, muestras.map { it.accuracyMetres }, tramo)
        val n = WeightedEstimate.of(norte, muestras.map { it.accuracyMetres }, tramo)
        if (e == null || n == null) return null

        // La altitud se pondera con SU propia precision, no con la horizontal: un GNSS la
        // estima peor, tipicamente por un factor de dos, y usar el numero horizontal
        // afirmaria una calidad vertical que el receptor no ha declarado.
        val conAltura = muestras.indices.filter { muestras[it].altitudeMetres != null }
        val alt = if (conAltura.isEmpty()) null else WeightedEstimate.of(
            conAltura.map { muestras[it].altitudeMetres!! },
            conAltura.map { muestras[it].verticalAccuracyMetres },
            conAltura.map { tramo[it] })

        val (lat, lon) = Utm.toLatLon(UtmCoord(zone, north, e.estimate, n.estimate, band))
        return GpsPointStats(
            samples = muestras.size,
            sessions = orden.size,
            zone = zone, north = north, band = band,
            easting = e, northing = n, altitude = alt,
            estimateLatitude = lat, estimateLongitude = lon,
            firstEpochMillis = muestras.minOf { it.epochMillis },
            lastEpochMillis = muestras.maxOf { it.epochMillis },
        )
    }

    /** Las muestras proyectadas, para dibujar la nube. Mismo orden que [samples]. */
    fun projected(): List<Pair<Double, Double>> = este.indices.map { este[it] to norte[it] }
}
