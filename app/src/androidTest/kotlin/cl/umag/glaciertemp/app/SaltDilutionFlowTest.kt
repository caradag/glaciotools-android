package cl.umag.glaciertemp.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

/**
 * El aforo por dilucion de sal entero, en el aparato: configurar, calibrar, medir, fijar la
 * base, calcular, bloquear con Done, desbloquear, y crear una medicion nueva del mismo perfil
 * desde la lista.
 */
class SaltDilutionFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /**
     * El nodo, despues de desplazar la lista que lo contenga hasta el. En una LazyColumn lo
     * que esta fuera de la pantalla no existe todavia, y performScrollTo no lo encuentra.
     */
    private fun ver(tag: String): SemanticsNodeInteraction {
        for (lista in listOf("fb-salt-list", "fb-list")) {
            if (rule.onAllNodesWithTag(lista).fetchSemanticsNodes().isNotEmpty()) {
                runCatching { rule.onNodeWithTag(lista).performScrollToNode(hasTestTag(tag)) }
            }
        }
        rule.waitForIdle()
        return rule.onNodeWithTag(tag)
    }

    private fun escribir(tag: String, texto: String) {
        ver(tag).performTextReplacement(texto)
        rule.waitForIdle()
    }

    private fun tagPrefix(p: String) = SemanticsMatcher("testTag starts with $p") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(p) == true
    }

    @Test fun dilucion_de_sal_de_punta_a_punta() {
        val perfil = "SD test ${System.currentTimeMillis() % 100000}"

        rule.onNodeWithTag("tool-fieldbook").performClick()
        waitForTag("fb-new")
        rule.onNodeWithTag("fb-new").performClick()
        waitForTag("fb-type-dialog")
        rule.onNodeWithTag("fb-type-gauging").performClick()

        // --- pestana y configuracion ---
        waitForTag("fb-gauging-tab-salt")
        rule.onNodeWithTag("fb-gauging-tab-salt").performClick()
        waitForTag("fb-salt-mass")
        escribir("fb-salt-profile", perfil)
        escribir("fb-salt-mass", "1000")

        // --- calibracion: una recta exacta de 2000 uS/cm por g/L, que es Cal = 0,5 ---
        ver("fb-salt-cal-new").performClick()
        waitForTag("fb-cal-ok")
        ver("fb-cal-ok").performClick()
        waitForTag("fb-cal-ec-10")
        for (i in 0..10) {
            val c = i * 3.0 / (500.0 + i)            // g/L
            escribir("fb-cal-ec-$i", "%.6f".format(java.util.Locale.ROOT, 30.0 + 2000.0 * c))
        }
        // Salir de la ultima casilla es lo que rehace la recta.
        ver("fb-cal-ec-0").performClick()
        rule.waitForIdle()
        ver("fb-cal-fit")
            .assertTextContains("calibration factor 0.5000", substring = true)
        ver("fb-salt-cal").assertTextContains("0.5", substring = true)
        Espresso.closeSoftKeyboard()
        ver("fb-salt-setup-done").performClick()
        rule.waitForIdle()
        ver("fb-salt-summary").assertTextContains("Cal 0.5000", substring = true)

        // --- lecturas a mano, una por pulsacion ---
        for (v in listOf("20", "20", "80", "55", "21", "20")) {
            escribir("fb-salt-ec", v)
            rule.onNodeWithTag("fb-salt-enter").performClick()
            rule.waitForIdle()
            Thread.sleep(1100)
        }
        Espresso.closeSoftKeyboard()
        ver("fb-salt-last").assertTextContains("6 reading(s)", substring = true)

        // --- cursor sobre la primera lectura y base ---
        ver("fb-salt-chart").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(left + 2f, centerY), 300)
        }
        rule.waitForIdle()
        ver("fb-salt-cursor-value").assertTextContains("20 µS/cm", substring = true)
        ver("fb-salt-set-base").performClick()
        ver("fb-salt-base").assertTextContains("20 µS/cm", substring = true)

        // --- caudal ---
        ver("fb-salt-calculate").performClick()
        rule.waitForIdle()
        ver("fb-salt-total").assertTextContains("m³/s", substring = true)
        rule.onNodeWithTag("fb-salt-progress").assertTextContains("L/s", substring = true)

        // --- Done bloquea ---
        ver("fb-done").performClick()
        waitForTag("fb-list")
        // En el arbol SIN FUSIONAR: la tarjeta es clicable y fusiona el texto de sus hijos,
        // asi que en el fusionado el nombre no es descendiente de nadie.
        val tarjeta = rule.onAllNodes(tagPrefix("fb-card-") and hasAnyDescendant(hasText(perfil)),
                                      useUnmergedTree = true)
            .fetchSemanticsNodes().first()
        val id = tarjeta.config[SemanticsProperties.TestTag].removePrefix("fb-card-")
        ver("fb-card-$id").performClick()
        waitForTag("fb-gauging-locked")
        rule.onAllNodesWithTag("fb-salt-enter").assertCountEquals(0)
        ver("fb-gauging-unlock").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("fb-gauging-locked").assertCountEquals(0)
        ver("fb-salt-enter").assertExists()
        ver("fb-done").performClick()
        waitForTag("fb-list")

        // --- medicion nueva desde la lista ---
        ver("fb-card-menu-$id").performClick()
        waitForTag("fb-card-new-measurement-$id")
        rule.onNodeWithTag("fb-card-new-measurement-$id").performClick()
        // Sin masa la configuracion nace DESPLEGADA: es lo primero que falta por poner.
        waitForTag("fb-salt-mass")
        rule.onAllNodesWithTag("fb-gauging-locked").assertCountEquals(0)
        ver("fb-salt-profile").assertTextContains(perfil, substring = true)
        ver("fb-salt-cal").assertTextContains("0.5", substring = true)
        // Lo medido no pasa: ni masa, ni lecturas, ni las conductividades de la calibracion.
        ver("fb-salt-mass").assert(hasText("1000", substring = true).not())
        ver("fb-salt-chart-empty").assertExists()
    }
}
