package cl.umag.glaciertemp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

/**
 * Recorrido completo de la app en el emulador contra tools/fake_glaciertemp.py.
 *
 * El simulador tiene que estar corriendo en el host antes de lanzar el test; de eso se
 * encarga tools/e2e.sh. El test no puede arrancarlo porque vive del otro lado del emulador.
 */
class AppFlowTest {

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

    @Test fun conecta_configura_y_descarga() {
        // 1. Conectar por el transporte de depuracion.
        rule.onNodeWithTag("connect-tcp").assertIsDisplayed().performClick()
        waitForTag("board-id")

        // 2. Los metadatos llegan de la cabecera INFO, no de adivinarlos.
        // Se muestra la forma CORTA: GT001-E5F607 son el tipo de hardware, la revision de
        // hardware y los seis ultimos digitos de los 64 bits E5A1B2C3D4E5F607 que reporta el
        // simulador. La etiqueta dice "Hardware ID" y no "ID" porque en esta misma tarjeta
        // convive la version de FIRMWARE, y el "001" pertenece al hardware.
        rule.onNodeWithTag("board-id").assertTextContains("Hardware ID", substring = true)
        rule.onNodeWithTag("board-id").assertTextContains("GT001-E5F607", substring = true)
        rule.onNodeWithTag("record-count").assertTextContains("240", substring = true)

        // 2b. La configuracion se lee sola al conectar. Antes salia "?" en todos los campos
        // porque la placa imprime el ROTULO de la EEPROM y no el codigo del comando.
        rule.onNodeWithTag("var-INT").performScrollTo().assertTextContains("600", substring = true)
        rule.onNodeWithTag("interval-seconds").assertTextContains("600", substring = true)

        // Y el formato aparece en palabras, no como un numero hexadecimal suelto.
        rule.onNodeWithTag("format-summary").performScrollTo()
            .assertTextContains("bytes per record", substring = true)

        // 3. Escribir una variable y comprobar que la placa devuelve el valor nuevo.
        rule.onNodeWithTag("var-INT").performScrollTo().performTextClearance()
        rule.onNodeWithTag("var-INT").performTextInput("900")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithTag("set-INT").performScrollTo().performClick()
        waitForText("INT = 900", 15_000)

        // 3b. Y la respuesta de la placa tiene que verse en el TERMINAL, no solo en la linea
        // de estado. El publicado a la pantalla esta limitado a una vez cada 150 ms para que
        // un volcado largo no repinte por cada linea, y el ultimo tramo --el que llega
        // cuando ya no viene nada detras-- se quedaba dentro de esa ventana sin publicar.
        rule.onNodeWithTag("tab-terminal").performClick()
        waitForText("Interval between measurements", 10_000)
        // El eco de lo ENVIADO tambien: el terminal ensena los dos sentidos.
        rule.onAllNodesWithText("INT=900", substring = true)
            .fetchSemanticsNodes().isNotEmpty()
        rule.onNodeWithTag("tab-device").performClick()
        rule.waitForIdle()

        // 4. Descarga por rango: el caso habitual, no el volcado completo.
        rule.onNodeWithTag("range-from").performScrollTo().performTextInput("10")
        rule.onNodeWithTag("range-to").performTextInput("29")
        // El teclado virtual queda abierto tras escribir y tapa el boton; hay que
        // cerrarlo y desplazar el boton a la vista antes de pulsarlo.
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithTag("download-estimate")
            .assertTextContains("20 records", substring = true)
        rule.onNodeWithTag("download").performScrollTo().performClick()
        waitForText("20 records downloaded", 60_000)

        // 5. El CSV se genera y el nombre del fichero lleva el ID de la placa.
        waitForTag("csv-preview")
        rule.onNodeWithTag("csv-preview")
            .assertTextContains("Time,Volt,Temp,RH,HAtemp", substring = true)
        // El nombre del fichero lleva la forma corta: "glaciotools_GT001-E5F607_..." se
        // lee, y "glaciotools_E5A1B2C3D4E5F607_..." no. Es el motivo de que exista el corto.
        rule.onNodeWithTag("export-name")
            .assertTextContains("glaciotools_GT001-E5F607_", substring = true)

        // 5a. El aviso de desfase del reloj: el simulador arranca con --clock-offset.
        rule.onNodeWithTag("clock-warning").performScrollTo()
            .assertTextContains("board clock is", substring = true)

        // 5b. El terminal, en su pestana: envia un comando y muestra lo que conteste.
        // Va dentro de este mismo test porque el simulador atiende una conexion cada vez.
        rule.onNodeWithTag("tab-terminal").performClick()
        rule.onNodeWithTag("terminal-input").performTextInput("VER")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        rule.onNodeWithTag("terminal-send").performClick()
        waitForText("proto=", 15_000)
        // El historial recupera el ultimo comando enviado.
        rule.onNodeWithTag("terminal-history").performClick()
        rule.onNodeWithTag("terminal-input").assertTextContains("VER", substring = true)
        rule.onNodeWithTag("tab-device").performClick()

        // 6. El grafico aparece y permite cambiar de canal.
        rule.onNodeWithTag("chart").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("chart-caption").assertTextContains("20 records", substring = true)

        // Las estadisticas de muestreo salen de las marcas de tiempo del propio log, no de
        // la configuracion de la placa.
        rule.onNodeWithTag("stat-interval").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("stat-maxgap").performScrollTo().assertIsDisplayed()
        // El simulador entrega registros a intervalo constante: cobertura completa.
        rule.onNodeWithTag("stat-coverage").performScrollTo()
            .assertTextContains("100.0", substring = true)
        rule.onNodeWithTag("chip-Temp").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chart").assertIsDisplayed()

        // 7. Bateria: con 20 registros en 3 horas no hay tramo suficiente, y la app
        //    debe decirlo en vez de extrapolar una autonomia inventada.
        rule.onNodeWithTag("battery-type").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("battery-text").assertTextContains("day", substring = true)

        // 8. El boton de exportar existe y el nombre lleva la marca de la app.
        rule.onNodeWithTag("export").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("export-name").assertTextContains("glaciotools_", substring = true)

        // 8b. La posicion de la descarga aparece entre las estadisticas, con los mismos
        // datos que van a la cabecera del CSV. En el emulador sin GPS sale el motivo por el
        // que no hay posicion, que es igual de informativo y tiene que salir tambien.
        rule.onNodeWithTag("stat-position").performScrollTo().assertIsDisplayed()

        // 9. El desfase del reloj aparece entre las estadisticas, no solo como aviso.
        rule.onNodeWithTag("stat-clock-offset").performScrollTo()
            .assertTextContains("board", substring = true)

        // 10. La nota libre va apagada por defecto y su campo solo existe al encenderla.
        rule.onAllNodesWithTag("export-note").assertCountEquals(0)
        rule.onNodeWithTag("export-note-toggle").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("export-note").performTextInput("Prueba de campo")
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        // La vista previa incluye la cabecera de metadatos con la nota dentro.
        rule.onNodeWithTag("csv-preview").assertIsDisplayed()

        // 11. Modo avanzado: el volcado crudo solo existe con el conmutador encendido.
        rule.onNodeWithTag("raw-log").assertDoesNotExist()
        rule.onNodeWithTag("advanced-toggle").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("raw-log").performScrollTo().assertIsDisplayed()
        // Y en normal se ocultan las demas variables, pero NUNCA el intervalo.
        rule.onNodeWithTag("var-TZN").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("advanced-toggle").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("var-TZN").assertDoesNotExist()
        rule.onNodeWithTag("var-INT").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("raw-log").assertDoesNotExist()

        // 12. Volver al terminal conserva la posicion del scroll en vez de barrer de arriba
        // abajo otra vez. Se comprueba que el primer elemento visible NO es el cero, que es
        // donde arrancaba el barrido cada vez que se entraba.
        rule.onNodeWithTag("tab-terminal").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab-device").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab-terminal").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("terminal").assertIsDisplayed()
        rule.onNodeWithTag("tab-device").performClick()
        rule.waitForIdle()

