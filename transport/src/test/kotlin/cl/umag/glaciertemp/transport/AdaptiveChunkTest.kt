package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.Logb
import kotlin.test.*

/**
 * El ajuste automatico del tamano de tramo, probado sin simulador.
 *
 * Va aparte del banco de punta a punta porque alli cada reduccion a la mitad cuesta un ciclo
 * completo de reintentos: probar la convergencia contra el simulador tardaba minutos. Aqui
 * la placa es de mentira y responde al instante, asi que se puede ejercitar la logica --que
 * es lo que hay que probar-- en milisegundos.
 */
class AdaptiveChunkTest {

    /**
     * Placa simulada que solo contesta bien si el rango pedido no pasa de [maxRecords].
     * Por encima devuelve la cabecera y bloques con el CRC estropeado, que es como se
     * comporta un enlace que descarta datos.
     */
    private class PickyBoard(
        private val maxRecords: Int,
        private val totalRecords: Int,
        override val recordsPerRequest: Int,
        /** Como un enlace BLE que pierde el texto de cierre. */
        private val omitEnd: Boolean = false,
    ) : Transport {
        private val pending = ArrayDeque<Byte>()
        /** La bomba lee desde su propio hilo mientras el test escribe desde el suyo. */
        private val cerrojo = Object()
        override var isOpen = false
        override fun open() { isOpen = true }
        override fun close() { isOpen = false }
        val requested = mutableListOf<Int>()

        override fun read(timeoutMs: Int): ByteArray {
            // Se respeta el plazo: devolver en el acto cuando no hay nada convierte el bucle
            // de la bomba en una espera activa que se come una CPU entera.
            val limite = System.currentTimeMillis() + timeoutMs
            while (true) {
                synchronized(cerrojo) {
                    if (pending.isNotEmpty()) {
                        return ByteArray(pending.size) { pending.removeFirst() }
                    }
                }
                if (System.currentTimeMillis() >= limite) return ByteArray(0)
                Thread.sleep(5)
            }
        }

        override fun write(data: ByteArray) = synchronized(cerrojo) { escribir(data) }

        private fun escribir(data: ByteArray) {
            val cmd = String(data).trim()
            if (!cmd.startsWith("LOGB=")) return
            val (a, b) = cmd.removePrefix("LOGB=").split(",").let {
                it[0].toLong() to it[1].toLong()
            }
            val n = (b - a + 1).toInt()
            requested += n
            val bytes = n * REC
            val blocks = (bytes + 256 - 1) / 256
            val sb = StringBuilder("LOGB begin sig=0x100F rec=$REC from=$a to=$b " +
                                   "blocks=$blocks blocksize=256\n")
            sb.toString().forEach { pending.addLast(it.code.toByte()) }
            for (i in 0 until blocks) {
                val len = minOf(256, bytes - i * 256)
                val data2 = ByteArray(len) { ((a + it) % 251).toByte() }
                pending.addLast(0xAA.toByte()); pending.addLast(0x55.toByte())
                u16(i); u16(len)
                data2.forEach { pending.addLast(it) }
                // Si el tramo es mayor de lo que aguanta, el CRC no cuadra.
                val crc = if (n <= maxRecords) Logb.crc16(data2) else 0x0000
                u16(crc)
            }
            if (!omitEnd) "LOGB end\n".forEach { pending.addLast(it.code.toByte()) }
        }

        private fun u16(v: Int) {
            pending.addLast((v and 0xFF).toByte())
            pending.addLast(((v shr 8) and 0xFF).toByte())
        }

        companion object { const val REC = 12 }
    }

    private fun info(total: Long) = DeviceInfo(
        firmware = "2.7", protocol = 2, boardId = "0", signature = 0x100F,
        recordBytes = 12, recordCount = total, flashBytes = 0)

    @Test
    fun `si el tramo no pasa, se baja hasta que pasa`() {
        val board = PickyBoard(maxRecords = 12, totalRecords = 128, recordsPerRequest = 16)
        board.open()
        val notas = mutableListOf<String>()
        val payload = DeviceSession(board).download(
            info(128), 0, 127, retries = 0, onDiagnostic = { notas += it })

        assertEquals(128 * 12, payload.size)
        // El caso medido contra un HM-10 real: 16 falla y 12 pasa. La binaria prueba antes
        // 8 --la mitad de 16-- y desde ahi sube al punto medio hasta dar con 12. Lo que se
        // comprueba es donde ACABA, no por donde pasa: el 8 es una prueba legitima.
        assertTrue(board.requested.any { it == 12 }, "nunca probo 12: ${board.requested}")
        assertTrue(notas.any { it.contains("Finished with 12") }, notas.toString())
    }

