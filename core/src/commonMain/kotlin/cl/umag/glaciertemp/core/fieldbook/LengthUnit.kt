package cl.umag.glaciertemp.core.fieldbook

import cl.umag.glaciertemp.core.Decimals
import kotlin.math.round

/**
 * La unidad en que se ESCRIBEN y se LEEN las longitudes del aforo: ancho, intervalo,
 * posiciones y profundidades.
 *
 * LO GUARDADO SIGUE EN METROS. La unidad es de la pantalla, no del dato: los ficheros, la
 * exportacion y el calculo del caudal no cambian con ella, y un aforo medido en centimetros
 * se abre igual en un telefono configurado en metros. Y cambia TODAS las casillas a la vez:
 * una tabla con x en metros y la profundidad en centimetros invita a leer mal justo el numero
 * que se esta tecleando con el rio delante.
 */
enum class LengthUnit(val suffix: String, val label: String, private val perMetre: Double) {
    METRE("m", "Metres", 1.0),
    CENTIMETRE("cm", "Centimetres", 100.0);

    /**
     * Redondeo a 1e-9: la ida y vuelta 0,35 m -> 35 cm -> 0,35 m no puede dejar
     * 0,35000000000000003, que haria creer al campo que el valor cambio desde fuera y le
     * reescribiria el texto al usuario mientras teclea.
     */
    private fun limpio(x: Double) = round(x * 1e9) / 1e9

    fun fromMetres(m: Double): Double = limpio(m * perMetre)
    fun toMetres(v: Double): Double = limpio(v / perMetre)

    /**
     * Texto de una longitud guardada en metros, con la resolucion que tendria en metros
     * [metreDecimals]: 3 decimales en metros (mm) son 1 decimal en centimetros.
     */
    fun format(metres: Double, metreDecimals: Int, fixed: Boolean = false): String {
        val d = (metreDecimals - if (this == CENTIMETRE) 2 else 0).coerceAtLeast(0)
        val v = fromMetres(metres)
        return if (fixed) Decimals.fixed(v, d) else Decimals.trimmed(v, d)
    }

    companion object {
        fun fromName(name: String?): LengthUnit = entries.firstOrNull { it.name == name } ?: METRE
    }
}
