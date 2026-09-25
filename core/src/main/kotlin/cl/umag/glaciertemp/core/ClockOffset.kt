package cl.umag.glaciertemp.core

import kotlin.math.abs

/**
 * El desfase entre dos relojes, dicho de forma que no se pueda leer al reves.
 *
 * POR QUE NO UN SIGNO. La primera version mostraba "-0 ms" junto a la frase "positive means
 * the phone is behind GPS", y las dos cosas se contradecian: el signo decia una cosa y el
 * pie otra. Peor aun, un signo obliga a recordar el convenio JUSTO cuando hay que decidir si
 * adelantar o atrasar el reloj del aparato, que es el unico momento en que la herramienta
 * se usa. Se dice con palabras: "3.2 s behind GPS" no admite dos lecturas.
 */
object ClockOffset {

    /** Por debajo de esto, los relojes estan en hora a efectos de terreno. */
    const val EN_HORA_MS = 50L

    /**
     * @param gpsMenosTelefono hora del GPS menos la del telefono, en milisegundos.
     *        Positivo = el GPS va por delante = el telefono ATRASA.
     */
    fun describe(gpsMenosTelefono: Long): String {
        val a = abs(gpsMenosTelefono)
        if (a < EN_HORA_MS) return "in sync"
        val cuanto = when {
            a < 1000 -> "$a ms"
            a < 60_000 -> "%.1f s".format(a / 1000.0)
            else -> "%d min %02d s".format(a / 60_000, (a % 60_000) / 1000)
        }
        return if (gpsMenosTelefono > 0) "$cuanto behind GPS" else "$cuanto ahead of GPS"
    }
}
