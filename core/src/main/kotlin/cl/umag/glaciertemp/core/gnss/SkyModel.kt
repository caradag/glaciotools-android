package cl.umag.glaciertemp.core.gnss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Donde esta un satelite en el cielo de un observador. */
data class SkyPos(val azimuthDeg: Double, val elevationDeg: Double)

/**
 * De elementos orbitales a "donde esta en el cielo", con la precision que hace falta y no mas.
 *
 * POR QUE NO SGP4 COMPLETO. SGP4 son unas cuatrocientas lineas de correcciones de perturbacion
 * para dar kilometros de precision. Aqui la tolerancia es de 2 grados, y 2 grados a la
 * distancia de un satelite GNSS son SETECIENTOS KILOMETROS:
 *
 *     20 200 km x tan(2 grados) = 705 km   (satelite en el cenit)
 *     25 800 km x tan(2 grados) = 901 km   (en el horizonte)
 *
 * Con ese margen basta con Kepler mas los terminos seculares de J2 --el achatamiento de la
 * Tierra, que es la unica perturbacion que importa a un dia vista para una orbita media--.
 * Son ochenta lineas en vez de cuatrocientas, y el error que deja es de kilometros, dos
 * ordenes de magnitud por debajo de lo que se puede tolerar.
 *
 * Y lo que se pide de verdad es todavia mas tolerante: cuantos satelites hay a cada hora.
 * Un satelite se mueve por el cielo a medio grado por minuto, asi que 2 grados de error son
 * CUATRO MINUTOS en la hora a la que sale o se pone. Para elegir en que rato medir, ruido.
 */
object SkyModel {

    /**
     * Posicion del satelite en coordenadas fijas a la Tierra (ECEF), en km.
     *
     * Kepler propagado desde la epoca del TLE, con las derivas seculares de J2 sobre el nodo
     * ascendente, el argumento del perigeo y la anomalia media.
     */
    fun ecef(tle: Tle, atMillis: Long): Triple<Double, Double, Double> {
        val dt = (atMillis - tle.epochMillis) / 1000.0        // segundos desde la epoca
        val n0 = tle.meanMotionRadPerSec
        val a = tle.semiMajorAxisKm
        val e = tle.eccentricity
        val i = tle.inclinationRad
        val p = a * (1.0 - e * e)
        val f = 1.5 * Tle.J2 * (Tle.RE_KM / p) * (Tle.RE_KM / p) * n0
        val cosI = cos(i)

        val raan = tle.raanRad - f * cosI * dt
        val argP = tle.argPerigeeRad + 0.5 * f * (5.0 * cosI * cosI - 1.0) * dt
        // J2 tambien corrige el movimiento medio. Es una correccion pequena --partes por
        // diez mil-- pero es SECULAR: a un dia vista son varios kilometros a lo largo de la
        // orbita, y no cuesta nada incluirla.
        val nBar = n0 * (1.0 + (f / n0) * sqrt(1.0 - e * e) * (1.0 - 1.5 * sin(i) * sin(i)))
        val m = tle.meanAnomalyRad + nBar * dt

        // Kepler por Newton. Con e < 0.02 --toda orbita GNSS-- converge en tres o cuatro vueltas.
        var ea = m
        repeat(12) {
            val d = (ea - e * sin(ea) - m) / (1.0 - e * cos(ea))
            ea -= d
            if (abs(d) < 1e-12) return@repeat
        }

        val xOrb = a * (cos(ea) - e)
        val yOrb = a * sqrt(1.0 - e * e) * sin(ea)

        // Plano orbital -> inercial (ECI)
        val cw = cos(argP); val sw = sin(argP)
        val co = cos(raan); val so = sin(raan)
        val ci = cosI; val si = sin(i)
        val xp = xOrb * cw - yOrb * sw
        val yp = xOrb * sw + yOrb * cw
        val xEci = xp * co - yp * ci * so
        val yEci = xp * so + yp * ci * co
        val zEci = yp * si

        // ECI -> ECEF girando el angulo sidereo.
        val g = gmstRad(atMillis)
        return Triple(xEci * cos(g) + yEci * sin(g), -xEci * sin(g) + yEci * cos(g), zEci)
    }

    /** Donde lo ve un observador en la superficie. */
    fun skyPos(tle: Tle, atMillis: Long, latDeg: Double, lonDeg: Double, altM: Double = 0.0): SkyPos {
        val (xs, ys, zs) = ecef(tle, atMillis)
        val lat = latDeg * PI / 180.0
        val lon = lonDeg * PI / 180.0

        // Observador en ECEF, elipsoide WGS84.
        val f = 1.0 / 298.257223563
        val ec2 = f * (2.0 - f)
        val nPhi = Tle.RE_KM / sqrt(1.0 - ec2 * sin(lat) * sin(lat))
        val h = altM / 1000.0
        val xo = (nPhi + h) * cos(lat) * cos(lon)
        val yo = (nPhi + h) * cos(lat) * sin(lon)
        val zo = (nPhi * (1.0 - ec2) + h) * sin(lat)

        // Vector observador -> satelite, girado al sistema local Este/Norte/Arriba.
        val dx = xs - xo; val dy = ys - yo; val dz = zs - zo
        val este  = -sin(lon) * dx + cos(lon) * dy
        val norte = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val arriba = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz

        val rango = sqrt(dx * dx + dy * dy + dz * dz)
        var az = atan2(este, norte) * 180.0 / PI
        if (az < 0) az += 360.0
        return SkyPos(az, asin(arriba / rango) * 180.0 / PI)
    }

    /** Cuantos satelites de cada constelacion estan por encima de la mascara de elevacion. */
    fun visibleCounts(tles: List<Tle>, atMillis: Long, latDeg: Double, lonDeg: Double,
                      maskDeg: Double = 10.0): Map<Constellation, Int> =
        tles.groupBy { it.constellation }
            .mapValues { (_, l) ->
                l.count { skyPos(it, atMillis, latDeg, lonDeg).elevationDeg >= maskDeg }
            }

    /**
     * Tiempo sidereo medio de Greenwich, en radianes.
     *
     * Es lo unico que convierte "donde esta el satelite" en "donde lo veo yo": el satelite se
     * mueve en un marco inercial y el observador gira con la Tierra debajo. Un error de un
     * minuto aqui son 15 minutos de arco de error en el azimut.
     */
    internal fun gmstRad(millis: Long): Double {
        val jd = millis / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        var s = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
                0.000387933 * t * t - t * t * t / 38_710_000.0
        s %= 360.0
        if (s < 0) s += 360.0
        return s * PI / 180.0
    }
}
