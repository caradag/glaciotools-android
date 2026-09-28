package cl.umag.glaciertemp.core.sensors

/**
 * El horizonte como LISTA DE PUNTOS: azimut y elevacion, uno por uno.
 *
 * DOS MANERAS DE LLEGAR AL MISMO DATO. El telefono lo barre girando y deja 72 sectores; con
 * brujula y clinometro se levantan ocho o dieciseis puntos a mano, mas despacio y con mejor
 * puntería. Ninguna sustituye a la otra: la de mano es mas precisa por punto, la del telefono
 * cubre mucho mas. Teniendo las dos se pueden comparar, que es la unica forma de saber si
 * alguna se equivoco.
 *
 * Este es ademas el formato de la calculadora de ICE-D y CRONUS: dos lineas de numeros
 * separados por espacios. Copiar en ese formato es lo que permite pegar la medida en la
 * pagina y contrastar el factor con el que sale aqui.
 */
object HorizonPoints {

    data class Point(val azimuth: Double, val elevation: Double)

    /**
     * Las dos lineas que pide la calculadora: azimuts y elevaciones.
     *
     * SIN CABECERA NI ETIQUETAS. La pagina tiene dos campos y cada linea va a uno; cualquier
     * texto de mas habria que borrarlo a mano justo cuando se esta copiando y pegando.
     */
    fun toClipboard(points: List<Point>): String {
        val orden = points.sortedBy { Compass.normalize(it.azimuth) }
        return orden.joinToString(" ") { num(Compass.normalize(it.azimuth)) } + "\n" +
               orden.joinToString(" ") { num(it.elevation) }
    }

    /** Un perfil por sectores, como lista de puntos en el centro de cada sector. */
    fun fromProfile(profile: HorizonProfile): List<Point> =
        profile.elevations.indices.map { Point(profile.centerOf(it), profile.elevations[it]) }

    /**
     * Lee dos lineas de numeros: azimuts arriba, elevaciones abajo.
     *
     * ACEPTA ESPACIOS, COMAS Y SALTOS. Lo que se pega viene de una hoja de calculo, de un
     * cuaderno tecleado o de la propia pagina, y cada sitio separa como quiere. Rechazar por
     * el separador seria rechazar por algo que no es el dato.
     */
    fun parse(azimuths: String, elevations: String): Result {
        val a = numeros(azimuths)
        val e = numeros(elevations)
        if (a.isEmpty() && e.isEmpty()) return Result(emptyList(), null)
        if (a.size != e.size)
            return Result(emptyList(),
                          "${a.size} azimuth${if (a.size == 1) "" else "s"} but " +
                          "${e.size} elevation${if (e.size == 1) "" else "s"}: they must pair up.")
        val malAz = a.firstOrNull { it < 0.0 || it > 360.0 }
        if (malAz != null) return Result(emptyList(), "Azimuth $malAz is outside 0–360.")
        val malEl = e.firstOrNull { it < -90.0 || it > 90.0 }
        if (malEl != null) return Result(emptyList(), "Elevation $malEl is outside −90–90.")
        if (a.size < 3)
            return Result(emptyList(), "At least three points are needed to close a horizon.")
        return Result(a.indices.map { Point(a[it], e[it]) }, null)
    }

    data class Result(val points: List<Point>, val error: String?)

    private fun numeros(t: String): List<Double> =
        t.split(' ', ',', '\n', '\r', '\t', ';')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toDoubleOrNull() }

    private fun num(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) "%.0f".format(java.util.Locale.ROOT, v) else "%.1f".format(java.util.Locale.ROOT, v)

    /** El perfil de 1 grado que pide el calculo de apantallamiento. */
    fun toShieldingHorizon(points: List<Point>): DoubleArray =
        Shielding.interpolate(points.map { it.azimuth }.toDoubleArray(),
                              points.map { it.elevation.coerceAtLeast(0.0) }.toDoubleArray())
}