        // 13. Reiniciar el contador avisa ANTES de hacerlo, y se puede cancelar. Se prueba
        // la cancelacion y no el reinicio porque el reinicio dejaria al simulador sin datos
        // para el resto del recorrido.
        rule.onNodeWithTag("reset-counter").performScrollTo().performClick()
        waitForTag("reset-warning")
        rule.onNodeWithTag("reset-cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("reset-warning").assertDoesNotExist()
        // Y el contador sigue intacto: cancelar no puede haber mandado nada.
        rule.onNodeWithTag("record-count").assertTextContains("240", substring = true)
    }

    /**
     * El aviso al sincronizar antes de descargar, en su propio test porque necesita una
     * conexion recien hecha: la advertencia se apaga en cuanto se descarga de esa placa.
     */
    @Test fun avisa_antes_de_sincronizar_sin_haber_descargado() {
        rule.onNodeWithTag("connect-tcp").assertIsDisplayed().performClick()
        waitForTag("board-id")

        // Sin descarga previa, sincronizar destruiria el desfase de los datos en memoria.
        rule.onNodeWithTag("sync-clock").performScrollTo().performClick()
        waitForTag("sync-warning")
        rule.onNodeWithTag("sync-download-first").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sync-warning").assertDoesNotExist()

        // Tras descargar, el mismo boton ya no avisa por ese motivo. Puede salir el del
        // huso horario, que es otra pregunta: el simulador declara TZN=-3.
        rule.onNodeWithTag("download").performScrollTo().performClick()
        waitForText("records downloaded", 60_000)
        rule.onNodeWithTag("sync-clock").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sync-warning").assertDoesNotExist()
    }
}
