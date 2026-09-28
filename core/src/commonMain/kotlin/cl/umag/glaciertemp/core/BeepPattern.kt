package cl.umag.glaciertemp.core

/**
 * Que suena en cada segundo de la cuenta atras del cambio de minuto.
 *
 * Aqui y no junto al reproductor porque esto es la FORMA de la cuenta atras --que segundos
 * suenan, con que tono y cuantas veces-- y eso es aritmetica que se puede comprobar. El
 * reproductor, que necesita Android, se limita a obedecer.
 *
 * El diseno responde a un problema concreto: para poner en hora un aparato hay que tener las
 * dos manos en el y los ojos en SU pantalla, no en la del telefono. El oido tiene que bastar
 * para saber en que segundo se esta.
 */
object BeepPattern {

    /** Un pitido: frecuencia, duracion y cuantas veces seguidas. */
    data class Beep(val hz: Double, val ms: Int, val repeticiones: Int)

    const val PRIMER_SEGUNDO = 50

    /**
     * El pitido del segundo dado, o null si toca callar.
     *
     *  - 50..57: uno solo, subiendo un semitono por segundo desde 880 Hz;
     *  - 58 y 59: DOBLE. Los dos ultimos son los que hay que distinguir sin contar, justo
     *    cuando el dedo ya esta sobre el boton;
     *  - 00: LARGO y mas grave. Es la marca, y tiene que sonar distinta de los avisos que la
     *    anuncian: si el instante exacto suena igual que la cuenta atras, quien ajusta el
     *    reloj no sabe cual de los pitidos era el bueno.
     */
    fun at(segundo: Int): Beep? = when {
        segundo == 0 -> Beep(660.0, 500, 1)
        segundo in PRIMER_SEGUNDO..57 -> Beep(hz(segundo), 90, 1)
        segundo == 58 || segundo == 59 -> Beep(hz(segundo), 90, 2)
        else -> null
    }

    /** Un semitono por segundo: la subida se oye sin tener que compararla con nada. */
    private fun hz(segundo: Int): Double =
        880.0 * Math.pow(2.0, (segundo - PRIMER_SEGUNDO) / 12.0)
}
