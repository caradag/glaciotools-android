package cl.umag.glaciertemp.transport.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cl.umag.glaciertemp.transport.BaudSwitchable
import cl.umag.glaciertemp.transport.BufferedTransport
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * Enlace con la placa por cable, a traves de un adaptador USB-serie.
 *
 * La placa no lleva chip USB: saca un header tipo FTDI, asi que el chip esta en el cable
 * que use cada uno --CH340, CP2102, FT232, PL2303 o un CDC-ACM generico--. Por eso el
 * reconocimiento y la configuracion de linea se delegan en usb-serial-for-android en vez de
 * escribirlos a mano: serian cuatro drivers que no hay forma de probar sin tener los cuatro
 * cables delante.
 */
class UsbSerialTransport(
    private val context: Context,
    private val device: UsbDevice,
    private val baudRate: Int = BAUD_RATE,
) : BufferedTransport(writeChunkSize = 4096), BaudSwitchable {

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null

    override val isOpen: Boolean get() = port != null

    override fun open() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver: UsbSerialDriver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw java.io.IOException(
                "adapter ${hex(device.vendorId)}:${hex(device.productId)} is not a " +
                "recognised USB-serial device")
        if (!manager.hasPermission(device)) {
            throw SecurityException("missing permission to access the USB device")
        }
        val connection = manager.openDevice(device)
            ?: throw java.io.IOException("could not open the USB device")

        val p = driver.ports.first()
        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // El header lleva el condensador de auto-reset, asi que bajar y subir DTR REINICIA
        // la placa. Se hace a proposito: el reinicio es lo que abre la ventana de consola de
        // 30 s. A cambio, quien abra el puerto debe esperar al bootloader antes de mandar
        // nada, que es lo que hace DeviceSession.drainBanner().
        runCatching { p.setDTR(true); p.setRTS(true) }

        clearBuffer()
        port = p
        io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = onReceived(data)
            override fun onRunError(e: Exception) { close() }
        }).apply {
            // Por defecto lee en trozos pequenos. En la descarga llegan 230400 baudios
            // sostenidos, unos 23 kB/s, y un buffer corto multiplica las vueltas del hilo
            // lector sin ganar nada.
            readBufferSize = 16 * 1024
            writeTimeout = WRITE_TIMEOUT_MS
            start()
        }
    }

    /**
     * Cambia la velocidad de linea sin cerrar el puerto. Lo usa el volcado binario para
     * subir a 230400 mientras duran los bloques; el resto de la sesion va a la de la
     * consola. El Bluetooth no implementa esto a proposito: alli la velocidad la fija el
     * modulo y la radio es mas lenta que la UART, asi que no habria nada que ganar.
     */
    override fun setBaudRate(baud: Int) {
        val p = port ?: throw java.io.IOException("the link is closed")
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override fun writeChunk(chunk: ByteArray) {
        val p = port ?: throw java.io.IOException("the link is closed")
        p.write(chunk, WRITE_TIMEOUT_MS)
    }

    override fun close() {
        io?.stop(); io = null
        runCatching { port?.close() }
        port = null
    }

    companion object {
        /**
         * Velocidad de la consola del firmware. 115200 sale de UBRR=7 con cero error en el
         * cristal de 7,3728 MHz, es el techo de los modulos HM-10 y es lo que usa el
         * bootloader de MiniCore con este reloj: una sola velocidad para todo.
         *
         * El volcado binario sube a 230400 mientras duran los bloques, pero eso lo pide
         * DeviceSession leyendo lo que la placa declara en INFO, no esta constante.
         */
        const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT_MS = 2000
        private const val ACTION_PERMISSION = "cl.umag.glaciertemp.USB_PERMISSION"

        private fun hex(v: Int) = "%04X".format(v)

        /** Adaptadores conectados que el prober reconoce como USB-serie. */
        fun available(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { it.device }
        }

        /**
         * Android exige permiso POR DISPOSITIVO y lo concede de forma asincrona. La UI
         * lanza esto y vuelve a intentar la apertura cuando llega el broadcast.
         */
        fun requestPermission(context: Context, device: UsbDevice) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (manager.hasPermission(device)) return
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.requestPermission(device, intent)
        }

        fun hasPermission(context: Context, device: UsbDevice): Boolean =
            (context.getSystemService(Context.USB_SERVICE) as UsbManager).hasPermission(device)
    }
}
