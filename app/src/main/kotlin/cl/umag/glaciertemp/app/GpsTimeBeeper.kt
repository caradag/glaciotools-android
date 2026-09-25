package cl.umag.glaciertemp.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Los pitidos de la cuenta atras del cambio de minuto.
 *
 * POR QUE TONOS GENERADOS Y NO ToneGenerator. Hace falta que la frecuencia SUBA segundo a
 * segundo: eso es lo que permite saber en que segundo se esta sin mirar la pantalla, que es
 * justo el momento en que se tienen las dos manos en el aparato que se esta poniendo en
 * hora. ToneGenerator solo ofrece un puñado de tonos fijos con nombre de centralita.
 *
 * Suena por el canal de ALARMA y no por el de multimedia: en terreno el telefono va en
 * silencio o con el volumen de medios a cero, y un pitido de sincronizacion que no se oye no
 * sirve para nada.
 */
class GpsTimeBeeper {

    private val ratio = 44100

    /**
     * Un pitido.
     *
     * @param hz frecuencia; sube con el segundo para que la cuenta atras se oiga, no se mire
     * @param ms duracion; el del cambio de minuto es largo, y esa es la marca que importa
     */
    fun beep(hz: Double, ms: Int) {
        val n = ratio * ms / 1000
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            // Rampa de entrada y salida de 5 ms: un seno cortado en seco produce un chasquido
            // que en una cuenta atras se confunde con el propio pitido.
            val rampa = (ratio * 5 / 1000).coerceAtLeast(1)
            val g = when {
                i < rampa -> i.toDouble() / rampa
                i > n - rampa -> (n - i).toDouble() / rampa
                else -> 1.0
            }
            pcm[i] = (sin(2.0 * PI * hz * i / ratio) * g * Short.MAX_VALUE * 0.6).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(ratio)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        runCatching {
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) { runCatching { t?.release() } }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            track.play()
        }.onFailure { runCatching { track.release() } }
    }

}
