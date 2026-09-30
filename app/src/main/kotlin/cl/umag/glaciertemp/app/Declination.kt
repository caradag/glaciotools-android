package cl.umag.glaciertemp.app

import android.hardware.GeomagneticField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cl.umag.glaciertemp.core.geomag.AndroidMagneticModel
import cl.umag.glaciertemp.core.geomag.DecimalYear
import cl.umag.glaciertemp.core.geomag.MagneticModels
import cl.umag.glaciertemp.core.geomag.MagneticResult
import kotlin.math.abs

/**
 * La declinacion magnetica: cuanto se aparta el norte de la brujula del norte real.
 *
 * POR QUE HACE FALTA. Todo lo que mide el telefono esta referido al norte MAGNETICO: el
 * sistema de referencia de `TYPE_ROTATION_VECTOR` tiene su eje Y apuntando al polo
 * geomagnetico, y Android no corrige nada. La posicion del sol, en cambio, sale de
 * astronomia pura y esta referida al norte REAL. Dibujar las dos cosas en el mismo grafico
 * sin corregir las desplaza una respecto de la otra la declinacion entera, que en Patagonia
 * son mas de diez grados: bastante para equivocarse en decenas de minutos sobre a que hora
 * asoma el sol por encima de un cerro.
 *
 * EL FACTOR DE APANTALLAMIENTO NO SE ENTERA --es una integral sobre todo el azimut, y una
 * rotacion rigida solo permuta sus terminos-- pero el panel solar y el recorrido del sol si.
 *
 * SE CORRIGE AL MEDIR, no al dibujar. Convirtiendo una sola vez, en la entrada, todo lo de
 * aguas abajo --grafico, sol, panel, lo que se copia, lo que se guarda-- habla del mismo
 * norte sin tener que acordarse en cada sitio.
 *
 * MODELO PROPIO, NO EL DE ANDROID. `GeomagneticField` trae WMM-2015 hasta Android 11 y
 * WMM-2020 desde Android 12, sin actualizar en 2026: fuera de su epoca extrapola en linea
 * recta. En Patagonia el error todavia es de decimas, pero en el mundo llega a 8 grados con
 * WMM-2015, y para otra fecha no sirve. Se usa WMM2025 (y IGRF-14 fuera de 2025-2030) desde
 * :core, validados contra los valores oficiales. El de Android queda solo como comparacion
 * en la calculadora.
 */
object Declination {

    /** El campo con el modelo que corresponde a esa fecha, o null si ninguno la cubre. */
    fun at(latitude: Double, longitude: Double, altitudeMetres: Double,
           epochMillis: Long = System.currentTimeMillis()): MagneticResult? {
        val ano = DecimalYear.fromEpochMillis(epochMillis)
        val modelo = MagneticModels.forDate(ano) ?: return null
        return MagneticModels.evaluate(modelo, latitude, longitude, altitudeMetres, ano)
    }

    /** "12.4° E" o "3.1° W", que es como se escribe una declinacion. */
    fun describe(deg: Double): String =
        if (deg >= 0) "%.1f° E".format(deg) else "%.1f° W".format(-deg)

    /**
     * La variacion anual en minutos de arco, que es como la dan las cartas: "5.3′ W per year".
     * Hacia el oeste si la declinacion DISMINUYE (se hace menos este o mas oeste).
     */
    fun describeRate(degPerYear: Double): String {
        val min = degPerYear * 60
        return if (abs(min) < 0.05) "no annual change"
        else "%.1f′ %s per year".format(abs(min), if (min > 0) "E" else "W")
    }
}

/**
 * El modelo que trae el Android de este telefono, y su declinacion.
 *
 * Solo para la comparacion de la calculadora: se identifica midiendo (ver
 * `AndroidMagneticModel`), una vez por proceso.
 */
object PlatformMagneticModel {
    val name: String? by lazy {
        AndroidMagneticModel.identify { lat, lon, t -> declination(lat, lon, 0.0, t) }
    }

    fun declination(lat: Double, lon: Double, altitudeMetres: Double, epochMillis: Long): Double =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altitudeMetres.toFloat(), epochMillis)
            .declination.toDouble()

    fun inclination(lat: Double, lon: Double, altitudeMetres: Double, epochMillis: Long): Double =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altitudeMetres.toFloat(), epochMillis)
            .inclination.toDouble()
}

/**
 * El campo magnetico del sitio donde se esta, ahora, o null si todavia no se sabe donde es
 * o la fecha cae fuera de todo modelo.
 *
 * NULL SIGNIFICA "NO SE SABE", y quien lo reciba tiene que decirlo en vez de suponer cero:
 * sin posicion no hay declinacion, y presentar el azimut magnetico como si fuera verdadero
 * es exactamente el error que esto viene a evitar.
 */
@Composable
fun rememberMagneticField(location: LocationSource?): MagneticResult? {
    var valor by remember { mutableStateOf<MagneticResult?>(null) }
    LaunchedEffect(location) {
        val loc = location ?: return@LaunchedEffect
        val fix = runCatching { loc.lastKnownFix() }.getOrNull()
            ?: runCatching { loc.freshFix(30_000L) }.getOrNull()
            ?: return@LaunchedEffect
        valor = Declination.at(fix.latitude, fix.longitude, fix.altitudeMetres ?: 0.0)
    }
    return valor
}

/** Solo la declinacion, para quien no necesita mas (inclinometro, horizonte). */
@Composable
fun rememberDeclination(location: LocationSource?): Double? =
    rememberMagneticField(location)?.declination
