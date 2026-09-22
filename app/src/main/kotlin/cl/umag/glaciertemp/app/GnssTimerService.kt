package cl.umag.glaciertemp.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * El cronometro de una medicion GNSS, fuera de la app.
 *
 * POR QUE UN SERVICIO Y NO UN CONTADOR EN EL ViewModel: una medicion de punto dura entre
 * cinco minutos y tres horas, y durante ese rato el telefono esta en un bolsillo con la
 * pantalla apagada. Android congela una app en ese estado, asi que un temporizador que viva
 * en el proceso simplemente no salta -- y el aviso de que se puede recoger el receptor es
 * justamente lo unico que tiene que ocurrir sin que nadie mire.
 *
 * La alarma se programa con [AlarmManager.setAlarmClock] porque es la unica clase de alarma
 * que Doze no aplaza NUNCA. A cambio el sistema muestra el icono de alarma, que aqui es una
 * propiedad y no un efecto secundario: dice que hay una medicion en marcha.
 *
 * SI necesita permiso de alarma exacta, al contrario de lo que decia este comentario antes.
 * Sin el, `setAlarmClock` lanza SecurityException y --si quien llama se la traga-- la alarma
 * simplemente no existe y nada lo dice. Fue exactamente lo que paso. Ahora el fallo se
 * reporta y se degrada a una alarma inexacta, que Doze puede retrasar minutos pero que al
 * menos suena.
 *
 * LA ALARMA NO TERMINA LA MEDICION. Suena, y la medicion sigue corriendo hasta que alguien
 * pulsa Measurement End, para que la hora de termino sea la de verdad -- el momento en que se
 * levanto el receptor del punto-- y no la hora a la que venia programado levantarlo.
 */
class GnssTimerService : Service() {

    companion object {
        const val ACTION_START = "cl.umag.glaciertemp.GNSS_TIMER_START"
        const val ACTION_STOP = "cl.umag.glaciertemp.GNSS_TIMER_STOP"
        const val ACTION_ALARM = "cl.umag.glaciertemp.GNSS_TIMER_ALARM"
        const val ACTION_SILENCE = "cl.umag.glaciertemp.GNSS_TIMER_SILENCE"

        const val EXTRA_LABEL = "label"
        const val EXTRA_START = "start"
        const val EXTRA_PLANNED_END = "plannedEnd"

        private const val CHANNEL = "gnss-timer"
        private const val NOTIFICATION_ID = 4711

        /** Tope del wake lock mientras suena. Sin tope, un telefono olvidado se vacia. */
        private const val RING_WAKELOCK_MS = 10 * 60_000L
    }

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var label: String = "GNSS measurement"
    private var startMillis: Long = 0L
    private var plannedEndMillis: Long? = null
    private var ringing = false

