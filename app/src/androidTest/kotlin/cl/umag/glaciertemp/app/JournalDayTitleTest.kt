package cl.umag.glaciertemp.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import cl.umag.glaciertemp.core.fieldbook.CampaignStore
import cl.umag.glaciertemp.core.fieldbook.JournalDays
import cl.umag.glaciertemp.core.fieldbook.JournalStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * El titulo del dia se escribe en el editor de su primera entrada, y el dia sobrevive con
 * titulo aunque la entrada se cierre vacia.
 *
 * Trabaja sobre el dia de HOY de la campana abierta, asi que antes y despues se limpian las
 * entradas y el titulo de hoy: el test no puede depender de lo que dejaron otros.
 */
class JournalDayTitleTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hoy = JournalDays.dayKey(System.currentTimeMillis())

    @Before fun permisos() {
        val ia = InstrumentationRegistry.getInstrumentation()
        ia.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun limpiarHoy() {
        val campana = CampaignStore(File(ctx.filesDir, "fieldbook/campaigns.txt")).active() ?: return
        val st = JournalStore(File(ctx.filesDir, "journal"))
        st.list(campana.id).filter { JournalDays.dayKey(it.epochMillis) == hoy }.forEach { st.delete(it.id) }
        st.setDayTitle(campana.id, hoy, "")
    }

    @After fun despues() = limpiarHoy()

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun el_dia_se_titula_desde_su_primera_entrada_y_sobrevive_vacio() {
        // El diario necesita una campana abierta; tras instalar de cero no hay ninguna
        CampaignStore(File(ctx.filesDir, "fieldbook/campaigns.txt")).openOrCurrent("Test")
        limpiarHoy()

        rule.onNodeWithTag("home-journal").performClick()
        waitForTag("jr-day-title-in-editor")
        rule.onNodeWithTag("jr-day-title-in-editor").performTextReplacement("Temporal, no se salio")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()

        // Se cierra la entrada VACIA: se borra, pero el dia queda con su titulo
        rule.onNodeWithTag("jr-save-top").performClick()
        waitForTag("jr-day-$hoy")
        rule.onNodeWithText("Temporal, no se salio").assertExists()
        rule.onNodeWithTag("jr-day-empty-$hoy").assertExists()

        // Y desde ahi se puede anadir una entrada: el editor vuelve a ofrecer el titulo
        rule.onNodeWithTag("jr-day-add-$hoy").performClick()
        waitForTag("jr-day-title-in-editor")
        rule.onNodeWithTag("jr-day-title-in-editor").assertTextContains("Temporal, no se salio")
    }
}
