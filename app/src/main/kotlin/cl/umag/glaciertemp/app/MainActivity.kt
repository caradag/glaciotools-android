package cl.umag.glaciertemp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val vm: DeviceViewModel by viewModels()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Concedidos, se reintenta el escaneo, que es lo que el usuario acababa de pedir.
            // Denegados no se insiste: el cable sigue disponible y funciona igual.
            if (granted.values.all { it }) vm.startBleScan()
        }

    private val askLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Sin insistir: denegarlo degrada los metadatos, no la funcion principal. */
    private fun askLocation() {
        val src = vm.location as? AndroidLocationSource ?: return
        if (src.hasPermission()) return
        askLocationPermission.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.connectivity = AndroidConnectivity(applicationContext,
            requestPermissions = { askPermissions.launch(it) })
        vm.prefs = getSharedPreferences("glaciotools", MODE_PRIVATE)
        vm.location = AndroidLocationSource(applicationContext)
        // Se pide al arrancar y no al descargar: un dialogo del sistema en mitad de la
        // descarga interrumpe justo lo que no se puede interrumpir. Si se deniega, la
        // descarga sigue funcionando y el CSV lo dice en sus metadatos.
        askLocation()
        vm.runningOnEmulator = isEmulator()
        vm.refreshUsb()
        setContent {
            GlacioToolsTheme {
                Surface { GlacierTempApp(vm) }
            }
        }
    }

    /**
     * Solo sirve para dar un mensaje util cuando falla la conexion al simulador: dentro del
     * emulador 10.0.2.2 es el PC anfitrion, y fuera no es nada.
     */
    private fun isEmulator(): Boolean =
        Build.HARDWARE in setOf("goldfish", "ranchu", "cutf_cvm") ||
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.MODEL.contains("sdk", ignoreCase = true)

    override fun onDestroy() {
        super.onDestroy()
        vm.stopBleScan()
        // El callback de permisos captura esta Activity; el ViewModel le sobrevive.
        if (isFinishing) vm.connectivity = null
    }
}
