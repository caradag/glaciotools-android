package cl.umag.glaciertemp.core.sensors

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Promediar angulos NO es promediar numeros.
 *
 * EL PROBLEMA. Un rumbo de 359 grados y otro de 1 estan separados por dos grados, pero su
 * media aritmetica es 180: justo el rumbo contrario. La mediana ordenando tampoco vale --con
 * 358, 359, 0, 1, 2 la lista ordenada es 0, 1, 2, 358, 359 y el valor central sale 2 en vez
 * de 0--. Cualquiera de las dos cuentas hechas a la ligera da la vuelta entera de error
 * exactamente cuando el telefono apunta al norte, que no es un caso raro.
 *
 * COMO SE RESUELVE. Cada angulo se trata como un punto en el circulo unidad: se promedian el
 * seno y el coseno y se recupera el angulo del resultado. Para la mediana, primero se
 * "desenrolla" cada valor al entorno de esa media --sumandole o restandole vueltas hasta que
 * caiga a menos de media vuelta-- y entonces ya se puede ordenar.
 *
 * TAMBIEN SE APLICA A PITCH Y ROLL. Ahi el salto esta en +-180, que es el telefono boca
 * abajo: exactamente la segunda mitad de una medida de albedo. Con datos que no cruzan
 * ningun salto, estas cuentas dan lo mismo que las de toda la vida, asi que no se pierde
 * nada por usarlas siempre.
 */
object Angles {

    /**
     * Media circular, en (-180, 180].
     *
     * Devuelve null si la lista esta vacia, y tambien si los angulos estan tan repartidos
     * que su vector suma es practicamente nulo: con datos apuntando a todas partes por igual
     * no hay una direccion media, y devolver la que salga del ruido seria inventarsela.
     */
    fun mean(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        var sx = 0.0
        var sy = 0.0
        values.forEach { v ->
            val r = Math.toRadians(v)
            sx += cos(r); sy += sin(r)
        }
        val n = values.size
        // Longitud del vector medio. Por debajo de esto no hay direccion que declarar.
        val r = Math.hypot(sx / n, sy / n)
        if (r < 1e-9) return null
        return wrap(Math.toDegrees(atan2(sy, sx)))
    }

    /**
     * Mediana circular, en (-180, 180].
     *
     * Con un numero par de muestras se promedian --circularmente-- las dos centrales, como
     * en la mediana de toda la vida.
     */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val centro = mean(values) ?: return null
        val desenrollados = values.map { centro + wrap(it - centro) }.sorted()
        val n = desenrollados.size
        val m = if (n % 2 == 1) desenrollados[n / 2]
                else (desenrollados[n / 2 - 1] + desenrollados[n / 2]) / 2.0
        return wrap(m)
    }

    /** A (-180, 180]. */
    fun wrap(deg: Double): Double {
        var d = deg % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }
}

/** Cuando los angulos de Euler dejan de significar lo que parecen. */
object Tilt {

    /**
     * Cerca de la vertical, yaw y roll dejan de estar determinados.
     *
     * EL PROBLEMA SE LLAMA GIMBAL LOCK y no es un fallo del telefono: con el pitch en +-90
     * --el telefono de pie, apuntando al cenit o al nadir-- girar sobre el eje del yaw y
     * girar sobre el del roll son el MISMO giro, asi que hay infinitas parejas de valores
     * que describen la misma orientacion. Los sensores entregan una cualquiera, y salta de
     * una a otra con el mas minimo temblor.
     *
     * IMPORTA AQUI porque es justo la postura que invita la herramienta: apoyar el telefono
     * contra una pared de hielo para medir su inclinacion. El PITCH sigue siendo bueno --que
     * es el dato que se buscaba-- pero apuntar el yaw de esa medida seria anotar ruido.
     */
    const val LIMITE_GRADOS = 80.0

    fun gimbalLock(pitch: Double?): Boolean =
        pitch != null && kotlin.math.abs(Angles.wrap(pitch)) >= LIMITE_GRADOS

    fun warning(pitch: Double?): String? =
        if (!gimbalLock(pitch)) null
        else "Near vertical: pitch is good, but yaw and roll are not separable at this " +
             "attitude and will jump with the slightest movement. Use the pitch only."
}

/** Media y mediana de numeros corrientes: los lux del sensor de luz. */
object Scalars {

    fun mean(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.sum() / values.size

    /**
     * La mediana.
     *
     * PARA QUE. En cinco segundos de medida el sensor de luz puede dar un valor disparatado
     * --un reflejo, un cambio de escala de ganancia, una sombra que cruza-- y la media se lo
     * lleva entero. La mediana no. Que las dos se parezcan es la senal de que la luz estuvo
     * quieta; que difieran dice que algo paso durante la medida.
     */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
