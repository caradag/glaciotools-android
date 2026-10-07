package cl.umag.glaciertemp.transport

import cl.umag.glaciertemp.core.firmware.Urclock

/**
 * Un enlace que puede reiniciar la placa con la linea DTR (el condensador de auto-reset del
 * conector de programacion). Solo el cable: por Bluetooth no hay DTR.
 */
interface ResetLine {
    fun pulseReset()
}

class FirmwareUploadException(message: String, val boardState: BoardState) : Exception(message)

/** Como queda la placa si la carga falla. Es lo que hay que decirle a quien la tiene en la mano. */
enum class BoardState {
    /** No se escribio nada: sigue con el firmware de antes. */
    UNCHANGED,
    /**
     * Se escribieron paginas pero no la 0: el vector 25 sigue saltando al firmware anterior,
     * ahora parcialmente pisado. Puede no arrancar bien; repetir la carga lo resuelve, porque
     * el bootloader sigue respondiendo a cada reset.
     */
    PARTIAL,
}

data class UploadProgress(val stage: String, val done: Int, val total: Int)

/**
 * Sube un firmware por el bootloader Urboot como `avrdude -c urclock -D -xnometadata`, que es
 * como lo sube el IDE. La secuencia y las respuestas se copiaron de una carga real con avrdude
 * contra la placa (tools/urclock-golden/write-trace.txt):
 *
 *   reset por DTR; sincronizar: `30 20` y despues `20 20` hasta que conteste `A0 77`
 *   (esos dos bytes son INSYNC/OK y a la vez el identificador del MCU)
 *   `50 20` entrar en programacion
 *   `03 lo hi n 20` leer n bytes de la flash   -> A0 <datos> 77
 *   `02 lo hi 80 <128 B> 20` escribir una pagina -> A0 77
 *   `51 20` salir: el bootloader arranca el firmware
 *
 * Direcciones en bytes, little-endian. Nada se escribe hasta haber comprobado que al otro lado
 * hay un ATmega328P con un Urboot cuya tabla entendemos. La EEPROM no se toca.
 */