    /** La alarma exacta fallo y se puso una que el sistema puede retrasar. La pantalla lo dice. */
    private var alarmaDegradada = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                label = intent.getStringExtra(EXTRA_LABEL) ?: label
                startMillis = intent.getLongExtra(EXTRA_START, System.currentTimeMillis())
                plannedEndMillis = intent.getLongExtra(EXTRA_PLANNED_END, 0L)
                    .takeIf { it > 0L }
                silenciar()
                startForeground(NOTIFICATION_ID, notificacion())
                programarAlarma()
            }

            ACTION_ALARM -> {
                // Se recuperan del intent y no del campo: si el proceso murio durante la
                // medicion, el servicio nace aqui con los campos a cero y la notificacion
                // saldria vacia justo en el momento en que hay que leerla.
                label = intent.getStringExtra(EXTRA_LABEL) ?: label
                startMillis = intent.getLongExtra(EXTRA_START, startMillis)
                plannedEndMillis = intent.getLongExtra(EXTRA_PLANNED_END, 0L)
                    .takeIf { it > 0L } ?: plannedEndMillis
                ringing = true
                startForeground(NOTIFICATION_ID, notificacion())
                sonar()
            }

            ACTION_SILENCE -> {
                silenciar()
                // La medicion SIGUE: se calla la alarma y se deja la notificacion en marcha.
                // Parar aqui borraria de la pantalla lo unico que recuerda que hay un
                // receptor en el hielo.
                startForeground(NOTIFICATION_ID, notificacion())
            }

            else -> {
                cancelarAlarma()
                silenciar()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START_NOT_STICKY: si el sistema mata el servicio, no tiene sentido revivirlo con un
        // intent vacio -- los datos de la medicion estan en disco y la alarma esta programada
        // en el AlarmManager, que sobrevive por su cuenta.
        return START_NOT_STICKY
    }

    // ------------------------------------- alarma -------------------------------------

    private fun alarmIntent(): PendingIntent {
        val i = Intent(this, GnssTimerService::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_START, startMillis)
            plannedEndMillis?.let { putExtra(EXTRA_PLANNED_END, it) }
        }
        // getForegroundService y no getService: desde Android 12 un servicio en primer plano
        // no se puede arrancar desde segundo plano, salvo --entre otros casos-- desde una
        // alarma exacta, que es exactamente lo que esto es.
        return PendingIntent.getForegroundService(
            this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Programa la alarma, y deja constancia de COMO quedo programada.
     *
     * Nunca en silencio: una alarma que no se pudo poner tiene que poder distinguirse de una
     * que se puso bien, porque las dos se ven igual --nada-- hasta el momento en que una suena
     * y la otra no, tres horas despues y en el hielo.
     */
    private fun programarAlarma() {
        alarmaDegradada = false
        val am = getSystemService(AlarmManager::class.java) ?: return
        val cuando = plannedEndMillis
        runCatching { am.cancel(alarmIntent()) }
        // Solo si aun no ha vencido. Sin esta guarda, volver a abrir una medicion cuya alarma
        // ya sono --y que el usuario ya silencio-- la haria sonar otra vez al instante.
        if (cuando == null || cuando <= System.currentTimeMillis()) return

        val exacta = runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cuando, pantallaPrincipal()),
                             alarmIntent())
        }
        if (exacta.isSuccess) return

        android.util.Log.w("GnssTimer", "setAlarmClock fallo; se degrada a inexacta",
                           exacta.exceptionOrNull())
        // Inexacta pero exenta de Doze: puede llegar tarde, y la notificacion lo dice en vez
        // de prometer una hora que no se va a cumplir.
        alarmaDegradada = runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, alarmIntent())
        }.isSuccess
        if (!alarmaDegradada) {
            android.util.Log.e("GnssTimer", "no se pudo programar ninguna alarma")
        }
    }

    private fun cancelarAlarma() {
        alarmaDegradada = false
        runCatching { getSystemService(AlarmManager::class.java)?.cancel(alarmIntent()) }
    }

    private fun pantallaPrincipal(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // ------------------------------------- sonido -------------------------------------

    private fun sonar() {
        if (player != null) return
        // Wake lock CON tope: la alarma tiene que sonar con la pantalla apagada, pero un
        // telefono olvidado en una mochila no puede quedarse despierto hasta agotarse.
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glaciotools:gnss-alarm")
                ?.apply { acquire(RING_WAKELOCK_MS) }
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return@runCatching
            player = MediaPlayer().apply {
                // USAGE_ALARM y no USAGE_NOTIFICATION: suena por el canal de alarma, que no
                // lo silencia el modo silencioso ni el de no molestar. El aviso de que hay un
                // receptor esperando en el hielo no puede depender de como tenga el telefono
                // el timbre.
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(this@GnssTimerService, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        runCatching {
            val patron = longArrayOf(0, 600, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                    ?.vibrate(android.os.VibrationEffect.createWaveform(patron, 0))
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)
                    ?.vibrate(android.os.VibrationEffect.createWaveform(patron, 0))
            }
        }
    }

    private fun silenciar() {
        ringing = false
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                    ?.cancel()
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)?.cancel()
            }
        }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---------------------------------- notificacion ----------------------------------

    private fun canal() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // IMPORTANCE_LOW: la notificacion permanente no tiene que pitar cada vez que se
        // actualiza. El ruido de la alarma lo pone el servicio con su propio reproductor, que
        // ademas no depende de los ajustes que el usuario le haya puesto al canal.
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "GNSS measurement", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows a GNSS measurement in progress and its planned end."
            setShowBadge(false)
        })
    }

    private fun notificacion(): Notification {
        canal()
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM
                         else NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pantallaPrincipal())

        val fin = plannedEndMillis
        when {
            ringing -> {
                b.setContentText("Planned time reached — the receiver can be collected. " +
                                 "The measurement is still running.")
                b.setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Planned time reached — the receiver can be collected.\n" +
                    "The measurement is still running: press Measurement End in the app " +
                    "when you actually lift the receiver."))
                b.addAction(0, "Silence", accion(ACTION_SILENCE))
            }
            // El cronometro lo lleva el SISTEMA, no la app: se le dan las marcas de tiempo y
            // el las pinta aunque el proceso este congelado. Un texto que la app tuviera que
            // refrescar cada segundo se quedaria parado en cuanto Android la duerma, que es
            // el 99 % del tiempo que dura la medicion.
            fin != null -> {
                b.setWhen(fin).setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) b.setChronometerCountDown(true)
                b.setContentText(if (alarmaDegradada)
                    "Measuring — the alarm may be a few minutes late"
                else "Measuring — alarm when the planned time is up")
            }
            else -> {
                b.setWhen(startMillis).setUsesChronometer(true)
                b.setContentText("Measuring — no planned duration")
            }
        }
        return b.build()
    }

    private fun accion(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, GnssTimerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        silenciar()
        super.onDestroy()
    }
}

/** La cara que ve el resto de la app. Arrancar y parar, nada mas. */
object GnssTimer {

    /**
     * Anuncia una medicion en marcha. Llamarlo dos veces con los mismos datos es inofensivo:
     * vuelve a poner la notificacion y reprograma la misma alarma.
     */
    fun start(ctx: Context, label: String, startMillis: Long, plannedEndMillis: Long?) {
        val i = Intent(ctx, GnssTimerService::class.java).apply {
            action = GnssTimerService.ACTION_START
            putExtra(GnssTimerService.EXTRA_LABEL, label)
            putExtra(GnssTimerService.EXTRA_START, startMillis)
            plannedEndMillis?.let { putExtra(GnssTimerService.EXTRA_PLANNED_END, it) }
        }
        runCatching { ctx.startForegroundService(i) }
    }

    fun stop(ctx: Context) {
        runCatching {
            ctx.startService(Intent(ctx, GnssTimerService::class.java)
                .setAction(GnssTimerService.ACTION_STOP))
        }
    }
}
