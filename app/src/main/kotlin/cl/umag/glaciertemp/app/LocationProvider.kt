package cl.umag.glaciertemp.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import cl.umag.glaciertemp.core.GeoFix
import cl.umag.glaciertemp.core.geo.GpsSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * De donde sale la posicion de una descarga.
 *
 * Es una interfaz y no una clase porque asi el flujo entero --incluido el caso en que el
 * arreglo fresco NO llega, que es el que hay que probar-- se ejercita sin GPS, sin permisos
 * y sin telefono. Una implementacion que llame directamente a la API de Android solo se
 * puede probar saliendo a la calle.
 */
interface LocationSource {
    /**
     * Si tiene sentido preguntar por la posicion. Sin permiso o sin proveedor no lo tiene:
     * un dialogo que solo puede decir "no hay posicion" despues de CADA descarga es ruido,
     * y el motivo queda igualmente escrito en los metadatos del CSV.
     */
    suspend fun isAvailable(): Boolean

    /**
     * El ultimo arreglo conocido, sin encender el receptor. Responde en milisegundos y
     * puede ser de hace horas -- o null si el telefono acaba de arrancar.
     */
    suspend fun lastKnownFix(): GeoFix?

    /**
     * Un arreglo NUEVO, encendiendo el receptor, o null si no llega dentro del plazo. Al
     * aire libre suele tardar entre 2 y 15 s; bajo dosel o en un valle encajonado puede no
     * llegar nunca, y por eso hay plazo.
     */
    suspend fun freshFix(timeoutMs: Long): GeoFix?

    /**
     * Lecturas crudas, una detras de otra, para promediar un punto.
     *
     * Devuelve [GpsSample] y no [GeoFix] porque son cosas distintas: un GeoFix describe la
     * posicion del telefono AHORA, con su antiguedad relativa al momento de preguntarla, y
     * una muestra es una lectura fechada que va a vivir en un fichero durante anos.
     *
     * El flujo se cierra al cancelarse quien lo recoge, y ahi se apaga el receptor. Un GPS
     * encendido y olvidado es la forma mas rapida de vaciar la bateria en terreno.
     */
    fun samples(minIntervalMs: Long = 1000L): Flow<GpsSample>
}

/** Motivo por el que no hay posicion, en un texto que va tal cual al CSV. */
object LocationNotes {
    const val NO_PERMISSION = "location permission not granted"
    const val NO_PROVIDER = "no location provider available"
    const val USER_SKIPPED = "the operator continued without a position"
    const val TIMED_OUT = "no fresh fix within the time allowed"
}

/**
 * Implementacion sobre el LocationManager del sistema.
 *
 * Se usa el LocationManager de la plataforma y no los servicios de Google a proposito: es
 * una app de terreno y no hay garantia de que el telefono lleve Play Services, ni de que
 * haya red para actualizarlos.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    private val manager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * [fromSatellites] solo es cierto para un arreglo FRESCO del proveedor GPS. En ese caso
     * el campo de tiempo viene de los satelites, asi que su diferencia con el reloj del
     * telefono mide el error de este ultimo. En un arreglo guardado esa diferencia es solo
     * su antiguedad, y tomarla por un error de reloj estropearia las marcas en vez de
     * arreglarlas.
     */
    private fun Location.toFix(fromSatellites: Boolean = false): GeoFix = GeoFix(
        latitude = latitude,
        longitude = longitude,
        // hasAltitude() y no `altitude` a secas: sin arreglo tridimensional Android
        // devuelve 0.0, que a nivel del mar es indistinguible de una medida buena.
        altitudeMetres = if (hasAltitude()) altitude else null,
        accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else null,
        ageSeconds = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0),
        clockSkewSeconds = if (fromSatellites) (time - System.currentTimeMillis()) / 1000
                           else null,
    )

    override suspend fun isAvailable(): Boolean {
        if (!hasPermission()) return false
        val m = manager ?: return false
        return m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnownFix(): GeoFix? {
        if (!hasPermission()) return null
        val m = manager ?: return null
        // Se mira TODO proveedor y se elige el mas reciente: el de red puede ser mas nuevo
        // que el de GPS si el telefono lleva un rato en el bolsillo, y para situar un sitio
        // una posicion de red reciente vale mas que una de GPS de hace tres horas.
        return m.allProviders
            .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    /**
     * Solo el proveedor GPS, nunca el de red.
     *
     * Promediar posiciones de red no mejora nada: vienen de una base de datos de antenas y
     * de wifis, se repiten identicas durante minutos y su error es un sesgo de decenas o
     * cientos de metros que no se promedia. Mezclarlas con las de satelite arrastraria la
     * mediana sin que nada lo delate. Si no hay GPS, no hay nada que promediar y se dice.
     */
    @SuppressLint("MissingPermission")
    override fun samples(minIntervalMs: Long): Flow<GpsSample> {
        if (!hasPermission()) return emptyFlow()
        val m = manager ?: return emptyFlow()
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) return emptyFlow()

        return callbackFlow {
            val listener = android.location.LocationListener { loc ->
                trySend(GpsSample(
                    // La marca es la del ARREGLO, no la de cuando llego: es la hora en que
                    // se midio, y es la que tiene sentido guardar.
                    epochMillis = loc.time,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    // hasAltitude() y no `altitude` a secas: sin arreglo tridimensional
                    // Android devuelve 0.0, que a nivel del mar pasa por una medida buena.
                    altitudeMetres = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyMetres = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                ))
            }
            runCatching {
                m.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, minIntervalMs, 0f,
                    listener, android.os.Looper.getMainLooper())
            }.onFailure { close(it) }
            awaitClose { runCatching { m.removeUpdates(listener) } }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun freshFix(timeoutMs: Long): GeoFix? {
        if (!hasPermission()) return null
        val m = manager ?: return null
        val provider = when {
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    m.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                        if (cont.isActive) {
                            cont.resume(loc?.toFix(
                                fromSatellites = provider == LocationManager.GPS_PROVIDER))
                        }
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}
