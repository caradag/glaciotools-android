package cl.umag.glaciertemp.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.umag.glaciertemp.core.gnss.AlmanacFreshness
import cl.umag.glaciertemp.core.gnss.Constellation
import cl.umag.glaciertemp.core.gnss.Freshness
import cl.umag.glaciertemp.core.gnss.CheckResult
import cl.umag.glaciertemp.core.gnss.Forecast
import cl.umag.glaciertemp.core.gnss.ModelCheck
import cl.umag.glaciertemp.core.gnss.ObservedSat
import cl.umag.glaciertemp.core.gnss.Tle
import cl.umag.glaciertemp.core.gnss.VisibilityForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AlmanacUiState(
    val tles: List<Tle> = emptyList(),
    val downloadedAtMillis: Long? = null,
    val freshness: Freshness = Freshness.MISSING,
    val busy: Boolean = false,
    /** Que paso en el ultimo intento. Null mientras no se haya intentado nada. */
    val note: String? = null,
    val counts: Map<Constellation, Int> = emptyMap(),

    // ---- planificacion ----
    /** Las que usa el receptor que se va a llevar. Se guardan entre sesiones. */
    val enabled: Set<Constellation> = Constellation.entries.toSet(),
    /** Desde donde se calcula. Sale del GPS y se puede cambiar a mano. */
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    /** Si la coordenada la puso el GPS o la escribio el usuario. */
    val fromFix: Boolean = false,
    val waitingFix: Boolean = false,
    val forecast: Forecast? = null,
    val computing: Boolean = false,
    /** Lo que se equivoca el modelo contra el cielo de ahora, si hay con que compararlo. */
    val check: CheckResult? = null,
    /** Dibujar tambien la suma. Apagado de fabrica: aplasta las lineas de cada constelacion. */
    val showTotal: Boolean = false,
    /**
     * Satelites descartados del recuento por identidad que no cuadra.
     *
     * Se APRENDEN: cada vez que el contraste contra el cielo pilla uno, se recuerda. De modo
     * que la grafica mejora sola segun se usa la herramienta, sin que nadie tenga que
     * mantener una lista de satelites retirados dentro de la app.
     */
    val excluded: Set<String> = emptySet(),
    /**
     * Mascara de elevacion, en grados.
     *
     * Estaba fija en 10. No hay un valor bueno: depende del horizonte que uno tenga delante
     * --un circo glaciar con paredes de 30 grados no deja ver nada por debajo de eso-- y de
     * lo exigente que sea el receptor con las senales rasantes, que llegan atravesando mas
     * atmosfera y son las que mas error meten.
     */
    val maskDeg: Double = 10.0,
    /** Medianoche local del dia que se esta calculando. Cero mientras no se sepa. */
    val dayStartMillis: Long = 0L,
    /** Si ese dia es hoy. De ello depende que el contraste contra el cielo tenga sentido. */
    val isToday: Boolean = true,
    /**
     * La epoca de referencia de los elementos orbitales: la mediana de las de los TLE.
     *
     * Es contra esto y no contra la fecha de descarga como se mide si un dia pedido cae
     * fuera del alcance util del almanaque, porque es la epoca la que gobierna el error de
     * propagacion.
     */
    val epochRefMillis: Long? = null,
)

/**
 * El almanaque: cargarlo al arrancar y mantenerlo al dia sin que nadie tenga que acordarse.
 *
 * LA REGLA DE ARRANQUE. Al abrir la app se mira lo guardado; si esta caducado Y hay red, se
 * baja solo. Nada mas. En particular NO se descarga si lo guardado sigue fresco, aunque haya
 * wifi: serian datos y bateria para no cambiar ni un pixel de la grafica, porque con 2 grados
 * de tolerancia un juego de elementos vale meses.
 *
 * Y NO SE PIDE PERMISO NI SE PREGUNTA. La descarga son 24 KB y ocurre una vez cada varias
 * semanas. Un cuadro de dialogo preguntando por eso, en una app que se usa con guantes,
 * cuesta mas que los datos que ahorra.
 */
class AlmanacViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AlmanacStore(File(app.filesDir, "almanac"))
    private val prefs = app.getSharedPreferences("planner", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AlmanacUiState())
    val state: StateFlow<AlmanacUiState> = _state

    init {
        _state.value = _state.value.copy(
            enabled = leerHabilitadas(),
            showTotal = prefs.getBoolean("showTotal", false),
            excluded = prefs.getStringSet("excluded", null)?.toSet() ?: emptySet(),
            maskDeg = prefs.getFloat("maskDeg", 10f).toDouble())
        cargarYPonerAlDia()
    }

    private fun leerHabilitadas(): Set<Constellation> {
        val guardado = prefs.getStringSet("enabled", null) ?: return Constellation.entries.toSet()
        val s = guardado.mapNotNull { n -> Constellation.entries.firstOrNull { it.name == n } }.toSet()
        // Nunca vacio: un grafico sin ninguna linea no informa de nada y no hay forma de
        // saber, mirandolo, que lo que falta es una casilla marcada en otra pantalla.
        return s.ifEmpty { Constellation.entries.toSet() }
    }

    fun setShowTotal(on: Boolean) {
        prefs.edit().putBoolean("showTotal", on).apply()
        _state.value = _state.value.copy(showTotal = on)
    }

    fun setMask(deg: Double) {
        prefs.edit().putFloat("maskDeg", deg.toFloat()).apply()
        _state.value = _state.value.copy(maskDeg = deg)
        recalcular()
    }

    fun setEnabled(c: Constellation, on: Boolean) {
        val nuevo = if (on) _state.value.enabled + c else _state.value.enabled - c
        if (nuevo.isEmpty()) return          // dejar al menos una
        prefs.edit().putStringSet("enabled", nuevo.map { it.name }.toSet()).apply()
        _state.value = _state.value.copy(enabled = nuevo)
        recalcular()
    }

    // ------------------------------- posicion y cielo -------------------------------

    private var oyente: LocationListener? = null
    private var cielo: GnssStatus.Callback? = null
    private var observados: List<ObservedSat> = emptyList()

    /** Se llama al entrar en la pestana: enciende el GPS para saber DONDE estamos. */
    @SuppressLint("MissingPermission")
    fun watchSky() {
        val ctx = getApplication<Application>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (oyente != null) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        _state.value = _state.value.copy(waitingFix = _state.value.latDeg == null)
        val l = LocationListener { loc ->
            // Solo la PRIMERA vez, o si el usuario no ha tocado la coordenada: si la ha
            // editado para planificar otro sitio, el GPS no debe devolverla a donde esta.
            if (_state.value.latDeg == null || _state.value.fromFix) {
                _state.value = _state.value.copy(
                    latDeg = loc.latitude, lonDeg = loc.longitude,
                    fromFix = true, waitingFix = false)
                recalcular()
            } else {
                _state.value = _state.value.copy(waitingFix = false)
            }
        }
        val c = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(st: GnssStatus) {
                observados = (0 until st.satelliteCount).mapNotNull { i ->
                    val con = when (st.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
                        GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
                        GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
                        GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
                        else -> null
                    } ?: return@mapNotNull null
                    ObservedSat(con, st.getSvid(i),
                                st.getAzimuthDegrees(i).toDouble(),
                                st.getElevationDegrees(i).toDouble())
                }
                comprobarContraElCielo()
            }
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l)
            lm.registerGnssStatusCallback(c, null)
            oyente = l; cielo = c
        }
    }

    fun stopSky() {
        val ctx = getApplication<Application>()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        oyente?.let { runCatching { lm?.removeUpdates(it) } }
        cielo?.let { runCatching { lm?.unregisterGnssStatusCallback(it) } }
        oyente = null; cielo = null
        _state.value = _state.value.copy(waitingFix = false)
    }

    /**
     * El modelo contra el cielo real. Es la unica forma honesta de decir cuanto vale el
     * almanaque guardado: por calendario solo se puede estimar, midiendo se sabe.
     */
    private fun comprobarContraElCielo() {
        val s = _state.value
        val lat = s.latDeg; val lon = s.lonDeg
        if (lat == null || lon == null || s.tles.isEmpty() || observados.isEmpty()) return
        // Solo tiene sentido contra el cielo de AHORA. Ver setDay().
        if (!s.isToday) return
        val r = ModelCheck.compare(s.tles, observados, System.currentTimeMillis(), lat, lon)

        // Lo que se pilla, se recuerda. Un satelite mal identificado no deja de estarlo al
        // salir de la vista, y la proxima vez puede que no este en el cielo para volver a
        // pillarlo -- pero seguiria inflando el recuento del dia entero.
        val nuevos = r.mismatched.map { VisibilityForecast.key(it.constellation, it.svid) }
        val union = s.excluded + nuevos
        if (union != s.excluded) {
            prefs.edit().putStringSet("excluded", union).apply()
            _state.value = s.copy(check = r, excluded = union)
            recalcular()          // la grafica deja de contarlos AHORA, no al reabrir
        } else {
            _state.value = s.copy(check = r)
        }
    }

    /** La coordenada escrita a mano: se planifica para OTRO sitio. */
    fun setManualPosition(lat: Double, lon: Double) {
        _state.value = _state.value.copy(latDeg = lat, lonDeg = lon, fromFix = false)
        recalcular()
        comprobarContraElCielo()
    }

    /** Volver a la del GPS. */
    fun useFix() {
        _state.value = _state.value.copy(fromFix = true, waitingFix = true)
        watchSky()
    }

    /** Medianoche local de un instante cualquiera. */
    private fun medianoche(ms: Long): Long =
        java.util.Calendar.getInstance().apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Cambia el dia del que se calcula la prevision.
     *
     * PARA UN DIA QUE NO ES HOY SE APAGA EL CONTRASTE contra el cielo. El contraste mide el
     * modelo contra lo que el receptor ve AHORA; comparar la prediccion de pasado manana con
     * los satelites de este momento no mide nada, y un numero que no significa nada en una
     * casilla que en otras circunstancias si significa algo es peor que no ponerlo.
     */
    fun setDay(ms: Long) {
        val d = medianoche(ms)
        _state.value = _state.value.copy(
            dayStartMillis = d,
            isToday = d == medianoche(System.currentTimeMillis()),
            // Se tira la comprobacion anterior en vez de dejarla en pantalla: era del dia de
            // hoy y quedaria bajo un grafico que ya habla de otro dia.
            check = if (d == medianoche(System.currentTimeMillis())) _state.value.check else null)
        recalcular()
    }

    fun setToday() = setDay(System.currentTimeMillis())
    fun setTomorrow() = setDay(System.currentTimeMillis() + 86_400_000L)

    /** Recalcula la prevision del dia elegido. */
    fun recalcular() {
        val s = _state.value
        val lat = s.latDeg; val lon = s.lonDeg
        if (lat == null || lon == null || s.tles.isEmpty()) return
        _state.value = s.copy(computing = true)
        viewModelScope.launch {
            // De medianoche a medianoche EN HORA LOCAL: la pregunta es "a que hora de ese
            // dia salgo a medir", no "que pasa en las proximas 24 horas".
            val inicio = s.dayStartMillis.takeIf { it > 0L }
                ?: medianoche(System.currentTimeMillis())
            val f = withContext(Dispatchers.Default) {
                VisibilityForecast.compute(
                    VisibilityForecast.withoutMismatched(s.tles, s.excluded),
                    lat, lon, inicio, maskDeg = s.maskDeg, enabled = s.enabled)
            }
            _state.value = _state.value.copy(
                forecast = f, computing = false,
                dayStartMillis = inicio,
                isToday = inicio == medianoche(System.currentTimeMillis()))
        }
    }

    /** Lo que se hace al arrancar: cargar, y bajar solo si hace falta y se puede. */
    fun cargarYPonerAlDia() {
        viewModelScope.launch {
            val cargado = withContext(Dispatchers.IO) { store.load() }
            publicar(cargado.tles, cargado.downloadedAtMillis, null)

            val ahora = System.currentTimeMillis()
            if (!AlmanacFreshness.shouldDownload(cargado.downloadedAtMillis, ahora)) return@launch
            if (!store.online(getApplication())) {
                // Se dice, y no se calla: quien esta a punto de salir a terreno con un
                // almanaque viejo tiene derecho a enterarse mientras todavia hay wifi.
                if (cargado.downloadedAtMillis == null)
                    publicar(cargado.tles, null, "No almanac yet, and no network to fetch one.")
                return@launch
            }
            descargar(automatica = true)
        }
    }

    /** El boton de actualizar a mano. Intenta siempre, aunque este fresco. */
    fun refreshNow() = descargar(automatica = false)

    private fun descargar(automatica: Boolean) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, note = null)
        viewModelScope.launch {
            val cuando = withContext(Dispatchers.IO) { store.download() }
            if (cuando == null) {
                // Lo guardado NO se toca. Un almanaque de dos meses sirve; ninguno, no.
                _state.value = _state.value.copy(
                    busy = false,
                    note = if (automatica) "Could not update the almanac. Keeping the stored one."
                           else "Download failed. The stored almanac is unchanged.")
                return@launch
            }
            val cargado = withContext(Dispatchers.IO) { store.load() }
            publicar(cargado.tles, cargado.downloadedAtMillis,
                     "Almanac updated: ${cargado.tles.size} satellites.")
        }
    }

    private fun publicar(tles: List<Tle>, cuando: Long?, nota: String?) {
        _state.value = AlmanacUiState(
            tles = tles,
            downloadedAtMillis = cuando,
            freshness = AlmanacFreshness.of(cuando, System.currentTimeMillis()),
            busy = false,
            note = nota,
            counts = Constellation.entries.associateWith { c -> tles.count { it.constellation == c } },
            enabled = _state.value.enabled,
            latDeg = _state.value.latDeg,
            lonDeg = _state.value.lonDeg,
            fromFix = _state.value.fromFix,
            forecast = _state.value.forecast,
            check = _state.value.check,
            showTotal = _state.value.showTotal,
            excluded = _state.value.excluded,
            maskDeg = _state.value.maskDeg,
            dayStartMillis = _state.value.dayStartMillis,
            isToday = _state.value.isToday,
            // La MEDIANA y no la media: un TLE con la epoca rara --uno recien lanzado, o uno
            // que el catalogo no ha refrescado-- no debe mover la referencia de los otros
            // ciento cuarenta.
            epochRefMillis = tles.map { it.epochMillis }.sorted()
                .let { if (it.isEmpty()) null else it[it.size / 2] },
        )
        recalcular()
    }

    /**
     * Olvida los descartados y vuelve a contarlos todos.
     *
     * Tiene que existir: la exclusion se aprende de una sola observacion, y una observacion
     * con la posicion equivocada --por ejemplo tras teclear a mano una coordenada de otro
     * sitio y olvidarse-- descartaria satelites buenos. Sin forma de deshacerlo, ese error
     * quedaria pegado a la app para siempre.
     */
    fun clearExcluded() {
        prefs.edit().remove("excluded").apply()
        _state.value = _state.value.copy(excluded = emptySet())
        recalcular()
    }

    fun clearNote() { _state.value = _state.value.copy(note = null) }
}
