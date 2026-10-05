package cl.umag.glaciertemp.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import cl.umag.glaciertemp.core.fieldbook.CampaignStore
import cl.umag.glaciertemp.core.fieldbook.JournalDays
import cl.umag.glaciertemp.core.fieldbook.JournalEntry
import cl.umag.glaciertemp.core.fieldbook.JournalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El aviso del sistema de "ayer esta vacio", con los ficheros de verdad de la app.
 *
 * Se llama a la comprobacion directamente en vez de esperar a las nueve: lo que se prueba es
 * que lee la campana abierta, aplica la regla del recordatorio y publica, una sola vez.
 */
class JournalReminderTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    private fun publicada() = nm.activeNotifications.any { it.id == 4713 }

    /** El sistema publica de forma asincrona: se espera un poco antes de mirar. */
    private fun esperaPublicada(ms: Long = 5000): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (publicada()) return true; Thread.sleep(100) }
        return publicada()
    }

    private fun permiso() = android.os.Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @Test fun avisa_una_vez_si_ayer_esta_vacio_y_calla_si_no() {
        val campanas = CampaignStore(File(ctx.filesDir, "fieldbook/campaigns.txt"))
        val activa = campanas.openOrCurrent()
        val diario = JournalStore(File(ctx.filesDir, "journal"))
        val ayer = JournalDays.previousDayKey(System.currentTimeMillis())
        // Partimos de un ayer vacio: se quita lo que hubiera de ayer en el diario de prueba.
        diario.list(activa.id).filter { JournalDays.dayKey(it.epochMillis) == ayer }
            .forEach { diario.delete(it.id) }
        diario.setDayTitle(activa.id, ayer, "")
        ctx.getSharedPreferences("journal-reminder", 0).edit().clear().commit()
        nm.cancel(4713)

        assertEquals(ayer, JournalReminderAlarm.missingDay(ctx))
        JournalReminderAlarm.check(ctx)
        assertTrue("sin permiso de avisos el test no prueba nada", permiso())
        assertTrue("la notificacion tiene que estar publicada", esperaPublicada())

        // La misma comprobacion otra vez no vuelve a avisar del mismo dia.
        nm.cancel(4713)
        JournalReminderAlarm.check(ctx)
        Thread.sleep(1500)
        assertTrue("no se repite el aviso del mismo dia", !publicada())

        // Con algo escrito ayer, no hay nada que recordar.
        val mediodia = JournalDays.dayStart(System.currentTimeMillis()) - 12 * 3_600_000L
        val e = JournalEntry(diario.newId(), activa.id, mediodia, title = "prueba")
        diario.save(e)
        assertNull(JournalReminderAlarm.missingDay(ctx))
        diario.delete(e.id)
        assertNotNull(JournalReminderAlarm.missingDay(ctx))
    }
}
