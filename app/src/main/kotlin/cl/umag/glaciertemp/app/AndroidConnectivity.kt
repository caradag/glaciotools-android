package cl.umag.glaciertemp.app

import android.content.Context
import android.hardware.usb.UsbDevice
import cl.umag.glaciertemp.transport.TcpTransport
import cl.umag.glaciertemp.transport.Transport
import cl.umag.glaciertemp.transport.android.BleScanner
import cl.umag.glaciertemp.transport.android.UsbSerialTransport
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Implementacion real de [Connectivity]. Es la unica clase de la app que toca BLE y USB. */
class AndroidConnectivity(
    private val context: Context,
    /**
     * Lo aporta la Activity, que es quien puede abrir el dialogo del sistema. Se pide al
     * pulsar Buscar y no al arrancar: es lo que recomienda Android --el permiso se entiende
     * cuando se ve para que-- y ademas evita que el dialogo tape la pantalla nada mas
     * abrir la app.
     */
    private val requestPermissions: (Array<String>) -> Unit = {},
    private val tcpHost: String = "10.0.2.2",
    private val tcpPort: Int = 5599,
) : Connectivity {

    private val scanner by lazy { BleScanner(context) }

    private fun usbDevice(id: Int): UsbDevice? =
        UsbSerialTransport.available(context).firstOrNull { it.deviceId == id }

    override fun usbTargets(): List<ConnectionTarget.Usb> =
        UsbSerialTransport.available(context).map {
            // El nombre del producto suele venir vacio en los clones baratos; el
            // VID:PID siempre esta y es lo que permite reconocer el cable.
            val name = it.productName?.takeIf { n -> n.isNotBlank() }
                ?: "%04X:%04X".format(it.vendorId, it.productId)
            ConnectionTarget.Usb(it.deviceId, name)
        }

    override fun hasUsbPermission(target: ConnectionTarget.Usb): Boolean =
        usbDevice(target.id)?.let { UsbSerialTransport.hasPermission(context, it) } ?: false

    override fun requestUsbPermission(target: ConnectionTarget.Usb) {
        usbDevice(target.id)?.let { UsbSerialTransport.requestPermission(context, it) }
    }

    override fun missingBlePermissions(): List<String> = blePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    override fun requestBlePermissions() {
        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray())
    }

    override fun scanBle(onUpdate: (List<ConnectionTarget.Ble>) -> Unit) {
        // Sin permiso, startScan no falla: devuelve una lista VACIA y ningun error, que es
        // de los comportamientos mas desconcertantes de esta API. Quien llama ya comprobo
        // los permisos; esto es la ultima red.
        if (missingBlePermissions().isNotEmpty()) return
        runCatching {
            scanner.start { list ->
                onUpdate(list.map { ConnectionTarget.Ble(it.address, it.label, it.rssi) })
            }
        }
    }

    override fun stopBleScan() = scanner.stop()

    override val bluetoothEnabled: Boolean get() = scanner.isEnabled

    companion object {
        /**
         * Android 12 separo los permisos de Bluetooth de los de ubicacion. En versiones
         * anteriores el escaneo BLE exigia ACCESS_FINE_LOCATION.
         */
        fun blePermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun open(target: ConnectionTarget): Transport = when (target) {
        is ConnectionTarget.TcpDebug -> TcpTransport(tcpHost, tcpPort)
        is ConnectionTarget.Usb -> {
            val d = usbDevice(target.id)
                ?: throw java.io.IOException("the adapter is no longer connected")
            cl.umag.glaciertemp.transport.android.UsbSerialTransport(context, d)
        }
        is ConnectionTarget.Ble -> {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter
                ?: throw java.io.IOException("this device has no Bluetooth")
            cl.umag.glaciertemp.transport.android.BleTransport(
                context, adapter.getRemoteDevice(target.address))
        }
    }
}
