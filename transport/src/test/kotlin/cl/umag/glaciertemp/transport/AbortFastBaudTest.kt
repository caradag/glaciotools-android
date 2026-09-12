package cl.umag.glaciertemp.transport

import kotlin.test.*

/**
 * Abortar un volcado RAPIDO, que es el del cable.
 *
 * El fallo que esto fija: el byte de cancelacion sale a la velocidad de la LINEA, y durante
 * un volcado rapido la placa escucha a 230400. Quien abandonaba el volcado bajaba la linea a
 * 115200 en su limpieza --antes de que nadie mandara el CAN-- asi que la placa recibia un
 * byte cualquiera y seguia volcando los ocho megabytes hasta el final. Por Bluetooth nunca
 * se noto porque ahi la velocidad no se cambia: la fija el modulo por su lado.
 *
 * La placa de mentira de aqui hace lo unico que importa de una UART: lo que llega a una
 * velocidad distinta de la suya NO es el byte que se mando, es basura.
 */
class AbortFastBaudTest {

    private companion object {
        const val NORMAL = 115200
        const val RAPIDA = 230400
    }

    private class PlacaRapida(
        /** Donde empieza a escuchar la placa; el volcado rapido ya la subio. */
        private var velocidadPlaca: Int = RAPIDA,
    ) : Transport, BaudSwitchable {

        var velocidadLinea = RAPIDA; private set
        /** A que velocidad iba la linea cuando llego el CAN. Null si nunca llego. */
        var velocidadDelCancel: Int? = null; private set
        var paro = false; private set

        private var acuseEnviado = false
        private var paroEn = 0L
        override var isOpen = true
        override fun open() {}
        override fun close() { isOpen = false }

        override fun setBaudRate(baud: Int) { velocidadLinea = baud }

        @Synchronized
        override fun write(data: ByteArray) {
            // Una UART a otra velocidad no entrega el byte que se mando. No hay nada que
            // interpretar: se pierde.
            if (velocidadLinea != velocidadPlaca) return
            if (data.size == 1 && data[0] == DeviceSession.CANCEL) {
                velocidadDelCancel = velocidadLinea
                paro = true
                paroEn = System.currentTimeMillis()
            }
        }

        @Synchronized
        override fun read(timeoutMs: Int): ByteArray {
            if (!paro) {
                // Sigue volcando a la suya. Si la linea no la acompana, basura.
                Thread.sleep(minOf(timeoutMs, 10).toLong())
                return ByteArray(256) { 0xAA.toByte() }
            }
            // Ya paro. Termina el bloque, vuelve a la velocidad normal y recien entonces
            // anuncia el cierre -- el mismo orden que el firmware.
            if (System.currentTimeMillis() - paroEn < 60) {
                Thread.sleep(10)
                return ByteArray(0)
            }
            velocidadPlaca = NORMAL
            if (acuseEnviado) { Thread.sleep(minOf(timeoutMs, 20).toLong()); return ByteArray(0) }
            acuseEnviado = true
            val texto = "LOGB aborted\n".toByteArray()
            // Y si la linea no la siguio de vuelta, el acuse tampoco se lee.
            return if (velocidadLinea == NORMAL) texto else ByteArray(texto.size) { 0x00 }
        }
    }

    @Test
    fun `el cancel sale a la velocidad a la que la placa escucha`() {
        val t = PlacaRapida()
        val s = DeviceSession(t)
        // Como queda la sesion cuando un volcado rapido se abandona a medias.
        s.marcarVelocidadDeVolcado(RAPIDA, NORMAL)

        val paro = s.abortarVolcado()

        assertEquals(RAPIDA, t.velocidadDelCancel,
                     "el CAN salio a la velocidad equivocada: la placa no lo recibio")
        assertTrue(t.paro, "la placa siguio volcando")
        assertTrue(paro, "no se leyo el acuse: la linea no volvio a tiempo")
        assertEquals(NORMAL, t.velocidadLinea, "la linea se quedo en la velocidad del volcado")
    }

    @Test
    fun `sin volcado rapido nada cambia de velocidad`() {
        // El caso Bluetooth: la sesion no marco ninguna velocidad, asi que el aborto no
        // tiene por que tocar la linea. Tocarla seria romper lo unico que ya funcionaba.
        val t = PlacaRapida(velocidadPlaca = NORMAL)
        t.setBaudRate(NORMAL)
        val s = DeviceSession(t)

        assertTrue(s.abortarVolcado(), "no reconocio el acuse")
        assertEquals(NORMAL, t.velocidadDelCancel)
        assertEquals(NORMAL, t.velocidadLinea)
    }
}
