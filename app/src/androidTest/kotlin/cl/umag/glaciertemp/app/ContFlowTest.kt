package cl.umag.glaciertemp.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/**
 * La captura continua (CONT) en el emulador, contra un simulador con el LOG VACIO.
 *
 * Va aparte de [AppFlowTest] y con su propio simulador (lo arranca tools/e2e.sh en una
 * segunda pasada): CONT exige el log vacio y deja otro log --de version 2-- detras, y el
 * recorrido principal necesita sus 240 registros intactos.
 */
class ContFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000) =
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(fragment: String, timeoutMs: Long = 30_000) =
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(fragment, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

    private fun textOf(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("")

    @Test fun captura_continua_con_calentador_y_descarga_con_milisegundos() {
        rule.onNodeWithTag("tool-device").assertIsDisplayed().performClick()
        waitForTag("connect-tcp")
        rule.onNodeWithTag("connect-tcp").performClick()
        waitForTag("board-id")

        // Solo en Advanced: en Normal la tarjeta no existe.
        rule.onNodeWithTag("cont-card").assertDoesNotExist()
        rule.onNodeWithTag("advanced-toggle").performClick()
        waitForTag("cont-card")

        rule.onNodeWithTag("cont-start-heater").performScrollTo().performClick()
        waitForTag("cont-progress")
        rule.onNodeWithTag("cont-progress").assertTextContains("heater on", substring = true)
        // La muestra llega con la linea de estado (el simulador la da cada segundo).
        waitForTag("cont-sample", 15_000)

        rule.onNodeWithTag("cont-stop").performScrollTo().performClick()
        waitForTag("cont-summary", 20_000)
        rule.onNodeWithTag("cont-summary").performScrollTo()
            .assertTextContains("heater on", substring = true)
            .assertTextContains("stopped on request", substring = true)

        // INFO se relee al parar: ahora hay registros, de 14 bytes (12 + 2 de ms), y la
        // tarjeta ya no deja empezar otra captura sobre ellos.
        // INFO se relee justo DESPUES de publicar el resumen: se espera, no se mira una vez.
        rule.waitUntil(10_000) { textOf("record-count").contains("14 B each") }
        rule.onNodeWithTag("cont-not-empty").assertExists()

        rule.onNodeWithTag("download").performScrollTo().performClick()
        waitForText("records downloaded", 60_000)
        waitForTag("csv-rows")
        // Las filas, no la cabecera fija de csv-preview: cada una sale de CsvExporter.row.
        val filas = rule.onAllNodes(hasAnyAncestor(hasTestTag("csv-rows")))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .joinToString("\n")
        assert(Regex("""\d{2}:\d{2}:\d{2}\.\d{3},""").containsMatchIn(filas)) {
            "el CSV de un log de CONT lleva milisegundos:\n$filas"
        }
    }
}
