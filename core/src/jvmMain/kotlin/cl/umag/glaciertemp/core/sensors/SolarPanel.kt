package cl.umag.glaciertemp.core.sensors

import kotlin.math.cos
import kotlin.math.sin

/**
 * Donde apuntar un panel solar teniendo medido el horizonte.
 *
 * POR QUE EL HORIZONTE CAMBIA LA RESPUESTA. Sin obstrucciones, el optimo de un panel es
 * conocido y se puede sacar de una tabla: mirando al ecuador con una inclinacion parecida a
 * la latitud. En un valle de montana eso deja de valer, porque las horas que la tabla supone
 * disponibles pueden estar detras de una pared de roca. Entonces conviene inclinarlo hacia
 * donde el cielo ESTA abierto, aunque sea peor en abstracto. Esa diferencia es justo lo que
 * esta herramienta puede decir y una tabla no.
 */
object SolarPanel {

    /**
     * @param tiltDeg inclinacion sobre la horizontal: 0 es tumbado, 90 de pie
     * @param azimuthDeg hacia donde mira la cara del panel, desde el norte hacia el este
     * @param energy suma de radiacion recibida, en unidades arbitrarias comparables entre si
     * @param energyFlat lo que recibiria tumbado, para ver si inclinarlo compensa
     * @param energyUnobstructed lo que recibiria el mismo panel optimo sin horizonte delante
     */
    data class Best(
        val tiltDeg: Int,
        val azimuthDeg: Int,
        val energy: Double,
        val energyFlat: Double,
        val energyUnobstructed: Double,
    ) {
        /** Que fraccion se pierde por culpa del relieve, entre 0 y 1. */
        fun shadingLoss(): Double =
            if (energyUnobstructed <= 0.0) 0.0
            else ((energyUnobstructed - energy) / energyUnobstructed).coerceIn(0.0, 1.0)

        /** Cuanto gana inclinarlo respecto a dejarlo tumbado. */
        fun gainOverFlat(): Double =
            if (energyFlat <= 0.0) 0.0 else energy / energyFlat - 1.0
    }

    /**
     * Prueba todas las inclinaciones y orientaciones y se queda con la mejor del dia.
     *
     * BUSQUEDA EXHAUSTIVA y no un optimo analitico: con un horizonte irregular la funcion a
     * maximizar tiene escalones --cada vez que el sol entra o sale de detras de un cerro-- y
     * cualquier metodo basado en derivadas se queda en el primer escalon que encuentra.
     * Noventa inclinaciones por setenta y dos orientaciones sobre un dia en pasos de cinco
     * minutos son unos dos millones de cuentas: nada para un telefono, y no hay que
     * justificar ninguna aproximacion.
     */
    fun best(
        track: List<Solar.Position>,
        horizon: HorizonProfile?,
        tiltStep: Int = 1,
        azimuthStep: Int = 5,
    ): Best? {
        // Solo cuenta el sol que esta arriba Y a la vista. Lo segundo es el aporte de haber
        // medido el horizonte.
        val util = track.mapNotNull { p ->
            val w = Solar.beamFraction(p.elevation)
            if (w <= 0.0) return@mapNotNull null
            val tapado = horizon != null && p.elevation < horizon.elevationAt(p.azimuth)
            Rayo(Math.toRadians(p.azimuth), Math.toRadians(p.elevation), w, tapado)
        }
        if (util.isEmpty()) return null

        var mejor: Best? = null
        var mejorSinHorizonte = 0.0
        var plano = 0.0
        util.forEach { if (!it.tapado) plano += it.w * sin(it.el) }

        var t = 0
        while (t <= 90) {
            val cb = cos(Math.toRadians(t.toDouble()))
            val sb = sin(Math.toRadians(t.toDouble()))
            var a = 0
            while (a < 360) {
                val ga = Math.toRadians(a.toDouble())
                var visto = 0.0
                var sinHorizonte = 0.0
                util.forEach { r ->
                    // Coseno del angulo entre el sol y la normal del panel.
                    val cosIncidencia = sin(r.el) * cb + cos(r.el) * sb * cos(r.az - ga)
                    if (cosIncidencia > 0.0) {
                        val aporte = r.w * cosIncidencia
                        sinHorizonte += aporte
                        if (!r.tapado) visto += aporte
                    }
                }
                if (mejor == null || visto > mejor!!.energy) {
                    mejor = Best(t, a, visto, plano, 0.0)
                }
                if (sinHorizonte > mejorSinHorizonte) mejorSinHorizonte = sinHorizonte
                a += azimuthStep
            }
            t += tiltStep
        }
        return mejor?.copy(energyUnobstructed = mejorSinHorizonte)
    }

    private class Rayo(val az: Double, val el: Double, val w: Double, val tapado: Boolean)

    /**
     * Cuantas horas de sol directo quedan a la vista, y cuantas tapa el relieve.
     *
     * Es el dato que mas dice de un sitio sin tener que entender nada de paneles: "aqui el
     * sol sale a las nueve por detras del cerro y se va a las cuatro" es una frase que se
     * puede comprobar mirando.
     */
    fun sunHours(track: List<Solar.Position>, horizon: HorizonProfile?, stepMinutes: Int):
            Pair<Double, Double> {
        val h = stepMinutes / 60.0
        var arriba = 0.0
        var visible = 0.0
        track.forEach { p ->
            if (p.elevation > 0.0) {
                arriba += h
                if (horizon == null || p.elevation >= horizon.elevationAt(p.azimuth)) visible += h
            }
        }
        return visible to (arriba - visible)
    }
}
