package cl.umag.glaciertemp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

/**
 * Una placa que no contesta como logger (tools/fake_silent_board.py, que imita el firmware de
 * diagnostico): la conexion queda abierta en modo serie y el terminal sigue sirviendo. Corre
 * con su propio simulador; ver tools/e2e.sh.
 */
class RawSerialFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMs: Long = 30_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(fragment: String, timeoutMs: Long = 20_000) =
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(fragment, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test fun una_placa_sin_logger_deja_el_terminal_abierto() {
        rule.onNodeWithTag("tool-device").performClick()
        waitForTag("connect-tcp")
        // El boton de subir firmware esta siempre en la pantalla de conexion.
        rule.onNodeWithTag("firmware-update-offline").assertExists()
        rule.onNodeWithTag("connect-tcp").performClick()
        waitForTag("raw-serial")
        rule.onNodeWithTag("firmware-update-raw").assertExists()
        rule.onNodeWithTag("board-id").assertDoesNotExist()

        // Parametros de la linea: parten en los de la GlacierTemp. Por la conexion de
        // depuracion (un socket) baudios y paridad no se pueden cambiar; el fin de linea si.
        rule.onNodeWithTag("serial-settings").performScrollTo()
        rule.onNodeWithText("115200 8N1", substring = true).assertExists()
        rule.onNodeWithTag("serial-baud").assertIsNotEnabled()
        rule.onNodeWithTag("serial-lineending").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("serial-lineending-CR+LF (\\r\\n)").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("CR+LF", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("serial-defaults").assertIsEnabled()

        rule.onNodeWithTag("tab-terminal").performClick()
        rule.onNodeWithTag("terminal-input").performTextInput("HELP")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithTag("terminal-send").assertIsEnabled().performClick()
        waitForText("every test")
    }
}
