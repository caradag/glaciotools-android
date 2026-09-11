package cl.umag.glaciertemp.transport

/**
 * Un enlace con la placa. Cable y Bluetooth hablan el MISMO protocolo de texto sobre
 * la misma UART, asi que la app necesita una sola capa de comandos y varias
 * implementaciones de esta interfaz.
 */
interface Transport : AutoCloseable {
    /**
     * Registros por peticion en una descarga.
     *
     * Un enlace por cable aguanta el log entero de una vez. Un puente serie-BLE, no: recibe
     * de la placa a 11,5 kB/s y solo mueve 1-5 kB/s por radio, asi que ante una peticion
     * larga su buffer interno se desborda y descarta bytes sin avisar. Pedir por tramos deja
     * que la radio se vacie entre peticiones, porque la placa solo envia lo que se le pide.
     *
     * 0 significa sin trocear.
     */
    val recordsPerRequest: Int get() = 0

    fun open()
    fun write(data: ByteArray)
    /** Devuelve lo disponible, o un array vacio si no llego nada antes del timeout. */
    fun read(timeoutMs: Int): ByteArray
    val isOpen: Boolean
}

/**
 * Transporte capaz de cambiar la velocidad de linea a mitad de sesion.
 *
 * Lo implementa el cable y NO el Bluetooth: en BLE la velocidad la fija el modulo por su
 * lado y la radio es mucho mas lenta que la UART, asi que cambiarla no ganaria nada. Que
 * sea una interfaz aparte es lo que hace imposible pedirle a un enlace BLE algo que no
 * puede hacer.
 */
interface BaudSwitchable {
    fun setBaudRate(baud: Int)
}

fun Transport.writeLine(s: String) = write((s + "\n").toByteArray())
