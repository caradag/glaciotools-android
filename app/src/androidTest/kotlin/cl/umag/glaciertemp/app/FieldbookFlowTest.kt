package cl.umag.glaciertemp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Recorrido de la libreta de terreno en el emulador.
 *
 * NO toca camara, microfono ni GPS: son dialogos del sistema que este banco no puede
 * conducir, y lo que hay detras de ellos --copiar el fichero, calcular la duracion-- ya se
 * prueba en `:core` sin telefono. Lo que se comprueba aqui es justo lo que no se puede probar
 * de otra forma: que la pantalla escribe lo que dice que escribe, que la tabla de la baliza
 * calcula la tasa que corresponde, y que el menu de ⋮ no toca lo ya registrado.
 */
class FieldbookFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000) =
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun entrarALaLibreta() {
        rule.onNodeWithTag("tool-fieldbook").assertIsDisplayed().performClick()
        waitForTag("fb-new")
    }

    /**
     * Escribe en un campo y CIERRA el teclado.
     *
     * No es cosmetico: la pantalla lleva `imePadding()`, asi que con el teclado abierto el
     * contenido se encoge y lo que estaba abajo queda fuera de la ventana. Un `performClick`
     * posterior aterriza entonces sobre el teclado y no sobre la fila -- que es exactamente
     * como fallaba este test, sin dar ningun error: el clic se ejecutaba, pero en otro sitio.
     */
    private fun escribir(tag: String, texto: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(texto)
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
    }

    private fun crear(tipo: String) {
        rule.onNodeWithTag("fb-new").performClick()
        waitForTag("fb-type-dialog")
        rule.onNodeWithTag("fb-type-$tipo").performClick()
    }

    @Test fun una_nota_general_guarda_texto_y_observador() {
        entrarALaLibreta()
        crear("note")
        waitForTag("fb-person")

        escribir("fb-person", "Camilo Rada")
        escribir("fb-item-0-text", "Grieta nueva al pie del serac")

        // Volver a la lista y reabrir: lo que se lee tiene que venir del disco, no de la
        // pantalla que se acaba de dejar.
        rule.onNodeWithTag("fb-back").performClick()
        waitForTag("fb-list")
        rule.onAllNodesWithText("Grieta nueva", substring = true).onFirst().performClick()
        waitForTag("fb-item-0-text")
        rule.onNodeWithTag("fb-item-0-text")
            .assertTextContains("Grieta nueva al pie del serac", substring = true)
        rule.onNodeWithTag("fb-person").assertTextContains("Camilo Rada", substring = true)
    }

    /**
     * Aqui se comprueba el CABLEADO de la tabla, no la aritmetica.
     *
     * La aritmetica --el ejemplo del enunciado, 80 a 95 cm en 3 dias igual a 5 cm/dia, y los
     * casos en que no hay tasa-- esta cubierta a fondo en `:core`, donde se puede fijar el
     * reloj. Aqui el banco no puede esperar tres dias ni conducir el cuadro de fecha del
     * sistema, asi que lo que toca comprobar es lo otro: que la primera fila NUNCA trae tasa
     * --no tiene anterior contra la que calcular-- y que la segunda SI la trae.
     *
     * Escribi antes este test esperando un guion en la segunda fila, sobre la premisa de que
     * dos lecturas creadas seguidas caen en el mismo milisegundo. Es falsa: entre una y otra
     * media el dedo, asi que hay tiempo transcurrido y la tasa existe -- enorme, porque el
     * intervalo son segundos, pero real. El defecto estaba en la premisa del test.
     */
    @Test fun la_tabla_de_la_baliza_calcula_la_tasa() {
        entrarALaLibreta()
        crear("stake")
        waitForTag("fb-stake-name")
        escribir("fb-stake-name", "E12")

        rule.onNodeWithTag("fb-stake-add").performScrollTo().performClick()
        waitForTag("fb-reading-row-0")
        rule.onNodeWithTag("fb-reading-row-0").performScrollTo().performClick()
        waitForTag("fb-reading-0-height")
        escribir("fb-reading-0-height", "80")
        rule.onNodeWithTag("fb-reading-row-0").performScrollTo().performClick()

        rule.onNodeWithTag("fb-stake-add").performScrollTo().performClick()
        waitForTag("fb-reading-row-1")
        rule.onNodeWithTag("fb-reading-row-1").performScrollTo().performClick()
        waitForTag("fb-reading-1-height")
        escribir("fb-reading-1-height", "95")

        // useUnmergedTree: la fila lleva `Modifier.clickable`, que FUSIONA a sus hijos en un
        // solo nodo de semantica. En el arbol normal las tres celdas son un unico texto
        // "2026-09-21 08:09, 80, +1296000.0" y las etiquetas de cada celda no existen; hay
        // que mirar el arbol sin fusionar para llegar a una sola.
        rule.onNodeWithTag("fb-reading-row-0").performScrollTo()

        // La primera fila no tiene anterior: su celda de tasa queda vacia, siempre.
        rule.onNodeWithTag("fb-rate-0", useUnmergedTree = true).assertTextEquals("—")
        // La segunda si, y trae un numero con signo. Cual sea depende de cuanto tardo el
        // banco en pulsar, asi que se comprueba la FORMA y no el valor.
        val tasa = rule.onNodeWithTag("fb-rate-1", useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text } ?: ""
        assert(Regex("^[+-][0-9]+([.,][0-9]+)?$").matches(tasa)) {
            "la segunda fila deberia traer una tasa con signo, y trae: “$tasa”"
        }
    }

    /** Lo que se promete en el menu de ⋮: quitar un nombre no toca lo ya registrado. */
    @Test fun quitar_un_receptor_de_la_lista_no_cambia_la_medicion_hecha_con_el() {
        entrarALaLibreta()
        crear("gnss")
        waitForTag("fb-point-name")
        escribir("fb-point-name", "BASE1")
        escribir("fb-gnss-receiver", "Emlid RS2")
        // El nombre se recuerda al salir del campo.
        rule.onNodeWithTag("fb-point-name").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()

        rule.onNodeWithTag("fb-gnss-receiver-menu").performScrollTo().performClick()
        waitForTag("fb-gnss-receiver-remove")
        rule.onNodeWithTag("fb-gnss-receiver-remove").performClick()

        rule.onNodeWithTag("fb-gnss-receiver")
            .assertTextContains("Emlid RS2", substring = true)
    }

    /**
     * EL BUG QUE SE ARREGLO: un receptor escrito dentro de una baliza no aparecia en el
     * desplegable de la siguiente medicion GNSS.
     *
     * La causa era que las listas de nombres se cargaban en el estado al ENTRAR en la libreta
     * y no se volvian a mirar, asi que el nombre estaba en disco y no en el desplegable. Este
     * test recorre exactamente ese camino: escribir el receptor en una baliza, salir, crear
     * una medicion GNSS, y abrir la lista.
     */
    @Test fun un_receptor_escrito_en_una_baliza_aparece_en_la_siguiente_medicion() {
        entrarALaLibreta()
        crear("stake")
        waitForTag("fb-stake-name")
        escribir("fb-stake-name", "E99")

        rule.onNodeWithTag("fb-stake-add").performScrollTo().performClick()
        waitForTag("fb-reading-row-0")
        rule.onNodeWithTag("fb-reading-row-0").performScrollTo().performClick()
        waitForTag("fb-reading-0-gnss-toggle")
        rule.onNodeWithTag("fb-reading-0-gnss-toggle").performScrollTo().performClick()
        waitForTag("fb-reading-0-gnss-receiver")
        escribir("fb-reading-0-gnss-receiver", "Septentrio PolaRx")

        // Se sale con Done, sin tocar Measurement End: es el camino en el que fallaba.
        rule.onNodeWithTag("fb-done").performScrollTo().performClick()
        waitForTag("fb-new")

        crear("gnss")
        waitForTag("fb-gnss-receiver-open")
        rule.onNodeWithTag("fb-gnss-receiver-open").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Septentrio PolaRx").onFirst().assertExists()
    }

    /** La altura de antena y la posicion se recuerdan al terminar, sin impedir terminar. */
    @Test fun terminar_sin_altura_de_antena_avisa_pero_ya_registro_la_hora() {
        entrarALaLibreta()
        crear("gnss")
        waitForTag("fb-point-name")
        escribir("fb-point-name", "P-AVISO")

        rule.onNodeWithTag("fb-gnss-go").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("fb-gnss-stop").performScrollTo().performClick()

        waitForTag("fb-missing-dialog")
        rule.onNodeWithTag("fb-missing-fill").performClick()
        rule.waitForIdle()

        // La hora de termino ESTA puesta: el aviso no la impidio.
        rule.onNodeWithTag("fb-gnss-end-value").performScrollTo()
            .assertTextContains("20", substring = true)
    }

    /** Sin titulo, la lista usa las primeras palabras del texto; con titulo, el titulo. */
    @Test fun el_titulo_de_una_nota_es_opcional() {
        entrarALaLibreta()
        crear("note")
        waitForTag("fb-item-0-text")
        escribir("fb-item-0-text", "Nieve reciente sobre el hielo")
        rule.onNodeWithTag("fb-done").performScrollTo().performClick()
        waitForTag("fb-list")
        rule.onAllNodesWithText("Nieve reciente sobre el hielo", substring = true)
            .onFirst().assertExists()

        rule.onAllNodesWithText("Nieve reciente", substring = true).onFirst().performClick()
        waitForTag("fb-note-title")
        escribir("fb-note-title", "Nevada del martes")
        rule.onNodeWithTag("fb-done").performScrollTo().performClick()
        waitForTag("fb-list")
        rule.onAllNodesWithText("Nevada del martes", substring = true).onFirst().assertExists()
    }

    /** Terminar una campana la archiva y deja la lista lista para la siguiente. */
    @Test fun archivar_una_campana_vacia_la_lista_sin_borrar_nada() {
        entrarALaLibreta()
        crear("note")
        waitForTag("fb-item-0-text")
        escribir("fb-item-0-text", "Anotacion de la campana uno")
        rule.onNodeWithTag("fb-done").performScrollTo().performClick()
        waitForTag("fb-list")

        rule.onNodeWithTag("fb-campaign-finish").performClick()
        waitForTag("fb-finish-dialog")
        rule.onNodeWithTag("fb-finish-name").performTextReplacement("Campana de prueba")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag("fb-finish-confirm").performClick()
        waitForTag("fb-empty")

        // Y sigue estando, en archivadas.
        rule.onNodeWithTag("fb-campaign-archived").performClick()
        waitForTag("fb-archived-dialog")
        rule.onAllNodesWithText("Campana de prueba", substring = true).onFirst().assertExists()
    }

    /**
     * EL OTRO BUG QUE SE ARREGLO: la alarma del temporizador no sonaba.
     *
     * `setAlarmClock` exige SCHEDULE_EXACT_ALARM o USE_EXACT_ALARM, que no estaban declarados,
     * y lanzaba SecurityException dentro de un `runCatching` que se la tragaba: la alarma no
     * existia y nada lo decia. Aqui se comprueba lo unico que se puede comprobar sin esperar
     * media hora -- que la alarma QUEDA REGISTRADA EN EL SISTEMA-- leyendo `dumpsys alarm`,
     * que es una fuente externa a la app y no su propio estado.
     */
    @Test fun elegir_una_duracion_registra_la_alarma_en_el_sistema() {
        entrarALaLibreta()
        crear("gnss")
        waitForTag("fb-point-name")
        escribir("fb-point-name", "P-ALARMA")

        rule.onNodeWithTag("fb-gnss-go").performScrollTo().performClick()
        rule.waitForIdle()
        // La duracion programada solo se puede elegir con la medicion ya en marcha.
        rule.onNodeWithTag("fb-gnss-planned").performScrollTo().performClick()
        waitForTag("fb-gnss-planned-30")
        rule.onNodeWithTag("fb-gnss-planned-30").performClick()
        rule.waitForIdle()
        Thread.sleep(1500)   // el servicio arranca y programa en otro proceso

        val volcado = shell("dumpsys alarm")
        // Se busca la ETIQUETA de ESTA alarma y no el nombre del paquete: el paquete aparece
        // en el volcado por otros motivos, asi que una busqueda laxa pasa igual sin permiso y
        // el test no detectaria nada. Comprobado rompiendo el manifiesto a proposito.
        assertTrue("el sistema no tiene registrada la alarma del cronometro: no sonaria nada",
                   "*walarm*:${GnssTimerService.ACTION_ALARM}" in volcado)
    }

    /** Lee la salida de un comando de shell. Fuente externa, no el estado de la propia app. */
    private fun shell(cmd: String): String {
        val fd = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
            .bufferedReader().use { it.readText() }
    }

    @Test fun la_libreta_esta_en_la_pantalla_de_inicio_junto_a_las_otras_dos() {
        rule.onNodeWithTag("tool-device").assertIsDisplayed()
        rule.onNodeWithTag("tool-gps").assertIsDisplayed()
        rule.onNodeWithTag("tool-fieldbook").assertIsDisplayed()
    }
}
