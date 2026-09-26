package cl.umag.glaciertemp.core.sensors

import cl.umag.glaciertemp.core.BeepPattern
import kotlin.math.abs
import kotlin.math.roundToInt

/** El rumbo en palabras y en grados. */
object Compass {

    private val ROSA = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                              "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** A [0, 360). Los sensores devuelven azimut en (-180, 180] y a veces algo fuera. */
    fun normalize(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /**
     * El punto de la rosa de los vientos.
     *
     * Se da JUNTO a los grados, no en su lugar. Los grados son el dato; "NNE" es lo que se
     * puede repetir en voz alta a quien anota, y lo que permite ver de un vistazo que el
     * telefono no esta apuntando a donde uno cree.
     */
    /**
     * Los grados ya listos para pintar.
     *
     * SE NORMALIZA DESPUES DE REDONDEAR. Un rumbo de 359,96 esta dentro de [0,360) y pasa la
     * normalizacion, pero al redondear a cero decimales se convierte en "360", que no es un
     * rumbo: la brujula se quedaba un instante marcando 360 al cruzar el norte.
     */
    fun format(deg: Double, decimales: Int = 0): String {
        val f = Math.pow(10.0, decimales.toDouble())
        val r = Math.round(normalize(deg) * f) / f
        return "%.${decimales}f°".format(if (r >= 360.0) 0.0 else r)
    }

    fun cardinal(deg: Double): String {
        val d = normalize(deg)
        return ROSA[(((d + 11.25) % 360.0) / 22.5).toInt()]
    }
}

/**
 * La medida de albedo con el sensor de luz del telefono.
 *
 * QUE ES Y QUE NO ES. El albedo es la fraccion de la luz que la superficie devuelve:
 * reflejada dividida por incidente. Se mide apuntando el telefono arriba y luego abajo.
 *
 * NO ES UNA MEDIDA RADIOMETRICA. El sensor de un telefono esta pensado para regular el
 * brillo de la pantalla: responde a la luz VISIBLE ponderada como el ojo humano --no al
 * espectro solar completo, que sobre nieve tiene mucho infrarrojo-- y su respuesta angular
 * no esta calibrada como la de un piranometro. El numero sirve para comparar superficies en
 * el mismo rato y bajo la misma luz; no para publicarlo como albedo de banda ancha.
 *
 * La division cancela buena parte de eso --la misma ponderacion aparece arriba y abajo-- que
 * es justo lo que hace la medida util a pesar del instrumento.
 */
object AlbedoRun {

    /** Para colocar el telefono despues de pulsar OK. */
    const val PREP_SECONDS = 5

    /** Hay que MANTENERLO quieto. Se promedia todo este rato, no se toma un instante. */
    const val HOLD_SECONDS = 5

    /**
     * El pitido de cada segundo.
     *
     * DOS TONOS Y NO UNO. La cuenta atras y la medida son dos cosas distintas: durante la
     * primera hay que moverse, durante la segunda hay que estar quieto. Si sonaran igual,
     * quien sostiene el telefono mirando al cielo --sin ver la pantalla, que es el caso-- no
     * sabria en cual de las dos esta. El grave dice "ya estoy midiendo, no te muevas".
     *
     * El largo del final marca que se acabo, como en la cuenta atras del reloj.
     */
    fun beep(midiendo: Boolean, ultimo: Boolean): BeepPattern.Beep = when {
        ultimo -> BeepPattern.Beep(440.0, 500, 1)
        midiendo -> BeepPattern.Beep(660.0, 90, 1)
        else -> BeepPattern.Beep(1320.0, 80, 1)
    }

    /**
     * Reflejada / incidente, o null si no se puede.
     *
     * Con incidente cero o negativa no hay cociente posible: pasa de noche o con el sensor
     * tapado, y devolver 0 o infinito seria inventarse un albedo donde no hubo medida.
     */
    fun albedo(incidente: Double, reflejada: Double): Double? {
        if (!incidente.isFinite() || !reflejada.isFinite()) return null
        if (incidente <= 0.0) return null
        return reflejada / incidente
    }

    /**
     * Lo que hay que mirar antes de creerse el numero.
     *
     * Un albedo mayor que 1 es imposible y significa que algo fallo --una sombra sobre la
     * medida de arriba, el telefono movido, una nube que se abrio en medio--. Decirlo es
     * mejor que dar un 1,4 con cara de dato.
     */
    fun warning(incidente: Double, reflejada: Double): String? {
        val a = albedo(incidente, reflejada)
        return when {
            a == null -> "No light reaching the sensor: albedo cannot be computed."
            a > 1.0 -> "Reflected is higher than incident — something shaded the upward " +
                       "reading or the light changed between the two. Repeat both."
            incidente < 100.0 -> "Very little light (${fmt(incidente)} lx). In near darkness " +
                                 "the ratio is mostly sensor noise."
            else -> null
        }
    }

    /** Dos decimales para el albedo; los lux, enteros a partir de 10. */
    fun fmt(lux: Double): String =
        if (abs(lux) >= 10.0) lux.roundToInt().toString() else "%.1f".format(lux)

    fun fmtAlbedo(a: Double): String = "%.2f".format(a)
}

/**
 * Lo que se copia al portapapeles en cada pestana de sensores.
 *
 * TEXTO Y NO NUMEROS SUELTOS. Lo copiado acaba pegado en la libreta, en un correo o en un
 * mensaje, y un "0.62" sin nada alrededor no dice ni que es ni cuando se tomo. Cada linea
 * lleva su nombre, su unidad y la marca de tiempo, que es lo que hace que siga
 * significando algo una semana despues.
 */
object SensorReport {

    fun tilt(yaw: Double, pitch: Double, roll: Double, cuando: String): String =
        buildString {
            appendLine("GlacioTools — tilt  $cuando")
            appendLine("Yaw (azimuth): ${Compass.format(yaw, 1)}  (${Compass.cardinal(yaw)})")
            appendLine("Pitch: ${g(pitch)}")
            append("Roll: ${g(roll)}")
        }

    fun compass(heading: Double, uT: Double?, cuando: String): String =
        buildString {
            appendLine("GlacioTools — compass  $cuando")
            appendLine("Heading: ${Compass.format(heading, 1)}  (${Compass.cardinal(heading)})")
            append("Magnetic field: " + (uT?.let { "%.1f µT".format(it) } ?: "—"))
        }

    fun pressure(hPa: Double, cuando: String): String =
        "GlacioTools — pressure  $cuando\nPressure: %.2f hPa".format(hPa)

    fun light(lux: Double, cuando: String): String =
        "GlacioTools — light  $cuando\nIlluminance: ${AlbedoRun.fmt(lux)} lx"

    fun albedo(incidente: Double, reflejada: Double, cuando: String): String =
        buildString {
            appendLine("GlacioTools — albedo  $cuando")
            appendLine("Incident (facing up): ${AlbedoRun.fmt(incidente)} lx")
            appendLine("Reflected (facing down): ${AlbedoRun.fmt(reflejada)} lx")
            val a = AlbedoRun.albedo(incidente, reflejada)
            appendLine("Albedo: " + (a?.let { AlbedoRun.fmtAlbedo(it) } ?: "—"))
            AlbedoRun.warning(incidente, reflejada)?.let { appendLine("Note: $it") }
            append("Phone light sensor, visible band, uncalibrated — comparative, not radiometric.")
        }

    private fun g(deg: Double): String = "%.1f°".format(deg)
}
