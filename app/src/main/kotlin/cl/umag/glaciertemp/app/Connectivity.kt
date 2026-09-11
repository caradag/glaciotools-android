package cl.umag.glaciertemp.app

import cl.umag.glaciertemp.transport.Transport

/** Un enlace concreto que el usuario puede elegir en la pantalla de conexion. */
sealed interface ConnectionTarget {
    val label: String

    /** Simulador del PC, solo en compilaciones de depuracion. */
    data object TcpDebug : ConnectionTarget {
        override val label = "Simulador (TCP)"
    }

    companion object {
        /**
         * Alias que el EMULADOR resuelve al PC anfitrion. En un telefono real no es
         * ninguna direccion valida y el intento de conexion agota el plazo sin decir por
         * que; de ahi que la app lo detecte y lo explique.
         */
        const val EMULATOR_HOST = "10.0.2.2"
    }

    /** Un adaptador USB-serie enchufado. [id] es el identificador de UsbDevice. */
    data class Usb(val id: Int, override val label: String) : ConnectionTarget

    /** Un modulo BLE, identificado por su direccion, que es lo unico estable. */
    data class Ble(val address: String, override val label: String,
                   val rssi: Int = 0) : ConnectionTarget
}

/**
 * Todo lo que la app necesita de Android para conectarse. Es una interfaz para que
 * [DeviceViewModel] no toque ninguna API de Android y siga probandose sin telefono: la
 * implementacion real vive en [AndroidConnectivity] y en los tests se sustituye.
 */
interface Connectivity {
    /** Adaptadores USB-serie enchufados ahora mismo. */
    fun usbTargets(): List<ConnectionTarget.Usb>

    /** true si Android ya concedio permiso para ese adaptador. */
    fun hasUsbPermission(target: ConnectionTarget.Usb): Boolean

    /** Lanza la peticion de permiso, que Android resuelve de forma asincrona. */
    fun requestUsbPermission(target: ConnectionTarget.Usb)

    /**
     * Permisos de Bluetooth que faltan. Se consulta ANTES de tocar cualquier API de la
     * radio: sin BLUETOOTH_CONNECT, hasta leer la lista de emparejados lanza
     * SecurityException, y desde un onClick de Compose eso cierra la app.
     */
    fun missingBlePermissions(): List<String>

    /** Lanza el dialogo del sistema para los permisos que falten. */
    fun requestBlePermissions()

    /**
     * Escanea y va entregando la lista acumulada.
     *
     * Solo se listan los modulos que ESTAN emitiendo ahora. Antes se sembraba la lista con
     * los emparejados del sistema, y eso llenaba la pantalla de equipos que quiza estaban a
     * kilometros: un modulo emparejado no es un modulo alcanzable.
     */
    fun scanBle(onUpdate: (List<ConnectionTarget.Ble>) -> Unit)
    fun stopBleScan()

    val bluetoothEnabled: Boolean

    fun open(target: ConnectionTarget): Transport
}
