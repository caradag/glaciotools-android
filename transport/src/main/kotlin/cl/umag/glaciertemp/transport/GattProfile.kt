package cl.umag.glaciertemp.transport

/**
 * Descubrimiento del perfil GATT de un puente serie-BLE.
 *
 * El modulo Bluetooth es un accesorio externo intercambiable, asi que la app no puede
 * codificar los UUID de ningun modelo concreto: un HM-10 expone `FFE0`/`FFE1` y un
 * modulo basado en Nordic expone el Nordic UART Service, con caracteristicas distintas
 * para escribir y para recibir. Este fichero es Kotlin puro justamente para poder probar
 * esa eleccion sin radio: es la parte donde se equivoca uno, no en la llamada a la API.
 */

/** Modelo plano de lo que descubre Android, para no arrastrar sus clases hasta aqui. */
data class GattCharacteristic(
    val uuid: String,
    val notify: Boolean = false,
    val indicate: Boolean = false,
    val write: Boolean = false,
    val writeNoResponse: Boolean = false,
) {
    val canReceive: Boolean get() = notify || indicate
    val canSend: Boolean get() = write || writeNoResponse
}

data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)

data class SerialGattProfile(
    /** Nombre para poder decirle al usuario que modulo se detecto. */
    val name: String,
    val service: String,
    /** Caracteristica a la que la app ESCRIBE. */
    val writeChar: String,
    /** Caracteristica que la app RECIBE por notificacion. */
    val notifyChar: String,
    /**
     * Escribir sin respuesta es mucho mas rapido, pero no todos los modulos lo aceptan y
     * en algunos pierde datos: se usa solo si la caracteristica lo declara.
     */
    val writeNoResponse: Boolean,
)

object GattProfiles {

    /** Expande la forma corta de 16 bits al UUID completo, y normaliza a minusculas. */
    fun normalize(uuid: String): String {
        val u = uuid.trim().lowercase()
        return when (u.length) {
            4 -> "0000$u-0000-1000-8000-00805f9b34fb"
            8 -> "$u-0000-1000-8000-00805f9b34fb"
            else -> u
        }
    }

    private fun uuid(s: String) = normalize(s)

    /**
     * Servicios que NO son puentes serie. Se excluyen del descubrimiento generico para que
     * no gane el servicio de informacion del dispositivo o el de bateria.
     */
    private val GENERIC = setOf(
        uuid("1800"), // GAP
        uuid("1801"), // GATT
        uuid("180a"), // Device Information
        uuid("180f"), // Battery
        uuid("1804"), // Tx Power
        uuid("fe59"), // Nordic DFU
    )

    private data class Known(
        val name: String, val service: String,
        val write: String, val notify: String,
    )

    /**
     * Perfiles conocidos, en orden de preferencia. Basta con uno que coincida; el
     * descubrimiento generico solo entra cuando ninguno lo hace.
     */
    private val KNOWN = listOf(
        // HM-10 y sus clones (AT-09, CC41-A, BT-05, JDY-08): una sola caracteristica
        // sirve para escribir y para recibir.
        Known("HM-10 and compatibles", "ffe0", "ffe1", "ffe1"),
        // Nordic UART Service. Los nombres van desde el punto de vista del MODULO: a lo
        // que el llama RX es donde escribe la app, y su TX es lo que la app recibe.
        Known("Nordic UART Service",
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        // Microchip RN4870/71 y modulos ISSC, "transparent UART".
        Known("Microchip transparent UART",
            "49535343-fe7d-4ae5-8fa9-9fafd205e455",
            "49535343-8841-43f4-a8d4-ecbe34729bb3",
            "49535343-1e4d-4bd9-ba61-23c647249616"),
        // Telit / STM BlueNRG y modulos que exponen FFE0 con dos caracteristicas.
        Known("Two-characteristic FFE0 serial bridge", "ffe0", "ffe2", "ffe1"),
    )

    /**
     * Elige el par de caracteristicas con el que hablar, o null si el dispositivo no
     * parece un puente serie.
     */
    fun select(services: List<GattService>): SerialGattProfile? {
        val byUuid = services.associateBy { normalize(it.uuid) }

        for (k in KNOWN) {
            val svc = byUuid[normalize(k.service)] ?: continue
            val chars = svc.characteristics.associateBy { normalize(it.uuid) }
            val w = chars[normalize(k.write)] ?: continue
            val n = chars[normalize(k.notify)] ?: continue
            if (!w.canSend || !n.canReceive) continue
            return SerialGattProfile(k.name, normalize(svc.uuid), normalize(w.uuid),
                normalize(n.uuid), w.writeNoResponse)
        }

        // Descubrimiento generico: cualquier servicio propietario que ofrezca por donde
        // escribir y por donde recibir. Prefiere la caracteristica que hace las dos cosas,
        // que es como funciona el HM-10 y sus derivados.
        for (svc in services) {
            if (normalize(svc.uuid) in GENERIC) continue
            val both = svc.characteristics.firstOrNull { it.canSend && it.canReceive }
            if (both != null) {
                return SerialGattProfile("Serial bridge detected", normalize(svc.uuid),
                    normalize(both.uuid), normalize(both.uuid), both.writeNoResponse)
            }
            val w = svc.characteristics.firstOrNull { it.canSend }
            val n = svc.characteristics.firstOrNull { it.canReceive }
            if (w != null && n != null) {
                return SerialGattProfile("Serial bridge detected", normalize(svc.uuid),
                    normalize(w.uuid), normalize(n.uuid), w.writeNoResponse)
            }
        }
        return null
    }

    /**
     * Payload de la escritura al descriptor CCCD que activa las notificaciones. Sin esto
     * el modulo se conecta, acepta comandos y no devuelve NADA, que es el fallo mas
     * frecuente al escribir un cliente BLE a mano.
     */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    val ENABLE_NOTIFICATION: ByteArray = byteArrayOf(0x01, 0x00)
    val ENABLE_INDICATION: ByteArray = byteArrayOf(0x02, 0x00)

    /**
     * Bytes utiles por escritura para un MTU dado: ATT gasta tres en la cabecera del
     * Write Command. Con el MTU por defecto de 23 quedan los 20 clasicos.
     */
    fun payloadForMtu(mtu: Int): Int = (mtu - 3).coerceAtLeast(20)
}
