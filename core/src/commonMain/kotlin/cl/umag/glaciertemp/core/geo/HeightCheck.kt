package cl.umag.glaciertemp.core.geo

import kotlin.math.abs

/**
 * Lo que interesa de una sentencia NMEA GGA: la altura sobre el nivel del mar que calculo el
 * chip GNSS y la separacion geoidal (N) que uso para obtenerla.
 */
data class Gga(
    /** Segundos desde medianoche UTC. */
    val timeOfDaySeconds: Double,
    val mslAltitude: Double,
    val geoidSeparation: Double,
    val fixQuality: Int,
)

object Nmea {
    /**
     * Lee una GGA de cualquier constelacion (GP, GN, GL, GA, BD...). Null si no es GGA, si el
     * checksum no cuadra o si falta la altura o la separacion: sin ambas no hay nada que
     * comparar.
     */
    fun parseGga(sentence: String): Gga? {
        val s = sentence.trim()
        if (!s.startsWith("$") || s.length < 7 || s.substring(3, 6) != "GGA") return null
        val estrella = s.lastIndexOf('*')
        val cuerpo = if (estrella > 0) s.substring(1, estrella) else s.substring(1)
        if (estrella > 0) {
            val dado = s.substring(estrella + 1).take(2).toIntOrNull(16) ?: return null
            var x = 0
            for (c in cuerpo) x = x xor c.code
            if (x != dado) return null
        }
        val f = cuerpo.split(',')
        if (f.size < 12) return null
        val hora = f[1]
        if (hora.length < 6) return null
        val t = (hora.substring(0, 2).toIntOrNull() ?: return null) * 3600 +
                (hora.substring(2, 4).toIntOrNull() ?: return null) * 60 +
                (hora.substring(4).toDoubleOrNull() ?: return null)
        val calidad = f[6].toIntOrNull() ?: return null
        if (calidad == 0) return null
        val alt = f[9].toDoubleOrNull() ?: return null
        val sep = f[11].toDoubleOrNull() ?: return null
        return Gga(t, alt, sep, calidad)
    }
}

enum class HeightVerdict { ELLIPSOIDAL, MSL, UNVERIFIED }

/**
 * Si `Location.getAltitude()` de este telefono es altura elipsoidal o sobre el nivel del mar.
 *
 * Android la especifica elipsoidal, pero hay chips que entregan el MSL por esa misma via, y
 * la plataforma no lo comprueba. La prueba: el propio chip dice en la GGA la altura MSL que
 * calculo y la separacion N que uso. Si la altura de Location coincide con MSL + N, es
 * elipsoidal; si coincide con MSL, es nivel del mar. Solo discrimina donde |N| es grande
 * (en Patagonia N = 9-25 m); con |N| < 2 m el par no cuenta.
 *
 * SE EXIGEN [required] PARES QUE COINCIDAN Y NINGUNO QUE CONTRADIGA: un solo par puede venir
 * de emparejar mal la hora de una GGA con la de una posicion. Una contradiccion anula el
 * veredicto: es preferible "no verificado" a afirmar algo falso sobre todas las alturas.
 */
class HeightCheck(private val required: Int = 10) {
    var ellipsoidalVotes = 0
        private set
    var mslVotes = 0
        private set
    /** La ultima separacion vista: para corregir un telefono que entrega MSL. */
    var lastSeparation: Double? = null
        private set

    val verdict: HeightVerdict
        get() = when {
            ellipsoidalVotes >= required && mslVotes == 0 -> HeightVerdict.ELLIPSOIDAL
            mslVotes >= required && ellipsoidalVotes == 0 -> HeightVerdict.MSL
            else -> HeightVerdict.UNVERIFIED
        }

    /** Hay votos de los dos lados: el telefono no se comporta de forma coherente. */
    val contradictory: Boolean get() = ellipsoidalVotes > 0 && mslVotes > 0

    /** Un par (altura de Location, GGA de la misma epoca). Devuelve el veredicto actual. */
    fun offer(locationAltitude: Double, gga: Gga): HeightVerdict {
        lastSeparation = gga.geoidSeparation
        if (abs(gga.geoidSeparation) >= 2.0) {
            val aElipsoidal = abs(locationAltitude - (gga.mslAltitude + gga.geoidSeparation))
            val aMsl = abs(locationAltitude - gga.mslAltitude)
            when {
                aElipsoidal < 1.0 && aMsl > 1.5 -> ellipsoidalVotes++
                aMsl < 1.0 && aElipsoidal > 1.5 -> mslVotes++
            }
        }
        return verdict
    }

    companion object {
        /** La GGA de la misma epoca que una posicion: misma hora del dia, a medio segundo. */
        fun matches(gga: Gga, locationTimeOfDaySeconds: Double): Boolean {
            val d = abs(gga.timeOfDaySeconds - locationTimeOfDaySeconds)
            return minOf(d, 86400 - d) <= 0.5
        }
    }
}
