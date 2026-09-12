package cl.umag.glaciertemp.transport

import kotlin.test.*

/**
 * Cortar un volcado en curso y dejar la linea limpia.
 *
 * Parar de leer no para de emitir. Abandonar una descarga dejaba a la placa volcando
 * megabytes contra un enlace que ya no se lee, y esa cola se colaba despues como si fuera
 * la respuesta de los comandos siguientes -- uno escribia VER y recibia bloques del volcado
 * anterior durante minutos, hasta que la cola se agotaba sola.
 *
 * Se prueba con un transporte falso y no contra el simulador porque el simulador lee por
 * lineas y no puede ver un byte suelto a mitad de volcado; emularlo exigiria reestructurarlo
 * para una carrera que ademas no seria reproducible.
 */
class AbortDumpTest {

    /**
     * Una placa que vuelca sin parar hasta que recibe el byte de cancelacion.
     *
     * [acusa] a false imita un firmware anterior al 3.1: no entiende la cancelacion, asi que
     * sigue emitiendo y nunca confirma nada.
     */
    private class PlacaVolcando(
        private val acusa: Boolean,
        private val restante: Int = 200_000,
        private val cierre: String = "LOGB aborted\n",
    ) : Transport {
        var cancelRecibido = false; private set
        private var emitido = 0
        private var acuseEnviado = false
        private val respuestas = ArrayDeque<String>()
        override var isOpen = true
        override fun open() {}

        override fun write(data: ByteArray) {
            if (data.size == 1 && data[0] == DeviceSession.CANCEL) {
                cancelRecibido = true
                return
            }
            // Un comando de texto: se encola su respuesta.
            respuestas.addLast("fw=3.1 proto=4\n")
        }

        override fun read(timeoutMs: Int): ByteArray {
            if (cancelRecibido && acusa && !acuseEnviado) {
                acuseEnviado = true
                return cierre.toByteArray()
            }
            if (cancelRecibido && acusa) {
                // Ya paro: solo quedan las respuestas de los comandos.
                return respuestas.removeFirstOrNull()?.toByteArray() ?: ByteArray(0)
            }
            // Sigue volcando: bloques binarios, que es lo que llenaba el terminal.
            if (emitido < restante) {
                val n = minOf(4096, restante - emitido)
                emitido += n
                return ByteArray(n) { 0xAA.toByte() }
            }
            return respuestas.removeFirstOrNull()?.toByteArray() ?: ByteArray(0)
        }

        override fun close() { isOpen = false }
    }

    @Test
    fun `con firmware que lo entiende, la placa para y la linea queda limpia`() {
        val t = PlacaVolcando(acusa = true)
        val s = DeviceSession(t)
        val diag = ArrayList<String>()

        assertTrue(s.abortarVolcado { diag.add(it) },
                   "no reconocio el acuse de la placa: $diag")
        assertTrue(t.cancelRecibido, "no se mando el byte de cancelacion")

        // Y lo que importa de verdad: el comando siguiente recibe SU respuesta y no la cola
        // del volcado. Era el sintoma -- escribir VER y recibir bloques del volcado anterior.
        val reply = String(s.exchange("VER", quietMs = 200))
        assertEquals("fw=3.1 proto=4", reply.trim(), "el comando leyo cola del volcado")
    }

    @Test
    fun `con firmware que no lo entiende se dice, en vez de fingir que paro`() {
        val t = PlacaVolcando(acusa = false, restante = 10_000_000)
        val s = DeviceSession(t)
        val diag = ArrayList<String>()

        val paro = s.abortarVolcado { diag.add(it) }
        assertFalse(paro, "dijo que la placa paro cuando nunca lo confirmo")
        assertTrue(diag.any { it.contains("discarded") },
                   "no informa de lo que tuvo que descartar: $diag")
    }

    @Test
    fun `tambien reconoce el acuse del volcado Intel HEX`() {
        val t = PlacaVolcando(acusa = true, cierre = "LOGH aborted\n")
        val s = DeviceSession(t)
        assertTrue(s.abortarVolcado(), "no reconocio LOGH aborted")
    }

    @Test
    fun `el byte de cancelacion no es el de pausa`() {
        // XOFF dice "espera, no doy abasto" y CAN dice "ya no lo quiero". Confundirlos
        // dejaria a la placa parada treinta segundos y reanudando despues.
        assertEquals(0x18.toByte(), DeviceSession.CANCEL)
        assertNotEquals(0x13.toByte(), DeviceSession.CANCEL)
    }
}
