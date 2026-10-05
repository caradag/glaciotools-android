package cl.umag.glaciertemp.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cl.umag.glaciertemp.core.fieldbook.CampaignStore
import cl.umag.glaciertemp.core.fieldbook.JournalDays
import cl.umag.glaciertemp.core.fieldbook.JournalReminder
import cl.umag.glaciertemp.core.fieldbook.JournalStore
import java.io.File
import java.util.Calendar

/**
 * El aviso del sistema cuando falta el diario de ayer en la campana abierta.
 *
 * EL RECORDATORIO DE LA APP NO BASTA. Sale arriba de la pantalla de inicio, es decir, solo lo
 * ve quien ya abrio la app, y quien se olvido del diario ayer es justo quien hoy tampoco la
 * abre. Una notificacion llega igual.
 *
 * UNA VEZ AL DIA, A LAS NUEVE DE LA MANANA, Y UNA SOLA VEZ POR DIA QUE FALTA. A primera hora
 * todavia se recuerda el dia anterior; insistir varias veces sobre el mismo dia convertiria
 * una ayuda en un reproche, que es lo que el recordatorio de la app ya evita. Alarma INEXACTA:
 * diez minutos arriba o abajo dan igual y no hace falta despertar el telefono a la hora justa.
 */
object JournalReminderAlarm {

    const val CHANNEL = "journal-reminder"
    const val ACTION_CHECK = "cl.umag.glaciertemp.JOURNAL_CHECK"
    /** El dia que falta, cuando la app se abre desde la notificacion. */
    const val EXTRA_DAY = "cl.umag.glaciertemp.JOURNAL_DAY"
    private const val PREFS = "journal-reminder"
    private const val LAST_NOTIFIED = "last_notified_day"
    private const val HOUR = 9

    private fun pendiente(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 4711,
            Intent(ctx, JournalReminderReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Programa la comprobacion diaria. Idempotente: llamarlo de nuevo la reemplaza. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.timeInMillis,
                               AlarmManager.INTERVAL_DAY, pendiente(ctx))
    }

    /**
     * El dia que falta en la campana abierta, o null. Lee los mismos ficheros que la app y
     * aplica la MISMA regla que el recordatorio de pantalla ([JournalReminder]), para que la
     * notificacion y el aviso de arriba no puedan decir cosas distintas.
     */
    fun missingDay(ctx: Context, now: Long = System.currentTimeMillis()): String? {
        val campanas = CampaignStore(File(ctx.filesDir, "fieldbook/campaigns.txt"))
        val activa = campanas.active() ?: return null
        val diario = JournalStore(File(ctx.filesDir, "journal"))
        val dias = JournalDays.group(diario.list(activa.id).filter { !it.isEmpty() },
                                     diario.dayTitles(activa.id))
        return JournalReminder.missingDay(dias.map { it.key }.toSet(), now, null)
    }

    /** Comprueba y, si falta un dia que no se haya avisado ya, lo avisa. */
    fun check(ctx: Context) {
        val dia = runCatching { missingDay(ctx) }.getOrNull() ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_NOTIFIED, null) == dia) return
        if (notify(ctx, dia)) prefs.edit().putString(LAST_NOTIFIED, dia).apply()
    }

    private fun notify(ctx: Context, dia: String): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "Journal reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Says when yesterday has nothing in the open campaign's journal."
            })
        }
        val abrir = PendingIntent.getActivity(ctx, 4712,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_DAY, dia),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val texto = "Nothing written for ${legible(dia)}. Write it now while you still remember."
        nm.notify(4713, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Journal: yesterday is empty")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build())
        return true
    }

    private fun legible(dayKey: String): String {
        val p = dayKey.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return dayKey
        val c = Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2], 12, 0, 0) }
        return java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.US).format(c.time)
    }
}

/**
 * La alarma diaria y el arranque del telefono.
 *
 * Tras reiniciar, Android olvida todas las alarmas; sin volver a programarla aqui, el aviso
 * dejaria de salir hasta la proxima vez que se abriera la app --justo lo que no pasa cuando
 * uno se olvida de ella--.
 */
class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                JournalReminderAlarm.schedule(ctx)
            JournalReminderAlarm.ACTION_CHECK -> {
                // Lectura de unos pocos ficheros de texto: cabe de sobra en el plazo de un
                // receptor, pero fuera del hilo principal por educacion.
                val pr = goAsync()
                Thread {
                    try { JournalReminderAlarm.check(ctx.applicationContext) } finally { pr.finish() }
                }.start()
            }
        }
    }
}
