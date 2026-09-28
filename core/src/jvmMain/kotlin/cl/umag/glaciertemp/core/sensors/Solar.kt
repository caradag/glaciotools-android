package cl.umag.glaciertemp.core.sensors

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Donde esta el sol, para dibujar su recorrido sobre el horizonte medido.
 *
 * QUE PRECISION HACE FALTA. El horizonte se mide en sectores de 5 grados sosteniendo un
 * telefono con la mano; pedirle al calculo solar mas de una decima de grado seria afinar la
 * unica parte del conjunto que ya sobra. Por eso basta la aproximacion de orbita circular
 * corregida --la que usa el NOAA para su calculadora-- y no hace falta VSOP87.
 *
 * NO SE CORRIGE LA REFRACCION. Cerca del horizonte vale medio grado, que importaria para
 * predecir el ORTO exacto; aqui lo que se compara es contra un horizonte medido a pulso, y
 * media hora de sol rasante no cambia ninguna decision sobre un panel.
 */
object Solar {

    /** El sol visto desde un sitio: de donde viene y cuanto sube. */
    data class Position(val azimuth: Double, val elevation: Double, val epochMillis: Long)

    private const val DIA_MS = 86_400_000.0

    /** Dias julianos desde J2000.0 (2000-01-01 12:00 UTC). */
    private fun diasDesdeJ2000(epochMillis: Long): Double =
        (epochMillis - 946_728_000_000L) / DIA_MS

    fun position(latitude: Double, longitude: Double, epochMillis: Long): Position {
        val n = diasDesdeJ2000(epochMillis)

        // Longitud media y anomalia media del sol.
        val L = Math.toRadians((280.460 + 0.9856474 * n) % 360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        // Longitud eclíptica: la orbita es una elipse, y estos dos terminos son la correccion
        // del centro que la convierte en circular a efectos de la posicion aparente.
        val lambda = L + Math.toRadians(1.915) * sin(g) + Math.toRadians(0.020) * sin(2 * g)
        val eps = Math.toRadians(23.439 - 0.0000004 * n)

        val dec = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))

        // Tiempo sidereo: cuanto ha girado la Tierra. De aqui sale el angulo horario, que es
        // lo unico que cambia a lo largo del dia.
        val gmstH = (18.697374558 + 24.06570982441908 * n) % 24.0
        val lstRad = Math.toRadians(((gmstH + 24.0) % 24.0) * 15.0 + longitude)
        val H = lstRad - ra

        val phi = Math.toRadians(latitude)
        val sinEl = sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(H)
        val el = asin(sinEl.coerceIn(-1.0, 1.0))
        // Azimut desde el NORTE hacia el este. En el hemisferio sur esto pone el sol al norte
        // al mediodia, que es lo correcto y lo que delata si los signos estan mal.
        val az = atan2(-cos(dec) * sin(H),
                       sin(dec) * cos(phi) - cos(dec) * sin(phi) * cos(H))
        return Position(Compass.normalize(Math.toDegrees(az)), Math.toDegrees(el), epochMillis)
    }

    /**
     * El recorrido del sol a lo largo de un dia, de medianoche a medianoche locales.
     *
     * Se devuelven TAMBIEN las posiciones nocturnas (elevacion negativa) y las filtra quien
     * dibuja: separar aqui obligaria a devolver dos cosas, y el limite de "esta de dia" no es
     * el mismo para dibujar que para sumar radiacion.
     */
    fun dayTrack(
        latitude: Double, longitude: Double, date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(), stepMinutes: Int = 5,
    ): List<Position> {
        val inicio = date.atStartOfDay(zone)
        val pasos = 24 * 60 / stepMinutes
        return (0..pasos).map { i ->
            position(latitude, longitude, inicio.plusMinutes((i.toLong() * stepMinutes)).toInstant().toEpochMilli())
        }
    }

    /**
     * Los dos solsticios del ano dado, con el nombre que les toca en ESTE hemisferio.
     *
     * El solsticio de junio es invierno en el sur y verano en el norte. La app se usa en
     * Patagonia, asi que llamar "verano" al de junio --como haria cualquier tabla escrita en
     * el hemisferio norte-- pondria el peor dia del ano donde el usuario espera el mejor.
     */
    fun solstices(year: Int, latitude: Double): Pair<Solstice, Solstice> {
        val junio = LocalDate.of(year, 6, 21)
        val diciembre = LocalDate.of(year, 12, 21)
        val sur = latitude < 0
        val invierno = Solstice(if (sur) junio else diciembre, "Winter solstice")
        val verano = Solstice(if (sur) diciembre else junio, "Summer solstice")
        return invierno to verano
    }

    data class Solstice(val date: LocalDate, val label: String)

    /**
     * Cuanta radiacion directa llega con el sol a esa altura, en fraccion de la constante.
     *
     * MODELO DE MEINEL, el mas simple que no miente demasiado: 0,7 elevado a la masa de aire
     * a la 0,678. Sin el, un sol a 3 grados sobre el horizonte contaria casi tanto como uno
     * en el cenit, y el optimo del panel saldria apuntando al orto.
     *
     * Es CIELO DESPEJADO y solo radiacion directa: no hay difusa ni nubes. Para elegir entre
     * dos inclinaciones de panel en el mismo sitio sirve; como prediccion de energia, no.
     */
    fun beamFraction(elevationDeg: Double): Double {
        if (elevationDeg <= 0.5) return 0.0
        val masaDeAire = 1.0 / sin(Math.toRadians(elevationDeg))
        if (masaDeAire > 40.0) return 0.0
        return 0.7.pow(masaDeAire.pow(0.678))
    }
}
