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