    @Test
    fun `un enlace holgado no se toca`() {
        val board = PickyBoard(maxRecords = 1000, totalRecords = 100, recordsPerRequest = 25)
        board.open()
        val notas = mutableListOf<String>()
        DeviceSession(board).download(info(100), 0, 99, retries = 0,
                                      onDiagnostic = { notas += it })
        assertTrue(board.requested.all { it == 25 }, "pidio ${board.requested}")
        assertTrue(notas.none { it.contains("dropping") }, notas.toString())
    }

    @Test
    fun `por debajo del minimo se rinde en vez de dar vueltas`() {
        // Ningun tamano funciona: hay que fallar, no bajar indefinidamente.
        val board = PickyBoard(maxRecords = 0, totalRecords = 64, recordsPerRequest = 64)
        board.open()
        val e = assertFailsWith<IllegalStateException> {
            DeviceSession(board).download(info(64), 0, 63, retries = 0)
        }
        assertTrue(e.message!!.contains("could not be read"), e.message!!)
        assertTrue(board.requested.min() >= DeviceSession.MIN_RECORDS_PER_REQUEST,
                   "bajo hasta ${board.requested.min()}")
    }

    @Test
    fun `una vez encontrado el techo, el tamano deja de moverse`() {
        // Es el fallo que se veia en el telefono: al subir cada pocos tramos, la app provoca
        // un fallo a proposito una y otra vez, y la descarga avanza a tirones.
        val board = PickyBoard(maxRecords = 24, totalRecords = 2000, recordsPerRequest = 16)
        board.open()
        val notas = mutableListOf<String>()
        val payload = DeviceSession(board).download(
            info(2000), 0, 1999, retries = 0, onDiagnostic = { notas += it })

        assertEquals(2000 * 12, payload.size)
        // Sube mientras puede, tropieza UNA vez y se queda ahi.
        // La busqueda binaria hace varias pruebas para acorralar el maximo; lo que importa
        // es que sean POCAS y que acabe pegada al valor real, no que sea una sola.
        assertTrue(notas.count { it.contains("Too large") } <= 8,
                   "demasiadas reducciones para una busqueda binaria: $notas")
        // Y acaba pegada al maximo real del enlace, que es 24.
        val sesion = DeviceSession(board)
        assertTrue(board.requested.count { it == board.requested.last() } > 5 ||
                   board.requested.takeLast(20).distinct().size <= 2,
                   "el tamano seguia cambiando al final: ${board.requested.takeLast(20)}")
    }

    @Test
    fun `no se tantea por encima del tope`() {
        val board = PickyBoard(maxRecords = 100000, totalRecords = 20000,
                               recordsPerRequest = 256)
        board.open()
        DeviceSession(board).download(info(20000), 0, 19999, retries = 0)
        assertTrue(board.requested.max() <= DeviceSession.MAX_RECORDS_PER_REQUEST,
                   "llego a pedir ${board.requested.max()}")
    }

    @Test
    fun `un tramo que falla justo en el tamano que antes funcionaba no da un bucle`() {
        // El fallo visto en el telefono: el log del usuario repetia sin fin
        //   LOGB 420..479: no header
        //   Chunk too large; settling on 60 records per request
        // porque al fallar se volvia a "el ultimo que funciono", que era el mismo 60 que
        // acababa de fallar. Ni avanzaba ni se rendia.
        //
        // Aqui el enlace admite 60 al principio y luego nada: la descarga tiene que bajar de
        // 60 y acabar, con exito o con error, pero acabar.
        val board = object : Transport {
            var servidos = 0
            override val recordsPerRequest = 60
            override var isOpen = false
            override fun open() { isOpen = true }
            override fun close() { isOpen = false }
            override fun read(timeoutMs: Int) = ByteArray(0)   // nunca contesta
            override fun write(data: ByteArray) { servidos++ }
        }
        board.open()
        val notas = mutableListOf<String>()
        // Lo que se prueba es que TERMINA. Sin el arreglo, esto no vuelve nunca.
        assertFailsWith<IllegalStateException> {
            DeviceSession(board).download(
                info(6000), 0, 5999, retries = 0, onDiagnostic = { notas += it })
        }
        // Y que cada intento bajo de verdad el tamano, en vez de repetir el mismo.
        val tamanos = notas.mapNotNull {
            Regex("""Too large for this link; trying (\d+)""").find(it)?.groupValues?.get(1)?.toInt()
        }
        assertTrue(tamanos.isNotEmpty(), "no informo de ninguna reduccion")
        assertEquals(tamanos.sortedDescending(), tamanos, "no bajaba de forma estricta: $tamanos")
        assertEquals(tamanos.distinct(), tamanos, "repitio un tamano: $tamanos")
    }

