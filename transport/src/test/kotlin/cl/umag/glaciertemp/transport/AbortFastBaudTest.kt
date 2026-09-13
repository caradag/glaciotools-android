package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.Logb
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

    /** Una placa que atiende un LOGB de verdad, con su cambio de velocidad. */
    private class PlacaConLogb(private val registros: Int) : Transport, BaudSwitchable {
        private val cola = ArrayDeque<Byte>()
        var velocidadLinea = NORMAL; private set
        override var isOpen = true
        override fun open() {}
        override fun close() { isOpen = false }
        override fun setBaudRate(baud: Int) { velocidadLinea = baud }

        @Synchronized
        override fun write(data: ByteArray) {
            val cmd = String(data).trim()
            if (!cmd.startsWith("LOGB=", ignoreCase = true)) return
            val bytes = registros * REC
            val bloques = (bytes + 255) / 256
            ("LOGB begin sig=0x100F rec=$REC from=0 to=${registros - 1} " +
             "blocks=$bloques blocksize=256 fast=$RAPIDA\n")
                .forEach { cola.addLast(it.code.toByte()) }
            for (i in 0 until bloques) {
                val len = minOf(256, bytes - i * 256)
                val d = ByteArray(len) { (it % 251).toByte() }
                cola.addLast(0xAA.toByte()); cola.addLast(0x55.toByte())
                u16(i); u16(len)
                d.forEach { cola.addLast(it) }
                u16(Logb.crc16(d))
            }
            "LOGB end\n".forEach { cola.addLast(it.code.toByte()) }
        }

        private fun u16(v: Int) {
            cola.addLast((v and 0xFF).toByte()); cola.addLast(((v shr 8) and 0xFF).toByte())
        }

        @Synchronized
        override fun read(timeoutMs: Int): ByteArray {
            if (cola.isEmpty()) {
                Thread.sleep(minOf(timeoutMs, 10).toLong())
                return ByteArray(0)
            }
            val n = minOf(256, cola.size)
            return ByteArray(n) { cola.removeFirst() }
        }

        companion object { const val REC = 12 }
    }

    @Test
    fun `la velocidad que se anuncia es la de ahora, no la de siempre`() {
        // La barra de estado decia 115200 durante todo el volcado rapido, que es justo
        // cuando alguien mira ese numero: para comprobar que el volcado rapido entro.
        val t = PlacaConLogb(registros = 4000)
        t.setBaudRate(NORMAL)
        val s = DeviceSession(t)
        val info = DeviceInfo(firmware = "3.3", protocol = 4, boardId = "0",
                              signature = 0x100F, recordBytes = PlacaConLogb.REC,
                              recordCount = 4000, flashBytes = 0,
                              baud = NORMAL, fastBaud = RAPIDA)

        assertEquals(0, s.velocidadDeVolcado, "antes de empezar no hay velocidad de volcado")

        val vistas = java.util.Collections.synchronizedSet(HashSet<Int>())
        s.download(info, 0, 3999, retries = 0,
                   onProgress = { vistas.add(s.velocidadDeVolcado) })

        assertTrue(RAPIDA in vistas,
                   "durante el volcado nunca se anuncio la velocidad rapida: $vistas")
        assertEquals(0, s.velocidadDeVolcado, "quedo anunciando una velocidad que ya no es")
        assertEquals(NORMAL, t.velocidadLinea, "la linea se quedo arriba al terminar")
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
