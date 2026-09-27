package cl.umag.glaciertemp.app

import android.hardware.GeomagneticField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
 */
object Declination {

    /**
     * Grados que hay que SUMAR a un azimut magnetico para obtener el verdadero.
     *
     * Positivo al este. Sale del World Magnetic Model, que Android trae en
     * [GeomagneticField]; no es una constante, cambia con el sitio y con el ano.
     */
    fun at(latitude: Double, longitude: Double, altitudeMetres: Double,
           epochMillis: Long = System.currentTimeMillis()): Double =
        GeomagneticField(latitude.toFloat(), longitude.toFloat(),
                         altitudeMetres.toFloat(), epochMillis).declination.toDouble()

    /** "12.4° E" o "3.1° W", que es como se escribe una declinacion. */
    fun describe(deg: Double): String =
        if (deg >= 0) "%.1f° E".format(deg) else "%.1f° W".format(-deg)
}

/**
 * La declinacion del sitio donde se esta, o null si todavia no se sabe donde es.
 *
 * NULL SIGNIFICA "NO SE SABE", y quien lo reciba tiene que decirlo en vez de suponer cero:
 * sin posicion no hay declinacion, y presentar el azimut magnetico como si fuera verdadero
 * es exactamente el error que esto viene a evitar.
 */
@Composable
fun rememberDeclination(location: LocationSource?): Double? {
    var valor by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(location) {
        val loc = location ?: return@LaunchedEffect
        val fix = runCatching { loc.lastKnownFix() }.getOrNull()
            ?: runCatching { loc.freshFix(30_000L) }.getOrNull()
            ?: return@LaunchedEffect
        valor = Declination.at(fix.latitude, fix.longitude, fix.altitudeMetres ?: 0.0)
    }
    return valor
}
