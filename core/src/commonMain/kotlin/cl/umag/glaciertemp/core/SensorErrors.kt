package cl.umag.glaciertemp.core

/**
 * El registro de fallos de sensores que la placa informa en INFO (firmware 3.6).
 *
 * La placa guarda los CUATRO ultimos codigos, de 4 bits cada uno, en un entero de 16 bits
 * con el mas reciente en el nibble bajo, y cuenta aparte todos los intentos fallidos. Los
 * manda en crudo --`err=0x3218 errn=17`-- porque el texto de cada codigo costaria flash que
 * no hay: la traduccion vive aqui.
 *
 * El contador y la lista dicen cosas distintas. La placa reintenta cada lectura hasta tres
 * veces; un fallo que un reintento arregla no deja hueco en el log, y solo queda en el
 * contador. Los huecos del log son los que fallaron las tres veces.
 */
data class SensorErrorLog(
    /** Codigos del mas reciente al mas antiguo, sin los huecos vacios (0). */
    val codes: List<Int>,
    /** Intentos fallidos desde el ultimo arranque en frio o RC; 65535 es "o mas". */
    val failedAttempts: Int,
) {
    val isEmpty: Boolean get() = failedAttempts == 0 && codes.isEmpty()
    val saturated: Boolean get() = failedAttempts >= 0xFFFF
}

object SensorErrors {

    /**
     * La tabla del firmware (GlacierTemp_1_cell_v02_claude.ino, ERR_*). Tienen que coincidir:
     * si el firmware anade uno, se anade aqui con el mismo numero.
     */
    fun describe(code: Int): String = when (code) {
        1 -> "TMP119 did not acknowledge the conversion command"
        2 -> "TMP119 did not answer while converting"
        3 -> "TMP119 did not finish the conversion in time"
        4 -> "TMP119 temperature read failed"
        5 -> "HDC1080 did not acknowledge the measurement command"
        6 -> "HDC1080 returned an incomplete reading"
        7 -> "DS18B20 probe missing, bus busy or bad CRC"
        8 -> "Flash memory did not accept a record"
        else -> "Unknown error code $code"
    }

    /**
     * Los campos `err` y `errn` de INFO, o null si la placa no los manda (firmware anterior
     * a 3.6) o vienen mal formados. Null y no un registro vacio: "sin errores" es una
     * afirmacion sobre la placa, y un firmware que no lleva la cuenta no puede hacerla.
     */
    fun parse(err: String?, errn: String?): SensorErrorLog? {
        val word = err?.removePrefix("0x")?.takeIf { it.length in 1..4 }?.toIntOrNull(16)
            ?: return null
        val count = errn?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val codes = (0 until 4).map { (word shr (4 * it)) and 0xF }.filter { it != 0 }
        return SensorErrorLog(codes, count)
    }
}
