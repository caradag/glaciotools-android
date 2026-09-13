package cl.umag.glaciertemp.core

/**
 * Layout del registro del log, derivado del LOG_SIGNATURE de 16 bits que el firmware
 * graba en EEPROM. Las asignaciones de bits vienen del bloque de canales de
 * GlacierTemp_1_cell_v02_claude.ino y coinciden con decode_logh.py.
 *
 * El signature es autodescriptivo: codifica que canales escribieron el log y cuantas
 * sondas DS18B20 habia, de modo que el layout se recupera del propio dato.
 */
data class Field(val name: String, val scale: Double, val decimals: Int)

object LogFormat {

    private val CHANNELS = listOf(
        Triple("Volt",   0x0001, Field("Volt",   1000.0, 2)),
        Triple("Temp",   0x0002, Field("Temp",    100.0, 2)),
        Triple("RH",     0x0004, Field("RH",       10.0, 1)),
        Triple("HAtemp", 0x0008, Field("HAtemp",  100.0, 2)),
    )
    private val ANALOG = listOf(
        "A0" to 0x0020, "A1" to 0x0040, "A2" to 0x0080, "A3" to 0x0100,
    )

    const val DS_BIT = 0x0010
    const val DS_MASK = 0x0E00
    const val DS_SHIFT = 9
    const val INVALID = -32768
    const val TIMESTAMP_BYTES = 4

    /** Version de formato que llevan los bits 12..15. Un valor distinto no es legible. */
    const val FORMAT_VERSION = 1

    fun formatVersion(signature: Int): Int = (signature ushr 12) and 0x0F

    /** Numero de sondas DS18B20 que codifica el signature; 0 si el canal no esta presente. */
    fun dsCount(signature: Int): Int =
        if (signature and DS_BIT == 0) 0 else ((signature and DS_MASK) ushr DS_SHIFT) + 1

    /** Campos que componen un registro, en el orden en que estan grabados. */
    fun fields(signature: Int): List<Field> = buildList {
        CHANNELS.forEach { (_, bit, f) -> if (signature and bit != 0) add(f) }
        repeat(dsCount(signature)) { add(Field("DS$it", 100.0, 2)) }
        ANALOG.forEach { (name, bit) -> if (signature and bit != 0) add(Field(name, 1000.0, 3)) }
    }

    /** Tamano del registro en bytes: 4 de marca de tiempo mas 2 por campo. */
    fun recordBytes(signature: Int): Int = TIMESTAMP_BYTES + 2 * fields(signature).size

    /** Nombre completo de cada canal. El corto es el encabezado de la columna del CSV. */
    fun channelDescription(name: String): String = when {
        name == "Volt" -> "Battery voltage"
        name == "Temp" -> "Air temperature (HDC1080)"
        name == "RH" -> "Relative humidity (HDC1080)"
        name == "HAtemp" -> "High-accuracy temperature (TMP119)"
        name.startsWith("DS") -> "Probe ${name.drop(2)} temperature (DS18B20)"
        name.startsWith("A") -> "Analog input $name"
        else -> name
    }

    /**
     * Lo que la firma significa, en palabras.
     *
     * El numero hexadecimal no es una curiosidad: dice QUE canales escribieron el log, y por
     * eso el decodificador puede recuperar la disposicion del registro a partir del propio
     * dato. Pero eso es util para el programa, no para quien mira la pantalla: a una persona
     * hay que decirle que columnas trae y cuanto ocupa cada registro.
     */
    fun describe(signature: Int): List<String> =
        fields(signature).map { channelDescription(it.name) }

    /**
     * La unidad de un canal, para ensenarla junto al numero.
     *
     * No va en [Field] porque el CSV no la lleva: las columnas del fichero son nombres
     * pelados, y meterla ahi cambiaria el formato de todo lo ya exportado. Aqui es solo
     * para la pantalla, donde un numero suelto no dice si son grados o voltios.
     */
    fun unitOf(name: String): String = when {
        name == "Volt" -> "V"
        name == "RH" -> "%"
        name == "Temp" || name == "HAtemp" -> "°C"
        name.startsWith("DS") -> "°C"
        // Los canales analogicos se guardan en mV con escala 1000: son voltios.
        name.startsWith("A") -> "V"
        else -> ""
    }

    /** Resumen de una linea, para cuando no hay sitio para la lista entera. */
    fun summary(signature: Int): String {
        val f = fields(signature)
        return "${f.size} channels · ${recordBytes(signature)} bytes per record"
    }
}
