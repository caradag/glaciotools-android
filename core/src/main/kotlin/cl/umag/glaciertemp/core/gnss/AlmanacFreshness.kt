package cl.umag.glaciertemp.core.gnss

/** En que estado esta el almanaque guardado. */
enum class Freshness {
    /** No hay ninguno. La herramienta no puede predecir nada. */
    MISSING,
    /** Recien traido. No hace falta tocar nada. */
    FRESH,
    /** Sirve de sobra, pero conviene refrescarlo cuando haya red. */
    AGING,
    /** Viejo. Sigue sirviendo para elegir horas, pero se avisa. */
    STALE,
}

/**
 * Cuando hay que volver a bajar el almanaque, y cuando basta con avisar.
 *
 * LOS PLAZOS SALEN DE LA TOLERANCIA, no de una costumbre. Un error de 2 grados en el cielo
 * son 700-900 km de error de posicion del satelite, cinco ordenes de magnitud por encima de
 * lo que da una efemeride precisa. Con ese margen un juego de elementos orbitales vale
 * MESES, no horas: el ICD del GPS permite usar un almanaque hasta 180 dias, y los receptores
 * guardan almanaques de semanas para decidir que satelites buscar, que es exactamente el
 * problema de esta herramienta.
 *
 * Asi que el calendario no lo manda la precision sino la comodidad:
 *
 *  - por debajo de una semana no se descarga nada, aunque haya red: seria gastar bateria y
 *    datos para no cambiar ni un pixel de la grafica;
 *  - hasta dos meses se usa sin reparos, refrescandolo si hay red a mano;
 *  - pasados dos meses se sigue usando --sirve, y en terreno no hay alternativa-- pero se
 *    dice en pantalla. Un dato viejo que se presenta como bueno es peor que no tenerlo.
 */
object AlmanacFreshness {

    const val FRESH_DAYS = 7L
    const val STALE_DAYS = 60L

    private const val DIA = 86_400_000L

    fun of(downloadedAtMillis: Long?, nowMillis: Long): Freshness {
        if (downloadedAtMillis == null) return Freshness.MISSING
        val dias = (nowMillis - downloadedAtMillis) / DIA
        return when {
            dias < 0 -> Freshness.FRESH          // reloj del telefono hacia atras: no alarmar
            dias < FRESH_DAYS -> Freshness.FRESH
            dias < STALE_DAYS -> Freshness.AGING
            else -> Freshness.STALE
        }
    }

    /** Si conviene bajarlo ahora, suponiendo que hay red. */
    fun shouldDownload(downloadedAtMillis: Long?, nowMillis: Long): Boolean =
        of(downloadedAtMillis, nowMillis) != Freshness.FRESH

    /** La antiguedad en palabras, para la pantalla. */
    fun describeAge(downloadedAtMillis: Long?, nowMillis: Long): String {
        if (downloadedAtMillis == null) return "never downloaded"
        val ms = (nowMillis - downloadedAtMillis).coerceAtLeast(0)
        val dias = ms / DIA
        val horas = ms / 3_600_000L
        return when {
            horas < 1 -> "updated just now"
            horas < 24 -> "updated $horas hour${if (horas == 1L) "" else "s"} ago"
            dias < 60 -> "updated $dias day${if (dias == 1L) "" else "s"} ago"
            else -> "updated ${dias / 30} month${if (dias / 30 == 1L) "" else "s"} ago"
        }
    }
}
