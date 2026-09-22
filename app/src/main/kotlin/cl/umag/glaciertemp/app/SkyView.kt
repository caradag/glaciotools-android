package cl.umag.glaciertemp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** Cuantos satelites de una constelacion se ven y cuantos entran en la solucion. */
data class ConstellationCount(val name: String, val visible: Int, val used: Int)

/**
 * Lo que el receptor del telefono ve del cielo ahora mismo.
 *
 * SIRVE PARA DECIDIR, no para procesar. La pregunta que responde es la que uno se hace con el
 * tripode ya puesto: si hay pocos satelites conviene alargar la ocupacion, y si hay muchos se
 * puede levantar antes de lo programado. El telefono no mide el punto --eso lo hace el
 * receptor geodesico-- pero mira el mismo cielo desde el mismo sitio, que es justo lo que hace
 * falta saber.
 */
data class SkyView(
    val constellations: List<ConstellationCount>,
    val totalVisible: Int,
    val totalUsed: Int,
    /** Relacion senal/ruido mediana de los que entran en la solucion, en dB-Hz. */
    val medianCn0: Double?,
) {
    companion object {
        val EMPTY = SkyView(emptyList(), 0, 0, null)
    }
}

/**
 * Lee el estado de los satelites del telefono.
 *
 * Interfaz y no clase por lo mismo que [LocationSource]: la pantalla se puede ejercitar con
 * una version falsa, sin salir a la calle y sin un cielo despejado.
 */
interface SkySource {
    /**
     * Emite una foto del cielo cada vez que el receptor la actualiza (tipicamente 1 Hz).
     *
     * Devuelve un flujo VACIO si no hay permiso o no hay proveedor GPS, que quien lo recoge
     * distingue de "no se ve ningun satelite": lo primero es un problema de la app y lo
     * segundo un dato del sitio.
     */
    fun sky(): Flow<SkyView>
}

/**
 * Implementacion sobre `GnssStatus`.
 *
 * DOS COSAS QUE NO SE VEN EN LA API. La primera: registrar el callback no basta para que
 * lleguen datos -- el motor GNSS solo se enciende si hay una peticion de posicion activa, asi
 * que esto pide actualizaciones mientras alguien esta mirando y las corta al dejar de mirar.
 * La segunda: eso consume bateria, y una ocupacion de punto dura horas; por eso el flujo se
 * recoge solo mientras el panel esta en pantalla y no durante toda la medicion.
 */
class AndroidSkySource(private val context: Context) : SkySource {

    private val manager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun sky(): Flow<SkyView> {
        // FINE y no COARSE: con el aproximado el sistema no entrega estado de satelites.
        if (!hasPermission()) return emptyFlow()
        val m = manager ?: return emptyFlow()
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) return emptyFlow()

        return callbackFlow {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    trySend(resumir(status))
                }
            }
            // Un oyente de posicion que no se usa para nada mas que mantener encendido el
            // motor GNSS. Sin el, el callback de estado se registra sin error y no llega
            // nunca nada, que es el fallo mas dificil de diagnosticar de esta API.
            val despertador = android.location.LocationListener { }
            runCatching {
                m.registerGnssStatusCallback(callback, android.os.Handler(
                    android.os.Looper.getMainLooper()))
                m.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, despertador,
                    android.os.Looper.getMainLooper())
            }.onFailure { close(it) }

            awaitClose {
                runCatching { m.unregisterGnssStatusCallback(callback) }
                runCatching { m.removeUpdates(despertador) }
            }
        }
    }

    companion object {
        /** Nombres en el orden en que se leen. Los de fuera del GNSS van al final. */
        private val ORDEN = listOf(
            GnssStatus.CONSTELLATION_GPS to "GPS",
            GnssStatus.CONSTELLATION_GLONASS to "GLONASS",
            GnssStatus.CONSTELLATION_GALILEO to "Galileo",
            GnssStatus.CONSTELLATION_BEIDOU to "BeiDou",
            GnssStatus.CONSTELLATION_QZSS to "QZSS",
            GnssStatus.CONSTELLATION_IRNSS to "NavIC",
            GnssStatus.CONSTELLATION_SBAS to "SBAS",
        )

        /**
         * Resume un `GnssStatus` en cuentas por constelacion.
         *
         * SE SEPARA "visible" DE "usado" a proposito: ver doce satelites y que el receptor
         * use cinco significa algo muy distinto de ver seis y usar seis, y es justamente lo
         * que decide si conviene alargar la ocupacion. Un solo numero borraria esa diferencia.
         *
         * SBAS aparece en la lista pero no aporta geometria: son satelites de correccion. Se
         * muestra porque estar ahi explica una cuenta de visibles que si no no cuadra.
         */
        fun resumir(status: GnssStatus): SkyView {
            val visibles = HashMap<Int, Int>()
            val usados = HashMap<Int, Int>()
            val cn0 = ArrayList<Double>()
            for (i in 0 until status.satelliteCount) {
                val c = status.getConstellationType(i)
                visibles[c] = (visibles[c] ?: 0) + 1
                if (status.usedInFix(i)) {
                    usados[c] = (usados[c] ?: 0) + 1
                    cn0 += status.getCn0DbHz(i).toDouble()
                }
            }
            val filas = ORDEN.mapNotNull { (tipo, nombre) ->
                val v = visibles[tipo] ?: 0
                if (v == 0) null else ConstellationCount(nombre, v, usados[tipo] ?: 0)
            }
            // Cualquier tipo que la plataforma anada y esta lista no conozca: se cuenta en vez
            // de desaparecer, porque si no la suma de las filas no cuadra con el total.
            val conocidos = ORDEN.map { it.first }.toSet()
            val otrosV = visibles.filterKeys { it !in conocidos }.values.sum()
            val otrosU = usados.filterKeys { it !in conocidos }.values.sum()
            val todas = if (otrosV > 0) filas + ConstellationCount("Other", otrosV, otrosU)
                        else filas

            return SkyView(
                constellations = todas,
                totalVisible = status.satelliteCount,
                totalUsed = usados.values.sum(),
                // Mediana y no media: un satelite que acaba de asomar por el horizonte entra
                // con una relacion senal/ruido malisima y arrastraria la media hacia abajo
                // justo cuando el cielo esta bien.
                medianCn0 = cn0.sorted().let {
                    when {
                        it.isEmpty() -> null
                        it.size % 2 == 1 -> it[it.size / 2]
                        else -> (it[it.size / 2 - 1] + it[it.size / 2]) / 2
                    }
                },
            )
        }
    }
}
