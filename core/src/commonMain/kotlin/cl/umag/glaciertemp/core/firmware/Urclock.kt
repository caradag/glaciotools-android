package cl.umag.glaciertemp.core.firmware

/**
 * Lo que hace falta para subir un firmware a la placa como `avrdude -c urclock`, sin E/S:
 * leer el .hex, comprobar que cabe y ajustar los vectores que exige el bootloader.
 *
 * LA PLACA. Un ATmega328P con Urboot, un bootloader "de vector": el chip arranca en la
 * direccion 0 (el fusible BOOTRST no esta programado), y es el vector de reset el que tiene
 * que saltar al bootloader. A su vez, el bootloader salta al firmware por un vector propio,
 * el 25 (SPM_READY) en esta placa. Ninguno de los dos saltos viene en el .hex que compila
 * el IDE: los pone quien sube el firmware. Si quedan mal, el firmware queda escrito y no
 * arranca, o el bootloader deja de alcanzarse con un reset.
 *
 * Todo lo de aqui se comprobo contra una carga real con avrdude (tools/urclock-golden): a
 * partir de `original.hex`, [patch] tiene que dar exactamente la flash que avrdude dejo en
 * la placa (`readback.hex`). Ver UrclockTest.
 */
object Urclock {

    /** Flash del ATmega328P. */
    const val FLASH_SIZE = 32_768

    /** Pagina de programacion del ATmega328P. */
    const val PAGE_SIZE = 128

    /** Bytes de cada vector: `jmp` de 4 bytes en un chip de mas de 8 KB. */
    const val VECTOR_SIZE = 4

    /** Vectores de interrupcion del ATmega328P (0 = reset .. 25 = SPM_READY). */
    const val VECTOR_COUNT = 26

    /**
     * Version minima de Urboot cuyo formato de tabla sabemos leer y ajustar. Las versiones
     * se codifican en octal: 075 es la 7.5, a partir de la cual la tabla trae el numero de
     * paginas del bootloader. La placa lleva la 7.7 (077).
     */
    const val MIN_URBOOT_VERSION = 0b0011_1101   // 075 octal = 7.5

    /**
     * Los seis bytes al final de la flash con que Urboot se describe a si mismo:
     * paginas que ocupa, vector por el que salta al firmware, un `rjmp` a su rutina de
     * escritura, capacidades y version. Ver `ur_initstruct` en urclock.c de avrdude.
     */
    data class BootInfo(
        val pages: Int,
        val vectorNumber: Int,
        val capabilities: Int,
        val version: Int,
    ) {
        /** Donde empieza el bootloader: lo que queda por debajo es para el firmware. */
        val start: Int get() = FLASH_SIZE - pages * PAGE_SIZE

        /** "7.7" para la version 077 octal. */
        val versionText: String get() = "${version shr 3}.${version and 7}"
    }

    /** Lee la tabla de los seis ultimos bytes de la flash. Null si no es una de Urboot que entendamos. */
    fun bootInfo(top6: ByteArray): BootInfo? {
        if (top6.size != 6) return null
        val b = IntArray(6) { top6[it].toInt() and 0xFF }
        val info = BootInfo(pages = b[0] and 0x7F, vectorNumber = b[1] and 0x7F,
                            capabilities = b[4], version = b[5])
        val bytes = info.pages * PAGE_SIZE
        return info.takeIf {
            it.version in MIN_URBOOT_VERSION..0x67 && bytes in 64..2048 &&
                it.vectorNumber in 1 until VECTOR_COUNT
        }
    }

    // ------------------------------------------------------------------- .hex

    class HexException(message: String) : Exception(message)

    /**
     * Un firmware leido de un .hex: los bytes que el archivo define, en su direccion.
     *
     * [defined] marca que direcciones traia el archivo, porque no es lo mismo un 0xFF escrito
     * que un hueco: solo se reescriben las paginas que el archivo toca.
     */
    class Image(val data: ByteArray, val defined: BooleanArray) {
        /** Una direccion despues del ultimo byte definido. */
        val end: Int get() = defined.indexOfLast { it } + 1

