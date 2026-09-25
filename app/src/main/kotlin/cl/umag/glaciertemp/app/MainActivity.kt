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
    private val gps: GpsViewModel by viewModels()
    private val fieldbook: FieldbookViewModel by viewModels()
    // Se crea con la actividad, no al abrir la herramienta: la regla es "al iniciar
    // la app", y quien sale a terreno abre GPS tools cuando ya no hay red.
    private val almanac: AlmanacViewModel by viewModels()
    private val journal: JournalViewModel by viewModels()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Concedidos, se reintenta el escaneo, que es lo que el usuario acababa de pedir.
            // Denegados no se insiste: el cable sigue disponible y funciona igual.
            if (granted.values.all { it }) vm.startBleScan()
        }

    private val askLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /**
     * Cuando el sistema lanza la app porque se ha enchufado un cable serie.
     *
     * La actividad es `singleTop` por defecto en su modo estandar: con la app ya abierta,
     * Android crea OTRA instancia en vez de reutilizarla, asi que hace falta atender tambien
     * onNewIntent. Sin esto, enchufar el cable con la app delante no refrescaba la lista y
     * el adaptador no aparecia hasta pulsar Scan.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        atenderCableEnchufado(intent)
    }

    private fun atenderCableEnchufado(intent: android.content.Intent?) {
        if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // Llegar por aqui significa que el usuario eligio GlacioTools en el dialogo del
        // sistema, y eso YA concede el permiso para ese aparato: no hay que volver a pedirlo.
        vm.refreshUsb()
    }

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
        gps.location = vm.location
        // Los puntos y la libreta viven en la carpeta privada de la app: no hacen falta
        // permisos de almacenamiento, y el usuario saca lo que quiera con Exportar. Guardarlos
        // donde el usuario elija obligaria a pedirle una carpeta antes de poder medir nada.
        //
        // UN SOLO almacen de puntos para las dos herramientas y no una copia por cada una:
        // elegir un punto guardado como coordenada de una entrada de libreta tiene que ver los
        // mismos puntos que GPS tools, incluido el que se acaba de medir.
        val puntos = cl.umag.glaciertemp.core.geo.GpsPointStore(
            java.io.File(filesDir, "gps-points"))
        gps.store = puntos
        fieldbook.store = cl.umag.glaciertemp.core.fieldbook.FieldbookStore(
            java.io.File(filesDir, "fieldbook"))
        fieldbook.people = cl.umag.glaciertemp.core.fieldbook.NameStore(
            java.io.File(filesDir, "fieldbook/people.txt"))
        fieldbook.receivers = cl.umag.glaciertemp.core.fieldbook.NameStore(
            java.io.File(filesDir, "fieldbook/receivers.txt"))
        fieldbook.species = cl.umag.glaciertemp.core.fieldbook.NameStore(
            java.io.File(filesDir, "fieldbook/species.txt"))
        val campanas = cl.umag.glaciertemp.core.fieldbook.CampaignStore(
            java.io.File(filesDir, "fieldbook/campaigns.txt"))
        fieldbook.campaigns = campanas
        // El diario lee el MISMO almacen de campanas: es lo que le permite saber cual esta
        // abierta al arrancar la app, sin que nadie haya entrado en la libreta, y de eso
        // depende que el recordatorio pueda salir.
        journal.campaigns = campanas
        journal.store = cl.umag.glaciertemp.core.fieldbook.JournalStore(
            java.io.File(filesDir, "journal"))
        // Y la libreta conoce el del diario para poder borrarlo con la campana.
        fieldbook.journal = journal.store
        fieldbook.sky = AndroidSkySource(applicationContext)
        fieldbook.gpsPoints = puntos
        fieldbook.location = vm.location
        fieldbook.requestLocationPermission = { askLocation() }
        // La descarga tambien puede tomar su posicion de un punto ya promediado, asi que el
        // ViewModel de la placa necesita el mismo almacen.
        vm.gpsPoints = puntos
        // Sin posicion la herramienta de GPS no existe, asi que aqui si se insiste -- pero
        // solo cuando el usuario ya ha pulsado "New point" y el dialogo se entiende.
        gps.requestLocationPermission = { askLocation() }
        // Se pide al arrancar y no al descargar: un dialogo del sistema en mitad de la
        // descarga interrumpe justo lo que no se puede interrumpir. Si se deniega, la
        // descarga sigue funcionando y el CSV lo dice en sus metadatos.
        askLocation()
        vm.runningOnEmulator = isEmulator()
        vm.refreshUsb()
        atenderCableEnchufado(intent)
        setContent {
            GlacioToolsTheme {
                Surface { GlacioToolsApp(vm, gps, fieldbook, almanac, journal) }
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
