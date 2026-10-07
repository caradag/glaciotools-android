package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Que se escribe en la placa al sincronizar el reloj. */
enum class ClockSyncMode {
    /** Huso y hora: la placa adopta el huso del telefono. */
    BOTH,

    /** Solo la hora, conservando el huso que la placa ya tiene. */
    TIME_ONLY,
}

/**
 * Que marca de tiempo hay que enviarle a la placa.
 *
 * Vive en `:core` y no junto a la interfaz PARA PODER PROBARLO. La trampa de esta
 * funcionalidad no se ve leyendo el codigo:
 *
 * "Solo la hora" NO puede enviar la hora local del telefono. Con el telefono en UTC-3 y la
 * placa declarando UTC+0, mandar la hora local sin tocar el huso deja el reloj de la placa
 * tres horas corrido respecto al huso que ella misma dice tener. El resultado es PEOR que no
 * haber sincronizado, porque el error pasa a ser sistematico -- y sistematico es peor que
 * ruidoso, porque no se nota.
 *
 * La marca correcta es el mismo INSTANTE expresado en el huso de destino.
 */
object ClockSync {

    /**
     * [boardTz] es el huso que la placa declara, o null si no se pudo leer. En ese caso
     * TIME_ONLY cae al del telefono: es lo unico que se sabe, y es lo que la app hacia
     * antes de que existiera la opcion.
     */
    fun targetOffsetHours(mode: ClockSyncMode, boardTz: Int?, phoneTz: Int): Int =
        if (mode == ClockSyncMode.TIME_ONLY) (boardTz ?: phoneTz) else phoneTz

    /**
     * Cuanto falta, en milisegundos, para el proximo segundo entero del reloj del telefono.
     *
     * La placa solo guarda segundos enteros. Enviar la hora truncada en un instante
     * cualquiera deja la placa atrasada entre 0 y 1 s segun el momento de pulsar; esperando
     * al cambio de segundo y enviando justo entonces, la marca es exacta y el error queda en
     * lo que tarda el comando en llegar.
     */
    fun msToNextSecond(nowMillis: Long): Long {
        val resto = Math.floorMod(nowMillis, 1000L)
        return if (resto == 0L) 0L else 1000L - resto
    }

    /**
     * Lo que tarda el comando en LLEGAR, a partir de un ida y vuelta medido: la mitad, con
     * tope. Al escribir el registro de segundos, el DS3231 reinicia su cadena de cuenta, de
     * modo que la placa empieza su segundo cuando le llega la orden, no cuando se envia:
     * mandar justo en el cambio de segundo la deja atrasada lo que tarde el viaje (decenas
     * de ms por Bluetooth). Con el ida y vuelta se envia ese tanto ANTES del cambio.
     *
     * Es una estimacion: supone el viaje simetrico, y la linea TIME (25 bytes) puede ocupar
     * un paquete BLE mas que la sonda. Sin medida, 0: el comportamiento de siempre.
     */
    fun leadMs(roundTripMs: Long?): Long =
        roundTripMs?.let { (it / 2).coerceIn(0L, MAX_LEAD_MS) } ?: 0L

    const val MAX_LEAD_MS = 400L

    /** Cuando enviar ([waitMs] desde ahora) y que segundo entero anunciar ([targetMillis]). */
    data class SendPlan(val waitMs: Long, val targetMillis: Long)

    /**
     * El proximo cambio de segundo al que da tiempo a llegar adelantandose [leadMs], con
     * [MIN_WAIT_MS] de margen para que la corrutina se despierte a tiempo.
     */
    fun sendPlan(nowMillis: Long, leadMs: Long): SendPlan {
        var target = nowMillis - Math.floorMod(nowMillis, 1000L) + 1000L
        while (target - leadMs - nowMillis < MIN_WAIT_MS) target += 1000L
        return SendPlan(target - leadMs - nowMillis, target)
    }

    const val MIN_WAIT_MS = 20L

    /** La marca a enviar, con los segundos enteros: la placa no guarda fracciones. */
    fun stampFor(
        now: ZonedDateTime,
        mode: ClockSyncMode,
        boardTz: Int?,
        phoneTz: Int,
    ): LocalDateTime = now
        .withZoneSameInstant(ZoneOffset.ofHours(targetOffsetHours(mode, boardTz, phoneTz)))
        .toLocalDateTime()
        .withNano(0)
}
