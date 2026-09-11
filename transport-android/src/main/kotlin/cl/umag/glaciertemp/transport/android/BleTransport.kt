package cl.umag.glaciertemp.transport.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import cl.umag.glaciertemp.transport.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Enlace con la placa a traves de un puente serie-BLE (HM-10 u otro).
 *
 * El modulo es un accesorio externo intercambiable, asi que el perfil GATT no se codifica:
 * se descubre con [GattProfiles], que vive en `:transport` y se prueba sin radio. Aqui solo
 * queda el pegamento con la API de Android, que es poco pero tiene tres trampas que cuestan
 * horas si no se conocen, marcadas mas abajo.
 */
@SuppressLint("MissingPermission")   // los permisos los pide la UI antes de llegar aqui
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    /**
     * Pausa entre escrituras consecutivas. Un HM-10 a 20 bytes por paquete pierde datos si
     * se le escribe mas rapido que su intervalo de conexion, y el sintoma es un comando
     * truncado, no un error. Los comandos son cortos, asi que este coste es despreciable.
     */
    writeGapMs: Long = 20L,
    private val connectTimeoutMs: Long = 15_000,
    // Se escriben siempre 20 bytes por paquete, el minimo que acepta cualquier modulo. El
    // MTU negociado importa para la DESCARGA --paquetes mas grandes, menos vueltas-- y no
    // para los comandos, que nunca pasan de una veintena de caracteres.
) : BufferedTransport(writeChunkSize = 20, writeGapMs = writeGapMs) {

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile var profile: SerialGattProfile? = null; private set
    @Volatile var negotiatedMtu: Int = 23; private set

    private val failure = AtomicReference<String?>(null)
    private var connected = CountDownLatch(1)
    private var servicesReady = CountDownLatch(1)
    private var mtuSettled = CountDownLatch(1)
    private var notificationsOn = CountDownLatch(1)

    override val isOpen: Boolean get() = gatt != null && writeChar != null

    /**
     * Registros por peticion, deducidos del MTU que el modulo acepto.
     *
     * Un modulo que negocia MTU 23 mueve 20 bytes por notificacion: con un intervalo de
     * conexion tipico son unos centenares de bytes por segundo, mientras la placa empuja
     * 11,5 kB/s. Pedir 128 registros (1,3 kB) desbordaba su buffer y se perdian casi todos
     * los bloques -- medido contra un HM-10 real, del que llegaba un unico bloque de cinco.
     *
     * Es solo el PUNTO DE PARTIDA: DeviceSession lo ajusta durante la descarga hasta dar
     * con el mayor que el enlace sostiene. El buffer real de cada modulo no esta
     * documentado, asi que medirlo sobre la marcha es mas fiable que deducirlo del MTU.
     */
    override val recordsPerRequest: Int get() {
        val payload = GattProfiles.payloadForMtu(negotiatedMtu)
        // Con el MTU minimo se va a lo seguro; con uno grande, el modulo tiene buffer y
        // radio de sobra para tramos comodos.
        return when {
            payload <= 20 -> 16
            payload < 100 -> 64
            else -> 256
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT connection failed (status $status)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected.countDown()
                    // El MTU se pide ANTES de descubrir servicios: pedirlo despues obliga a
                    // algunas pilas a rehacer el descubrimiento.
                    if (!g.requestMtu(247)) {
                        negotiatedMtu = 23
                        mtuSettled.countDown()
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> fail("the device disconnected")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            mtuSettled.countDown()
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("could not discover services (status $status)")
                return
            }
            val model = g.services.map { s ->
                GattService(s.uuid.toString(), s.characteristics.map { c ->
                    GattCharacteristic(
                        uuid = c.uuid.toString(),
                        notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                        indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                        write = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                        writeNoResponse = c.properties and
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    )
                })
            }
            val p = GattProfiles.select(model)
            if (p == null) {
                fail("the device exposes no recognisable serial bridge")
                return
            }
            profile = p
            val svc = g.getService(UUID.fromString(p.service))
            writeChar = svc?.getCharacteristic(UUID.fromString(p.writeChar))
            val notifyChar = svc?.getCharacteristic(UUID.fromString(p.notifyChar))
            if (writeChar == null || notifyChar == null) {
                fail("the ${p.name} characteristics were not in the service")
                return
            }
            servicesReady.countDown()
            enableNotifications(g, notifyChar)
        }

        // Firma nueva a partir de Android 13; la vieja sigue llegando en versiones previas.
        override fun onCharacteristicChanged(g: BluetoothGatt,
                                             c: BluetoothGattCharacteristic, value: ByteArray) {
            onReceived(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt,
                                             c: BluetoothGattCharacteristic) {
            c.value?.let { onReceived(it.copyOf()) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt,
                                       d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) notificationsOn.countDown()
            else fail("could not enable notifications (status $status)")
        }
    }

    /**
     * Activar las notificaciones son DOS pasos: avisar a la pila local y ESCRIBIR el
     * descriptor CCCD en el dispositivo. Hacer solo el primero es el fallo mas frecuente al
     * escribir un cliente BLE a mano: conecta, acepta comandos y no llega nunca nada.
     */
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(UUID.fromString(GattProfiles.CCCD))
        if (cccd == null) {
            // Algun modulo no publica el descriptor pero notifica igual: no es motivo para
            // abortar la conexion.
            notificationsOn.countDown()
            return
        }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            GattProfiles.ENABLE_INDICATION else GattProfiles.ENABLE_NOTIFICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = value; g.writeDescriptor(cccd) }
        }
    }

    private fun fail(reason: String) {
        failure.compareAndSet(null, reason)
        connected.countDown(); servicesReady.countDown()
        mtuSettled.countDown(); notificationsOn.countDown()
    }

    override fun open() {
        failure.set(null)
        connected = CountDownLatch(1); servicesReady = CountDownLatch(1)
        mtuSettled = CountDownLatch(1); notificationsOn = CountDownLatch(1)
        clearBuffer()
        // TRANSPORT_LE explicito: sin el, un modulo que anuncie tambien BR/EDR puede
        // conectarse por el transporte clasico y no responder a nada.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw java.io.IOException("could not start the GATT connection")
        await(connected, "connect"); await(servicesReady, "discover services")
        await(notificationsOn, "enable notifications")
    }

    private fun await(latch: CountDownLatch, what: String) {
        if (!latch.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw java.io.IOException("timed out while trying to $what")
        }
        failure.get()?.let { close(); throw java.io.IOException(it) }
    }

    override fun writeChunk(chunk: ByteArray) {
        val g = gatt ?: throw java.io.IOException("the link is closed")
        val c = writeChar ?: throw java.io.IOException("the link is closed")
        val type = if (profile?.writeNoResponse == true)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, chunk, type)
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = chunk; g.writeCharacteristic(c) }
        }
    }

    override fun close() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null; writeChar = null
    }
}