        /** Bytes de programa: lo que el IDE informa como "Sketch uses". */
        val size: Int get() = defined.count { it }
    }

    /**
     * Lee un Intel HEX de firmware de forma ESTRICTA: una linea con la suma mal, un registro
     * desconocido o una direccion repetida con otro valor rechazan el archivo entero. Es lo
     * contrario del lector de LOGH ([cl.umag.glaciertemp.core.IntelHex]), que salta las lineas
     * danadas y sigue: ahi se rescatan datos; aqui se escriben en el chip.
     *
     * Acepta tambien el `…with_bootloader.hex` del IDE: lo que cae en el bootloader
     * ([bootStart] en adelante) se descarta y se informa en [ParsedHex.droppedBootBytes],
     * porque el bootloader se protege a si mismo y no se puede ni se debe reescribir.
     */
    fun parseHex(text: String, bootStart: Int): ParsedHex {
        val data = ByteArray(FLASH_SIZE) { 0xFF.toByte() }
        val defined = BooleanArray(FLASH_SIZE)
        var base = 0
        var dropped = 0
        var sawEof = false
        for ((n, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sawEof) throw HexException("line ${n + 1}: data after the end-of-file record")
            if (!line.startsWith(":")) throw HexException("line ${n + 1}: not an Intel HEX record")
            val b = hexBytes(line.substring(1))
                ?: throw HexException("line ${n + 1}: not hexadecimal")
            if (b.size < 5 || b.size != (b[0].toInt() and 0xFF) + 5) {
                throw HexException("line ${n + 1}: wrong length")
            }
            if (b.sumOf { it.toInt() and 0xFF } and 0xFF != 0) {
                throw HexException("line ${n + 1}: bad checksum")
            }
            val len = b[0].toInt() and 0xFF
            val addr = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
            when (val type = b[3].toInt() and 0xFF) {
                0x00 -> for (k in 0 until len) {
                    val a = base + addr + k
                    if (a >= FLASH_SIZE) throw HexException("line ${n + 1}: address 0x${a.toString(16)} beyond the flash")
                    if (a >= bootStart) { dropped++; continue }
                    if (defined[a] && data[a] != b[4 + k]) {
                        throw HexException("line ${n + 1}: address 0x${a.toString(16)} defined twice")
                    }
                    data[a] = b[4 + k]; defined[a] = true
                }
                0x01 -> sawEof = true
                0x02 -> base = (((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)) shl 4
                0x04 -> base = (((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)) shl 16
                0x03, 0x05 -> {}   // direccion de arranque: no aplica a un AVR
                else -> throw HexException("line ${n + 1}: unknown record type $type")
            }
        }
        if (!sawEof) throw HexException("the file ends without an end-of-file record (truncated?)")
        val img = Image(data, defined)
        if (img.size == 0) throw HexException("the file holds no program bytes")
        return ParsedHex(img, dropped)
    }

    class ParsedHex(val image: Image, val droppedBootBytes: Int)

    private fun hexBytes(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return ByteArray(s.length / 2) { i ->
            (s.substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: return null).toByte()
        }
    }

    // ------------------------------------------------------------- ajuste

    class PatchException(message: String) : Exception(message)

    /**
     * El firmware tal como tiene que quedar en la flash, con los dos saltos que pone
     * `avrdude -c urclock` (`urclock_flash_readhook`/`set_resetvector` en urclock.c):
     *
     * - Vector 25 (el de [BootInfo.vectorNumber]): `jmp` al inicio real del firmware, que se
     *   saca del vector de reset del propio .hex.
     * - Vector 0 (reset): salto al bootloader. Un `rjmp` si alcanza --en un chip de 32 KB el
     *   contador de programa da la vuelta, y el bootloader, al final, queda a unos cientos de
     *   bytes "hacia atras" de la direccion 0--; si no, `jmp`. Con `rjmp` solo cambia la
     *   primera palabra: la segunda queda como venia en el .hex, igual que con avrdude.
     *
     * Exige lo mismo que avrdude: que el .hex empiece en la direccion 0 con una tabla de
     * vectores completa hecha de `jmp`. Si no, no es un firmware de Arduino normal y no se
     * sabe donde arranca.
     */
    fun patch(image: Image, boot: BootInfo): Image {
        val tabla = VECTOR_COUNT * VECTOR_SIZE
        if ((0 until tabla).any { !image.defined[it] }) {
            throw PatchException("the file does not start with a full interrupt vector table at address 0")
        }
        for (v in 0 until VECTOR_COUNT) {
            if (jmpTarget(image.data, v * VECTOR_SIZE) == null) {
                throw PatchException("vector $v is not a jmp: not a normal Arduino firmware")
            }
        }
        if (image.end > boot.start) {
            throw PatchException("the firmware (${image.end} B) does not fit below the bootloader " +
                                 "(${boot.start} B available)")
        }
        val appStart = jmpTarget(image.data, 0)!!
        if (appStart >= boot.start) throw PatchException("the reset vector points into the bootloader")

        val data = image.data.copyOf()
        val defined = image.defined.copyOf()

        // Vector del bootloader -> inicio del firmware.
        putJmp(data, boot.vectorNumber * VECTOR_SIZE, appStart)

        // Vector de reset -> bootloader.
        val rjmp = rjmpTo(from = 0, to = boot.start)
        if (rjmp != null) {
            data[0] = (rjmp and 0xFF).toByte(); data[1] = (rjmp shr 8).toByte()
        } else {
            putJmp(data, 0, boot.start)
        }
        return Image(data, defined)
    }

    /** Destino en bytes de un `jmp` (0x940C + palabra) en [at], o null si no es un `jmp`. */
    fun jmpTarget(d: ByteArray, at: Int): Int? {
        val w0 = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)
        if (w0 and 0xFE0E != 0x940C) return null
        val k = (d[at + 2].toInt() and 0xFF) or ((d[at + 3].toInt() and 0xFF) shl 8)
        val hi = ((w0 shr 3) and 0x3E) or (w0 and 1)          // bits 21..16 de la palabra
        return ((hi shl 16) or k) * 2
    }

    private fun putJmp(d: ByteArray, at: Int, targetBytes: Int) {
        val k = targetBytes / 2
        d[at] = 0x0C; d[at + 1] = 0x94.toByte()
        d[at + 2] = (k and 0xFF).toByte(); d[at + 3] = ((k shr 8) and 0xFF).toByte()
    }

    /**
     * Opcode de un `rjmp` desde [from] a [to] (bytes), contando la vuelta de la flash, o null
     * si no alcanza (+-2 K palabras). `rjmp k` salta a PC+1+k, en palabras.
     */
    private fun rjmpTo(from: Int, to: Int): Int? {
        val palabras = FLASH_SIZE / 2
        var k = (to / 2 - (from / 2 + 1)) % palabras
        if (k >= palabras / 2) k -= palabras
        if (k < -palabras / 2) k += palabras
        if (k !in -2048..2047) return null
        return 0xC000 or (k and 0x0FFF)
    }

    // ------------------------------------------------------------- paginas

    /**
     * Las paginas que hay que escribir: las que tocan algun byte del archivo, completas (lo
     * que el archivo no define dentro de una pagina va como 0xFF, igual que con avrdude). Sin
     * borrado previo: el resto de la flash queda como esta, que es lo que hace el IDE con `-D`.
     *
     * La pagina 0 va AL FINAL: hasta entonces el vector 25 sigue saltando al firmware
     * anterior, asi que una carga cortada a mitad no deja un salto a codigo a medio escribir.
     * El vector de reset no corre riesgo en ningun orden: el bootloader lo protege.
     */
    fun pages(image: Image): List<Int> {
        val out = (0 until FLASH_SIZE / PAGE_SIZE).filter { p ->
            (p * PAGE_SIZE until (p + 1) * PAGE_SIZE).any { image.defined[it] }
        }.map { it * PAGE_SIZE }
        return out.filter { it != 0 } + out.filter { it == 0 }
    }

    /** Los 128 bytes de la pagina que empieza en [address]. */
    fun page(image: Image, address: Int): ByteArray =
        image.data.copyOfRange(address, address + PAGE_SIZE)
}
