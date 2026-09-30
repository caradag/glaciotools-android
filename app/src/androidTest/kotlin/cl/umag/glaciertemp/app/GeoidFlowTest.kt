package cl.umag.glaciertemp.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import cl.umag.glaciertemp.core.geo.HeightReference
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Referencia de alturas en Settings y la pestana Geoid de GPS tools.
 *
 * El valor esperado de N sale de GeoidEval (GeographicLib) con egm96-15: 8,7584 m en
 * Punta Arenas; H = 34 - 8,758 = 25,24 m.
 */
class GeoidFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun permisos() {
        val ia = InstrumentationRegistry.getInstrumentation()
        ia.uiAutomation.grantRuntimePermission(ia.targetContext.packageName,
                                               Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @After fun restaurar() = AppSettings.setHeightReference(false, GeoidModel.EGM2008)

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun elegir_egm96_y_ver_n_en_la_pestana_geoid() {
        rule.onNodeWithTag("home-settings").performClick()
        waitForTag("st-height-geoid")
        rule.onNodeWithTag("st-height-geoid").performClick()
        waitForTag("st-geoid-egm96")
        rule.onNodeWithTag("st-geoid-egm96").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(HeightReference.Orthometric(GeoidModel.EGM96), AppSettings.heightReference.value)
        rule.onNodeWithTag("st-geoid-status-egm96").assertTextContains("Built into the app.")

        Espresso.pressBack()
        waitForTag("tool-gps")
        rule.onNodeWithTag("tool-gps").performClick()
        waitForTag("gps-tab-geoid")
        rule.onNodeWithTag("gps-tab-geoid").performClick()
        waitForTag("geoid-lat")
        rule.onNodeWithTag("geoid-lat").performTextReplacement("53°09.6'S")
        rule.onNodeWithTag("geoid-lon").performTextReplacement("70°54.6'W")
        rule.onNodeWithTag("geoid-h").performTextReplacement("34")
        Espresso.closeSoftKeyboard()
        waitForTag("geoid-n-egm96")
        rule.onNodeWithTag("geoid-n-egm96").assertTextEquals("8.758")
        rule.onNodeWithTag("geoid-h-egm96").assertTextEquals("25.24")
        rule.onNodeWithTag("geoid-n-egm2008").assertTextEquals("—")
        rule.onAllNodesWithText("not downloaded").assertCountEquals(2)   // EGM2008 y XGM2019e
    }
}
