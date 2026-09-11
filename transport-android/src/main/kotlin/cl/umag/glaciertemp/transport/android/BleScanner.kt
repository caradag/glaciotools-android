package cl.umag.glaciertemp.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Un modulo visto en el escaneo, con la potencia para poder ordenar por cercania. */
data class BleCandidate(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val rssi: Int,
) {
    /** Lo que se muestra cuando el modulo no anuncia nombre, que es habitual. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "unnamed ($address)"
}

/**
 * Escaneo de puentes serie-BLE.
 *
 * No se filtra por UUID de servicio: un HM-10 sin configurar a menudo no anuncia el suyo, y
 * filtrar por `FFE0` lo dejaria fuera de la lista justo en el caso mas comun. Se muestran
 * todos y se deja elegir, que ademas es lo unico que funciona con un modulo desconocido.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, BleCandidate>()

    /**
     * Escanea [durationMs] y va llamando a [onUpdate] con la lista acumulada. Se acumula en
     * vez de emitir cada hallazgo porque un mismo modulo aparece muchas veces, y lo que la
     * UI necesita es una lista estable con el RSSI al dia.
     */
    fun start(durationMs: Long = 12_000, onUpdate: (List<BleCandidate>) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stop()
        found.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                found[d.address] = BleCandidate(d, runCatching { d.name }.getOrNull(),
                    d.address, result.rssi)
                onUpdate(found.values.sortedByDescending { it.rssi })
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
        }
        callback = cb
        scanner.startScan(emptyList<ScanFilter>(),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(), cb)
        handler.postDelayed({ stop() }, durationMs)
    }

    fun stop() {
        callback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        callback = null
        handler.removeCallbacksAndMessages(null)
    }
}