class FirmwareUploader(
    private val transport: Transport,
    private val log: (String) -> Unit = {},
) {
    private var insync = 0xA0
    private var ok = 0x77

    fun upload(
        image: Urclock.Image,
        onProgress: (UploadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Urclock.BootInfo {
        sync()
        cmd(byteArrayOf(0x50, 0x20), 0, "enter programming mode")
        val top = read(Urclock.FLASH_SIZE - 6, 6)
        val boot = Urclock.bootInfo(top) ?: throw FirmwareUploadException(
            "The bootloader is not an Urboot this app knows (table ${hex(top)}). Nothing was written.",
            BoardState.UNCHANGED)
        log("bootloader Urboot ${boot.versionText}, ${boot.pages * Urclock.PAGE_SIZE} B at " +
            "0x${boot.start.toString(16)}, vector ${boot.vectorNumber}")

        val patched = try {
            Urclock.patch(image, boot)
        } catch (e: Urclock.PatchException) {
            throw FirmwareUploadException("${e.message}. Nothing was written.", BoardState.UNCHANGED)
        }
        val pages = Urclock.pages(patched)
        var escritas = 0
        for ((i, addr) in pages.withIndex()) {
            if (isCancelled()) {
                leave()
                throw FirmwareUploadException("Cancelled after $escritas pages.",
                    if (escritas == 0) BoardState.UNCHANGED else BoardState.PARTIAL)
            }
            onProgress(UploadProgress("Writing", i, pages.size))
            val data = Urclock.page(patched, addr)
            val estado = if (escritas == 0) BoardState.UNCHANGED else BoardState.PARTIAL
            writeVerified(addr, data, estado)
            escritas++
        }
        onProgress(UploadProgress("Done", pages.size, pages.size))
        leave()
        return boot
    }

    /** Escribe una pagina y la relee; un reintento si no coincide. */
    private fun writeVerified(addr: Int, data: ByteArray, estado: BoardState) {
        repeat(2) { intento ->
            try {
                cmd(byteArrayOf(0x02, addr.toByte(), (addr shr 8).toByte(), 0x80.toByte()) + data +
                        byteArrayOf(0x20), 0, "write page 0x${addr.toString(16)}")
                val leido = read(addr, Urclock.PAGE_SIZE)
                if (leido.contentEquals(data)) return
                log("page 0x${addr.toString(16)} read back different (attempt ${intento + 1})")
            } catch (e: FirmwareUploadException) {
                if (intento == 1) throw FirmwareUploadException(e.message ?: "", estado)
            }
        }
        throw FirmwareUploadException("Page 0x${addr.toString(16)} does not read back as written.", estado)
    }

    private fun leave() {
        runCatching { cmd(byteArrayOf(0x51, 0x20), 0, "leave programming mode") }
    }

    /**
     * Reset y sincronizacion. Como avrdude: el primer intento lleva el 0x30 que el bootloader
     * usa para medir la velocidad; los siguientes, solo el fin de orden. Hacen falta dos
     * respuestas `A0 77` seguidas: lo que quedara en la linea del firmware anterior (su
     * prompt, sus lecturas) llega antes y no cuenta.
     */
    private fun sync() {
        repeat(3) { reset ->
            (transport as? ResetLine)?.pulseReset()
            sleep(120)
            drain()
            var buenas = 0
            for (intento in 0 until 16) {
                transport.write(if (intento == 0) byteArrayOf(0x30, 0x20) else byteArrayOf(0x20, 0x20))
                val r = readExactly(2, 60)
                if (r != null && (r[0].toInt() and 0xFF) == 0xA0 && (r[1].toInt() and 0xFF) == 0x77) {
                    if (++buenas == 2) {
                        drain()
                        log("bootloader in sync (attempt ${intento + 1}, reset ${reset + 1})")
                        return
                    }
                } else {
                    buenas = 0
                    sleep(32L shl minOf(intento, 3))
                }
            }
            log("no answer from the bootloader after reset ${reset + 1}")
        }
        throw FirmwareUploadException(
            "The bootloader did not answer. Check the USB-serial adapter and its DTR line, or press " +
            "RESET on the board right after starting the update. Nothing was written.",
            BoardState.UNCHANGED)
    }

    /** Envia una orden y exige `A0 <n bytes> 77`. Devuelve los n bytes. */
    private fun cmd(frame: ByteArray, n: Int, what: String): ByteArray {
        transport.write(frame)
        val r = readExactly(n + 2, 500) ?: throw FirmwareUploadException(
            "No answer from the bootloader ($what).", BoardState.UNCHANGED)
        if ((r[0].toInt() and 0xFF) != insync || (r[n + 1].toInt() and 0xFF) != ok) {
            throw FirmwareUploadException("Unexpected answer from the bootloader ($what): ${hex(r)}.",
                BoardState.UNCHANGED)
        }
        return r.copyOfRange(1, n + 1)
    }

    private fun read(addr: Int, n: Int): ByteArray =
        cmd(byteArrayOf(0x03, addr.toByte(), (addr shr 8).toByte(), n.toByte(), 0x20), n,
            "read 0x${addr.toString(16)}")

    private val pendiente = java.io.ByteArrayOutputStream()

    /** Exactamente n bytes, o null si no llegan en [timeoutMs] desde el ultimo que llego. */
    private fun readExactly(n: Int, timeoutMs: Int): ByteArray? {
        var limite = System.currentTimeMillis() + timeoutMs
        while (pendiente.size() < n) {
            val falta = limite - System.currentTimeMillis()
            if (falta <= 0) return null
            val c = transport.read(minOf(falta, 50L).toInt())
            if (c.isNotEmpty()) { pendiente.write(c); limite = System.currentTimeMillis() + timeoutMs }
        }
        val todo = pendiente.toByteArray()
        pendiente.reset()
        pendiente.write(todo, n, todo.size - n)
        return todo.copyOf(n)
    }

    private fun drain() {
        pendiente.reset()
        while (transport.read(20).isNotEmpty()) { /* descartar */ }
    }

    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