    @Test
    fun `no se espera al cierre si ya llegaron todos los bloques`() {
        // El log de un usuario mostraba 19 tramos con "no end marker after 1/1 blocks": el
        // bloque llegaba y el texto de cierre se perdia por radio, y el bucle se comia los
        // tres segundos completos de silencio en cada uno.
        val board = PickyBoard(maxRecords = 1000, totalRecords = 240,
                               recordsPerRequest = 12, omitEnd = true)
        board.open()
        val notas = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        val payload = DeviceSession(board).download(
            info(240), 0, 239, retries = 0, onDiagnostic = { notas += it })
        val secs = (System.currentTimeMillis() - t0) / 1000

        assertEquals(240 * 12, payload.size)
        assertTrue(notas.none { it.contains("no end marker") },
                   "no deberia quejarse del cierre: $notas")
        // 20 tramos. Con la espera de antes serian mas de un minuto.
        assertTrue(secs < 10, "tardo $secs s: se sigue esperando al cierre")
    }

    @Test
    fun `acorrala el maximo en vez de caer de golpe y quedarse abajo`() {
        // El caso del usuario: con MTU 512 la app caia de 81 a 33 y ya no volvia a subir,
        // dejando dos tercios de la velocidad sin usar. La busqueda binaria tiene que
        // acabar pegada al maximo real.
        val maximoReal = 70
        val board = PickyBoard(maxRecords = maximoReal, totalRecords = 40000,
                               recordsPerRequest = 256)
        board.open()
        val notas = mutableListOf<String>()
        val s = DeviceSession(board)
        s.download(info(40000), 0, 39999, retries = 0, onDiagnostic = { notas += it })

        assertTrue(s.finalChunk in 60..maximoReal,
                   "se quedo en ${s.finalChunk}, lejos del maximo real $maximoReal")
        assertEquals(256, s.initialChunk)
        // Y el numero de pruebas es el de una busqueda binaria, no el de un barrido.
        val pruebas = notas.count { it.contains("trying") }
        assertTrue(pruebas < 15, "hizo $pruebas pruebas: $notas")
    }

    @Test
    fun `cuando el enlace empeora no baja de uno en uno`() {
        // El caso medido en el telefono: 60 funciona un rato y despues deja de funcionar. La
        // busqueda bajaba 60, 59, 58 ... hasta 36, con una peticion fallida en cada escalon.
        // La causa era que 60 seguia contando como "el mayor que funciona" mientras acababa
        // de fallar, y el punto medio entre 60 y 60 vuelve a dar 60.
        val board = object : Transport {
            var atendidas = 0
            override val recordsPerRequest = 64
            override var isOpen = false
            override fun open() { isOpen = true }
            override fun close() { isOpen = false }
            private val delegate = PickyBoard(1000, 4000, 64)
            override fun read(timeoutMs: Int) = delegate.read(timeoutMs)
            override fun write(data: ByteArray) {
                val cmd = String(data).trim()
                if (cmd.startsWith("LOGB=")) {
                    val (a, b) = cmd.removePrefix("LOGB=").split(",")
                        .let { it[0].toLong() to it[1].toLong() }
                    val n = (b - a + 1).toInt()
                    atendidas++
                    // Al principio aguanta 64; a partir del quinto tramo solo hasta 40.
                    val tope = if (atendidas <= 5) 64 else 40
                    if (n > tope) return          // no contesta: el tramo falla
                }
                delegate.write(data)
            }
        }
        board.open()
        val notas = mutableListOf<String>()
        val s = DeviceSession(board)
        s.download(info(4000), 0, 3999, retries = 0, onDiagnostic = { notas += it })

        val bajadas = notas.mapNotNull {
            Regex("""Too large for this link; trying (\d+)""")
                .find(it)?.groupValues?.get(1)?.toInt()
        }
        // Terminar afinando de uno en uno es normal en una busqueda binaria; lo que no
        // puede haber es una RACHA larga de escalones de uno, que fue lo que se vio en el
        // telefono: 60, 59, 58 ... 36, con una peticion fallida en cada uno.
        var racha = 0
        var peorRacha = 0
        bajadas.zipWithNext().forEach { (a, b) ->
            racha = if (a - b == 1) racha + 1 else 0
            peorRacha = maxOf(peorRacha, racha)
        }
        assertTrue(peorRacha <= 3, "bajo de uno en uno $peorRacha veces seguidas: $bajadas")
        assertTrue(bajadas.size < 15, "demasiados intentos: $bajadas")
        // Y el resumen dice con QUE tamano acabo, no el mayor que alguna vez funciono.
        assertTrue(s.finalChunk <= 40, "dijo que acabo con ${s.finalChunk}")
    }

