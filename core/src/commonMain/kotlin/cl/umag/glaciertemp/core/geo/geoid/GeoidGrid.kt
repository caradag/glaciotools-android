package cl.umag.glaciertemp.core.geo.geoid

import kotlin.math.floor

/**
 * Una grilla de ondulacion del geoide (N), entera o una ventana de ella (una tesela).
 *
 * LOS INDICES SON LOS DE LA GRILLA GLOBAL de GeographicLib: columna 0 en 0 grados E hacia el
 * este, fila 0 en 90 N hacia el sur, `width = 360/paso` columnas y `height = 180/paso + 1`
 * filas. Una tesela es una ventana [x0, x0+xs) x [y0, y0+ys) de esa grilla con un borde de
 * dos muestras, para que la interpolacion nunca necesite a la vecina.
 *
 * LA INTERPOLACION ES LA DE GeographicLib (Geoid::height, cubica de 12 puntos, licencia MIT),
 * portada tal cual: asi GeoidEval sirve de oraculo exacto en las pruebas y se hereda su
 * precision documentada (EGM2008 1': 3 mm maximo; EGM96 15': 0,17 m maximo, 7 mm rms).
 *
 * Los valores son enteros sin signo de 16 bits: N = offset + scale * valor.
 */
class GeoidGrid(
    val model: String,
    val width: Int,
    val height: Int,
    val x0: Int,
    val y0: Int,
    val xs: Int,
    val ys: Int,
    val offset: Double,
    val scale: Double,
    private val data: ShortArray,
) {
    init {
        require(data.size == xs * ys) { "tesela $model: ${data.size} muestras para ${xs}x$ys" }
    }

    private val rlonres = width / 360.0
    private val rlatres = (height - 1) / 180.0

    /** Paso de la grilla en minutos de arco. */
    val stepArcMinutes: Double get() = 60.0 / rlonres

    private class FueraDeTesela : RuntimeException()

    private fun raw(ix0: Int, iy0: Int): Double {
        var ix = ix0
        var iy = iy0
        if (ix < 0) ix += width else if (ix >= width) ix -= width
        if (iy < 0 || iy >= height) {           // cruzando el polo
            iy = if (iy < 0) -iy else 2 * (height - 1) - iy
            ix += (if (ix < width / 2) 1 else -1) * width / 2
        }
        val dx = ((ix - x0) % width + width) % width
        val dy = iy - y0
        if (dx >= xs || dy < 0 || dy >= ys) throw FueraDeTesela()
        return (data[dy * xs + dx].toInt() and 0xFFFF).toDouble()
    }

    /** N en metros, o null si el punto no cae en esta tesela (con su entorno). */
    fun undulation(latitude: Double, longitude: Double): Double? {
        if (latitude.isNaN() || longitude.isNaN() || latitude < -90 || latitude > 90) return null
        val lon = ((longitude % 360) + 540) % 360 - 180          // (-180, 180]
        var fx = lon * rlonres
        var fy = -latitude * rlatres
        var ix = floor(fx).toInt()
        var iy = minOf((height - 1) / 2 - 1, floor(fy).toInt())
        fx -= ix
        fy -= iy
        iy += (height - 1) / 2
        ix += if (ix < 0) width else if (ix >= width) -width else 0
        return try {
            val v = doubleArrayOf(
                raw(ix, iy - 1), raw(ix + 1, iy - 1),
                raw(ix - 1, iy), raw(ix, iy), raw(ix + 1, iy), raw(ix + 2, iy),
                raw(ix - 1, iy + 1), raw(ix, iy + 1), raw(ix + 1, iy + 1), raw(ix + 2, iy + 1),
                raw(ix, iy + 2), raw(ix + 1, iy + 2))
            val (c3, c0) = when (iy) {
                0 -> C3N to C0N
                height - 2 -> C3S to C0S
                else -> C3 to C0
            }
            val t = DoubleArray(NTERMS) { i ->
                var s = 0.0
                for (j in 0 until STENCIL) s += v[j] * c3[NTERMS * j + i]
                s / c0
            }
            val h = t[0] + fx * (t[1] + fx * (t[3] + fx * t[6])) +
                    fy * (t[2] + fx * (t[4] + fx * t[7]) + fy * (t[5] + fx * t[8] + fy * t[9]))
            offset + scale * h
        } catch (_: FueraDeTesela) {
            null
        }
    }

    companion object {
        private const val STENCIL = 12
        private const val NTERMS = 10

        // Matrices de GeographicLib (src/Geoid.cpp, v2.5): ajuste cubico con 12 puntos y sus
        // variantes junto al polo norte (C3N) y sur (C3S), que hacen N independiente de la
        // longitud en el propio polo.
        private const val C0 = 240
        private val C3 = intArrayOf(
            9, -18, -88, 0, 96, 90, 0, 0, -60, -20,
            -9, 18, 8, 0, -96, 30, 0, 0, 60, -20,
            9, -88, -18, 90, 96, 0, -20, -60, 0, 0,
            186, -42, -42, -150, -96, -150, 60, 60, 60, 60,
            54, 162, -78, 30, -24, -90, -60, 60, -60, 60,
            -9, -32, 18, 30, 24, 0, 20, -60, 0, 0,
            -9, 8, 18, 30, -96, 0, -20, 60, 0, 0,
            54, -78, 162, -90, -24, 30, 60, -60, 60, -60,
            -54, 78, 78, 90, 144, 90, -60, -60, -60, -60,
            9, -8, -18, -30, -24, 0, 20, 60, 0, 0,
            -9, 18, -32, 0, 24, 30, 0, 0, -60, 20,
            9, -18, -8, 0, -24, -30, 0, 0, 60, 20)
        private const val C0N = 372
        private val C3N = intArrayOf(
            0, 0, -131, 0, 138, 144, 0, 0, -102, -31,
            0, 0, 7, 0, -138, 42, 0, 0, 102, -31,
            62, 0, -31, 0, 0, -62, 0, 0, 0, 31,
            124, 0, -62, 0, 0, -124, 0, 0, 0, 62,
            124, 0, -62, 0, 0, -124, 0, 0, 0, 62,
            62, 0, -31, 0, 0, -62, 0, 0, 0, 31,
            0, 0, 45, 0, -183, -9, 0, 93, 18, 0,
            0, 0, 216, 0, 33, 87, 0, -93, 12, -93,
            0, 0, 156, 0, 153, 99, 0, -93, -12, -93,
            0, 0, -45, 0, -3, 9, 0, 93, -18, 0,
            0, 0, -55, 0, 48, 42, 0, 0, -84, 31,
            0, 0, -7, 0, -48, -42, 0, 0, 84, 31)
        private const val C0S = 372
        private val C3S = intArrayOf(
            18, -36, -122, 0, 120, 135, 0, 0, -84, -31,
            -18, 36, -2, 0, -120, 51, 0, 0, 84, -31,
            36, -165, -27, 93, 147, -9, 0, -93, 18, 0,
            210, 45, -111, -93, -57, -192, 0, 93, 12, 93,
            162, 141, -75, -93, -129, -180, 0, 93, -12, 93,
            -36, -21, 27, 93, 39, 9, 0, -93, -18, 0,
            0, 0, 62, 0, 0, 31, 0, 0, 0, -31,
            0, 0, 124, 0, 0, 62, 0, 0, 0, -62,
            0, 0, 124, 0, 0, 62, 0, 0, 0, -62,
            0, 0, 62, 0, 0, 31, 0, 0, 0, -31,
            -18, 36, -64, 0, 66, 51, 0, 0, -102, 31,
            18, -36, 2, 0, -66, -51, 0, 0, 102, 31)

        private val MAGIA = "GTGEOID1".encodeToByteArray()

        /**
         * Lee el formato de tesela (ver tools/geoid/make_tiles.py): "GTGEOID1", nombre del
         * modelo (u8 largo + ASCII), width, height, x0, y0, xs, ys (i32), offset, scale (f64)
         * y xs*ys enteros sin signo de 16 bits, todo big-endian.
         */
        fun parse(bytes: ByteArray): GeoidGrid {
            var p = 0
            require(bytes.size > 60 && bytes.copyOfRange(0, 8).contentEquals(MAGIA)) { "no es una tesela de geoide" }
            p = 8
            val n = bytes[p++].toInt() and 0xFF
            val nombre = bytes.copyOfRange(p, p + n).decodeToString(); p += n
            fun i32(): Int { val v = ((bytes[p].toInt() and 0xFF) shl 24) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                                     ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF); p += 4; return v }
            fun f64(): Double { var b = 0L; repeat(8) { b = (b shl 8) or (bytes[p++].toLong() and 0xFF) }; return Double.fromBits(b) }
            val w = i32(); val h = i32(); val x0 = i32(); val y0 = i32(); val xs = i32(); val ys = i32()
            val off = f64(); val sc = f64()
            require(bytes.size - p == xs * ys * 2) { "tesela $nombre truncada" }
            val d = ShortArray(xs * ys) { i ->
                (((bytes[p + 2 * i].toInt() and 0xFF) shl 8) or (bytes[p + 2 * i + 1].toInt() and 0xFF)).toShort()
            }
            return GeoidGrid(nombre, w, h, x0, y0, xs, ys, off, sc, d)
        }
    }
}
