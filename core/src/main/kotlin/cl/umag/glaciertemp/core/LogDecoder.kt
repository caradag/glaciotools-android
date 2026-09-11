package cl.umag.glaciertemp.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDateTime

/** Un registro decodificado. [values] es null en las posiciones sin lectura valida. */
data class Record(val time: LocalDateTime, val values: List<Double?>)

object LogDecoder {

    /** mktime2() del firmware cuenta segundos desde esta epoca. */
    val EPOCH: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)

    /**
     * Decodifica los bytes crudos del log. Los registros en blanco -- marca de tiempo 0
     * (nunca escrito) o 0xFFFFFFFF (flash borrada) -- se omiten, igual que decode_logh.py.
     */
    fun decode(data: ByteArray, signature: Int): List<Record> {
        val fields = LogFormat.fields(signature)
        val rec = LogFormat.recordBytes(signature)
        val out = ArrayList<Record>()
        var off = 0
        while (off + rec <= data.size) {
            val t = readU32LE(data, off)
            if (t != 0L && t != 0xFFFFFFFFL) {
                val vals = fields.mapIndexed { i, f ->
                    val raw = readI16LE(data, off + LogFormat.TIMESTAMP_BYTES + 2 * i)
                    // La humedad nunca es negativa: cualquier valor negativo es una lectura
                    // fallida, lo que ademas cubre el centinela -1 del firmware antiguo.
                    if (raw == LogFormat.INVALID || (f.name == "RH" && raw < 0)) null
                    else raw / f.scale
                }
                out.add(Record(EPOCH.plus(Duration.ofSeconds(t)), vals))
            }
            off += rec
        }
        return out
    }

    /** Cuenta de registros en blanco, para poder reportarla como hace el oraculo. */
    fun blankCount(data: ByteArray, signature: Int): Int {
        val rec = LogFormat.recordBytes(signature)
        var off = 0; var blank = 0
        while (off + rec <= data.size) {
            val t = readU32LE(data, off)
            if (t == 0L || t == 0xFFFFFFFFL) blank++
            off += rec
        }
        return blank
    }

    private fun readU32LE(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun readI16LE(b: ByteArray, o: Int): Int =
        (((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort()).toInt()

    /**
     * Formatea como lo hace Python. `f"{v:.2f}"` redondea HALF_EVEN sobre el valor exacto
     * del double; String.format de Java usa HALF_UP y difiere en los casos limite, lo que
     * produciria un CSV distinto del que genera decode_logh.py.
     */
    fun formatValue(v: Double?, decimals: Int): String =
        if (v == null) "NaN"
        else BigDecimal(v).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
}
