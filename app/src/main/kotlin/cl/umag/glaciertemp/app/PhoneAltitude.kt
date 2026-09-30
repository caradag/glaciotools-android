package cl.umag.glaciertemp.app

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import cl.umag.glaciertemp.core.geo.Gga
import cl.umag.glaciertemp.core.geo.HeightCheck
import cl.umag.glaciertemp.core.geo.HeightVerdict
import cl.umag.glaciertemp.core.geo.Nmea

/**
 * La altura que entrega ESTE telefono, verificada con lo que dice su propio chip GNSS.
 *
 * Android especifica que `Location.getAltitude()` es altura sobre el elipsoide WGS84, pero
 * hay chips que entregan ahi la altura sobre el nivel del mar, y la plataforma no lo
 * comprueba. En Patagonia la diferencia es la separacion geoidal, 9-25 m: un telefono asi
 * daria todas las alturas equivocadas en esa cantidad, con la etiqueta "elipsoidal".
 *
 * Mientras el receptor esta encendido se escuchan las sentencias NMEA GGA, que traen la
 * altura MSL que calculo el chip y la separacion que uso, y se comparan con la altura de
 * cada Location de la misma epoca (ver `HeightCheck` en :core). El veredicto se guarda en
 * AppSettings y dura mas que el proceso.
 *
 * SI EL TELEFONO ENTREGA MSL, SE CORRIGE: altura elipsoidal = MSL + la separacion de la GGA
 * de esa misma epoca. Si no hay GGA que emparejar, la altura queda desconocida (null) en vez
 * de equivocada.
 */
object PhoneAltitude {
    private var check = HeightCheck(required = 10)
    private val recientes = ArrayDeque<Gga>()
    private val lock = Any()

    private fun horaDelDia(millis: Long) = (millis % 86_400_000L) / 1000.0

    private fun ggaDe(loc: Location): Gga? = synchronized(lock) {
        recientes.lastOrNull { HeightCheck.matches(it, horaDelDia(loc.time)) }
    }

    fun onNmea(sentence: String) {
        val g = Nmea.parseGga(sentence) ?: return
        synchronized(lock) {
            recientes.addLast(g)
            while (recientes.size > 20) recientes.removeFirst()
        }
    }

    /** Se ofrece cada posicion GPS con altura al verificador; al haber veredicto, se guarda. */
    fun observe(loc: Location) {
        if (!loc.hasAltitude() || loc.provider != LocationManager.GPS_PROVIDER) return
        val g = ggaDe(loc) ?: return
        val v = synchronized(lock) { check.offer(loc.altitude, g) }
        // Se guarda la primera vez que se alcanza en cada sesion, tambien si repite el
        // anterior: la fecha de la ultima confirmacion es parte de lo que se muestra.
        if (v != HeightVerdict.UNVERIFIED && !confirmedThisSession) {
            confirmedThisSession = true
            AppSettings.setHeightCheck(AppSettings.HeightCheckState(
                verdict = v, deviceModel = Build.MANUFACTURER + " " + Build.MODEL,
                checkedAtMillis = System.currentTimeMillis(), chipSeparation = g.geoidSeparation))
        }
    }

    /** Para las pruebas: empezar de cero, como al arrancar la app. */
    internal fun resetForTest() = synchronized(lock) {
        check = HeightCheck(required = 10); recientes.clear(); confirmedThisSession = false
    }

    /** Si ya hubo veredicto desde que arranco la app. */
    @Volatile var confirmedThisSession = false
        private set

    /** La separacion geoidal de la ultima GGA vista, o null. */
    fun lastChipSeparation(): Double? = synchronized(lock) { recientes.lastOrNull()?.geoidSeparation }

    /** Votos acumulados en esta sesion: (elipsoidal, MSL). Para mostrar el progreso. */
    fun votes(): Pair<Int, Int> = synchronized(lock) { check.ellipsoidalVotes to check.mslVotes }

    /** La altura ELIPSOIDAL de una posicion, o null si no se puede saber. */
    fun ellipsoidal(loc: Location): Double? {
        if (!loc.hasAltitude()) return null
        return when (AppSettings.heightCheck.value.verdict) {
            HeightVerdict.MSL -> ggaDe(loc)?.let { loc.altitude + it.geoidSeparation }
            else -> loc.altitude
        }
    }

    /**
     * Empieza a escuchar NMEA y devuelve la funcion que deja de hacerlo. Se llama junto a
     * cada peticion de posicion GPS: fuera de esos ratos el chip no emite nada.
     */
    @SuppressLint("MissingPermission")
    fun listen(m: LocationManager): () -> Unit {
        val l = OnNmeaMessageListener { msg, _ -> onNmea(msg) }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                m.addNmeaListener(java.util.concurrent.Executor { it.run() }, l)
            else @Suppress("DEPRECATION") m.addNmeaListener(l, Handler(Looper.getMainLooper()))
        }.getOrDefault(false)
        return { if (ok) runCatching { m.removeNmeaListener(l) } }
    }
}
