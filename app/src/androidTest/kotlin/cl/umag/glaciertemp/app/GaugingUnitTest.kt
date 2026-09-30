package cl.umag.glaciertemp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import cl.umag.glaciertemp.core.fieldbook.LengthUnit
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Aforo con las longitudes en centimetros: lo que se teclea en cm se guarda en metros, y al
 * volver a metros la misma casilla muestra el valor en metros sin perder nada.
 */
class GaugingUnitTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @After fun restaurar() = AppSettings.setGaugingLengthUnit(LengthUnit.METRE)

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun escribir(tag: String, texto: String) {
        rule.onNodeWithTag(tag).performTextReplacement(texto)
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
    }

    @Test fun las_longitudes_en_centimetros_se_guardan_en_metros() {
        AppSettings.setGaugingLengthUnit(LengthUnit.CENTIMETRE)
        rule.onNodeWithTag("tool-fieldbook").performClick()
        waitForTag("fb-new")
        rule.onNodeWithTag("fb-new").performClick()
        waitForTag("fb-type-dialog")
        rule.onNodeWithTag("fb-type-gauging").performClick()
        waitForTag("fb-gauging-width")

        escribir("fb-gauging-width", "1250")
        escribir("fb-gauging-interval", "50")
        rule.onNodeWithTag("fb-gauging-bincount").assertTextContains("This gives 25 bins", substring = true)
        rule.onNodeWithTag("fb-gauging-ok").performClick()
        waitForTag("fb-gauging-x-0")
        // centro del primer tramo: 0,25 m = 25 cm
        rule.onNodeWithTag("fb-gauging-x-0").assertTextEquals("25")
        rule.onNodeWithText("depth (cm)").assertExists()

        rule.onNodeWithTag("fb-gauging-depth-0").performTextReplacement("35")
        Espresso.closeSoftKeyboard()
        // medida al 60 % desde la superficie: 21 cm
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("fb-gauging-vdepth-0").fetchSemanticsNodes().any {
                it.config.toString().contains("21")
            }
        }

        // En metros, la misma profundidad se lee 0.35: se guardo en metros
        rule.runOnIdle { AppSettings.setGaugingLengthUnit(LengthUnit.METRE) }
        rule.waitForIdle()
        rule.onNodeWithTag("fb-gauging-depth-0").assertTextContains("0.35")
        rule.onNodeWithTag("fb-gauging-x-0").assertTextEquals("0.25")
    }
}