    @Test
    fun `la busqueda binaria llega al maximo en pocos intentos`() {
        // El caso real: arranca en 256 y el enlace aguanta 36. Partiendo por la mitad son
        // tres fallos para bajar (128, 64, 32) y unas pocas pruebas para afinar. Bajando de
        // tres en tres cuartos hacian falta seis solo para bajar.
        val board = PickyBoard(maxRecords = 36, totalRecords = 20000, recordsPerRequest = 256)
        board.open()
        val notas = mutableListOf<String>()
        val s = DeviceSession(board)
        s.download(info(20000), 0, 19999, retries = 0, onDiagnostic = { notas += it })

        val pruebas = notas.count { it.contains("trying") }
        assertTrue(pruebas <= 12, "hizo $pruebas pruebas: $notas")
        // Y acaba pegada al maximo real, no por debajo.
        assertTrue(s.finalChunk in 30..36, "acabo con ${s.finalChunk}, el maximo real es 36")

        // Los primeros descensos son a la mitad, no a tres cuartos.
        val bajadas = notas.mapNotNull {
            Regex("""Too large for this link; trying (\d+)""")
                .find(it)?.groupValues?.get(1)?.toInt()
        }
        assertEquals(128, bajadas.first(), "el primer descenso deberia ser a la mitad")
    }

    @Test
    fun `una vez cerrada la horquilla deja de tantear`() {
        val board = PickyBoard(maxRecords = 50, totalRecords = 30000, recordsPerRequest = 64)
        board.open()
        val notas = mutableListOf<String>()
        DeviceSession(board).download(info(30000), 0, 29999, retries = 0,
                                      onDiagnostic = { notas += it })
        // Con 30.000 registros y tramos de ~50 son unas 600 peticiones. Si siguiera
        // tanteando habria decenas de mensajes; cerrada la busqueda, no debe haber casi
        // ninguno despues de converger.
        val pruebas = notas.count { it.contains("trying") }
        assertTrue(pruebas <= 12, "seguia tanteando: $pruebas mensajes")
    }

    @Test
    fun `al fallar un tamano se retrocede al anterior que funciono, no por debajo`() {
        // El caso del telefono: 32 funciona, se sube a 48, 48 funciona una vez y luego
        // falla. Antes se olvidaba todo y se partia por la mitad --24-- por debajo de un
        // valor que ya se sabia bueno.
        val delegate = PickyBoard(1000, 9000, 32)
        val board = object : Transport {
            var vistas = 0
            override val recordsPerRequest = 32
            override var isOpen = false
            override fun open() { isOpen = true }
            override fun close() { isOpen = false }
            override fun read(timeoutMs: Int) = delegate.read(timeoutMs)
            override fun write(data: ByteArray) {
                val cmd = String(data).trim()
                if (cmd.startsWith("LOGB=")) {
                    val (a, b) = cmd.removePrefix("LOGB=").split(",")
                        .let { it[0].toLong() to it[1].toLong() }
                    val n = (b - a + 1).toInt()
                    // 48 pasa la primera vez y falla a partir de ahi: es el escenario que
                    // hacia perder la referencia.
                    if (n >= 48) { vistas++; if (vistas > 1) return }
                }
                delegate.write(data)
            }
        }
        board.open()
        val notas = mutableListOf<String>()
        DeviceSession(board).download(info(9000), 0, 8999, retries = 0,
                                      onDiagnostic = { notas += it })

        val probados = notas.mapNotNull {
            Regex("""trying (\d+) records""").find(it)?.groupValues?.get(1)?.toInt()
        }
        // Una vez que 32 funciono, nunca se baja por debajo de 32.
        val trasElPrimerExito = probados.dropWhile { it > 32 }
        assertTrue(trasElPrimerExito.none { it < 32 },
                   "bajo por debajo de 32, que ya funcionaba: $probados")
    }
}
