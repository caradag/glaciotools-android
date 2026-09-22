package cl.umag.glaciertemp.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fotos y audios de la libreta: lo poco que no se puede escribir sin Android.
 *
 * El resto --que fichero pertenece a que entrada, cuando se borra-- vive en `:core` y se
 * prueba en el escritorio. Aqui solo queda lo que necesita camara, microfono o el decodificador
 * de imagenes del sistema.
 */
object MediaVault {

    /**
     * El autoridad del FileProvider. Tiene que coincidir con el `android:authorities` del
     * manifiesto; escrito en los dos sitios a mano, se desfasa en silencio y la camara
     * devuelve un fallo que no dice nada.
     */
    fun authority(ctx: Context): String = ctx.packageName + ".files"

    /**
     * La URI que se le da a la camara para que escriba.
     *
     * Una `file://` no vale desde Android 7: el sistema lanza FileUriExposedException al
     * pasarsela a otra app. El FileProvider entrega una `content://` con permiso temporal
     * solo para ese fichero.
     */
    fun uriFor(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, authority(ctx), file)

    /**
     * Una miniatura, decodificada al tamano que se va a pintar y no al de la camara.
     *
     * Una foto de 12 Mpx son 48 MB en memoria; una fila con seis de ellas tumba la app. El
     * `inSampleSize` hace que el decodificador lea directamente reducido, sin llegar a
     * construir la grande.
     */
    fun decodeScaled(file: File, maxPx: Int): ImageBitmap? = runCatching {
        if (!file.exists()) return null
        val medir = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, medir)
        val mayor = maxOf(medir.outWidth, medir.outHeight)
        if (mayor <= 0) return null
        var escala = 1
        while (mayor / (escala * 2) >= maxPx) escala *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = escala }
        BitmapFactory.decodeFile(file.absolutePath, opciones)?.asImageBitmap()
    }.getOrNull()

    /** La decodificacion fuera del hilo principal: una foto grande tarda decenas de ms. */
    @Composable
    fun rememberImage(file: File, maxPx: Int): State<ImageBitmap?> =
        produceState<ImageBitmap?>(initialValue = null, file.path, maxPx) {
            value = withContext(Dispatchers.IO) { decodeScaled(file, maxPx) }
        }

    /**
     * Duracion de un audio ya grabado, en milisegundos, o null si no se puede leer.
     *
     * Con `release()` en un finally y no con `use`: MediaMetadataRetriever solo es
     * AutoCloseable desde API 29 y aqui el minimo es 26.
     */
    fun audioDurationMillis(file: File): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

/**
 * Graba una nota de audio a un fichero.
 *
 * AAC dentro de MPEG-4 (`.m4a`) y no el AMR por defecto: el AMR esta pensado para voz por
 * telefono, suena mal y hay escritorios donde no se abre sin instalar nada. Un `.m4a` lo abre
 * cualquier cosa, que es la misma propiedad que se busco en el formato de los ficheros.
 */
class AudioNoteRecorder {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    /** Cuando empezo la grabacion en curso, o 0. Lo lee el cronometro de la pantalla. */
    var startedAtMillis: Long = 0L
        private set

    val recording: Boolean get() = recorder != null
    val file: File? get() = target

    /** Devuelve false si el microfono no esta disponible; quien llama lo dice en pantalla. */
    fun start(ctx: Context, destination: File): Boolean {
        stop()
        return runCatching {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
                    else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(destination.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            target = destination
            startedAtMillis = System.currentTimeMillis()
            true
        }.getOrElse {
            runCatching { destination.delete() }
            recorder = null
            target = null
            false
        }
    }

    /**
     * Cierra la grabacion y devuelve el fichero, o null si no habia ninguna o salio vacia.
     *
     * Un `stop()` inmediato despues del `start()` lanza --el codificador no ha escrito ni una
     * trama-- y deja un fichero de cero bytes. Se borra aqui: una nota de audio vacia en la
     * lista es peor que no tener nota, porque hay que abrirla para descubrir que no hay nada.
     */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = target
        recorder = null
        target = null

        startedAtMillis = 0L
        val paroBien = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!paroBien || f == null || !f.exists() || f.length() == 0L) {
            runCatching { f?.delete() }
            return null
        }
        return f
    }

    /** Para y tira lo grabado. Es lo que hace el boton de descartar. */
    fun cancel() {
        val f = stop()
        runCatching { f?.delete() }
    }
}

/**
 * Reproduce una nota de audio. Uno cada vez: empezar otra para la anterior.
 *
 * Se guarda QUE fichero suena para que la lista pueda pintar el boton de parar en la linea
 * correcta; sin eso, dos notas de audio se ven las dos como si estuvieran sonando.
 */
class AudioNotePlayer {

    private var player: MediaPlayer? = null

    /**
     * Estado de Compose y no un campo normal: la lista tiene que repintar el boton de parar
     * en la linea que suena, y un campo corriente no provoca recomposicion -- el icono se
     * quedaria en "reproducir" con el audio sonando.
     */
    var playing: String? by androidx.compose.runtime.mutableStateOf(null)
        private set

    fun toggle(file: File, onFinished: () -> Unit) {
        if (playing == file.name) { stop(); onFinished(); return }
        stop()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                // this@AudioNotePlayer.stop() y no stop() a secas: dentro del apply, `stop`
                // es el del MediaPlayer. Llamando a ese, el audio para pero `playing` se
                // queda con el nombre puesto y la lista sigue ensenando el boton de parar
                // sobre una nota que ya termino.
                setOnCompletionListener { this@AudioNotePlayer.stop(); onFinished() }
                prepare()
                start()
            }
            playing = file.name
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playing = null
    }
}
