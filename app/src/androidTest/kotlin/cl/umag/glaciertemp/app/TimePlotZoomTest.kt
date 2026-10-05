package cl.umag.glaciertemp.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/**
 * El zoom del grafico de tiempo no se sale de los datos.
 *
 * El defecto: los limites se calculaban sobre los puntos VISIBLES, y la placa solo pasa los de
 * la ventana; cada gesto podia correrla un poco mas, hasta mucho antes del primer registro.
 */
class TimePlotZoomTest {

    @get:Rule val rule = createComposeRule()

    private val puntos = (0..1000).map { PlotPoint(it * 60.0, kotlin.math.sin(it / 50.0)) }
    private val fin = 1000 * 60.0

    @Test fun acercar_y_arrastrar_no_sale_de_los_datos() {
        val estado = TimePlotState().apply { zoomMode = true }
        rule.setContent {
            TimePlot(puntos, LocalDateTime.of(2026, 10, 1, 0, 0), estado,
                     Modifier.fillMaxWidth().height(300.dp).testTag("plot"),
                     xLimits = 0.0 to fin)
        }
        // Acercar en horizontal.
        rule.onNodeWithTag("plot").performTouchInput {
            pinch(Offset(centerX - 40f, centerY), Offset(left + 10f, centerY),
                  Offset(centerX + 40f, centerY), Offset(right - 10f, centerY))
        }
        rule.waitForIdle()
        assertNotNull("tiene que haber acercado", estado.x0)
        assertTrue(estado.x1!! - estado.x0!! < fin)
        // El eje vertical sigue automatico: solo se estiro el tiempo.
        assertNull(estado.y0)

        // Arrastrar hacia la derecha muchas veces: la ventana se para en el principio.
        repeat(6) {
            rule.onNodeWithTag("plot").performTouchInput {
                swipe(Offset(left + 20f, centerY), Offset(right - 20f, centerY), 200)
            }
        }
        rule.waitForIdle()
        assertTrue("x0 = ${estado.x0}", estado.x0!! >= 0.0)
        repeat(6) {
            rule.onNodeWithTag("plot").performTouchInput {
                swipe(Offset(right - 20f, centerY), Offset(left + 20f, centerY), 200)
            }
        }
        rule.waitForIdle()
        assertTrue("x1 = ${estado.x1}", estado.x1!! <= fin)

        // Alejar mas alla del registro entero vuelve a verlo todo.
        repeat(4) {
            rule.onNodeWithTag("plot").performTouchInput {
                pinch(Offset(left + 10f, centerY), Offset(centerX - 30f, centerY),
                      Offset(right - 10f, centerY), Offset(centerX + 30f, centerY))
            }
        }
        rule.waitForIdle()
        assertNull("al pasar del todo vuelve a automatico", estado.x0)
    }
}
