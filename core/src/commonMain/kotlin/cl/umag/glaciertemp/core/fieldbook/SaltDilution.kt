package cl.umag.glaciertemp.core.fieldbook

import kotlin.math.max

/**
 * Un aforo por dilucion de sal: se echa al rio una masa conocida de sal disuelta y aguas abajo
 * se mide como sube y vuelve a bajar la conductividad mientras pasa la nube.
 *
 * EL PRINCIPIO. Toda la sal que entra tiene que pasar por el punto de medida. Si en cada
 * instante pasa un caudal Q con una concentracion de sal c(t) por encima de la natural, la
 * masa que pasa es Q * integral(c dt), y eso tiene que ser igual a la masa echada:
 *
 *     M = Q * integral(c(t) dt)      ->      Q = M / integral(c(t) dt)
 *
 * La concentracion no se mide: se mide la conductividad, y la calibracion dice cuanta sal
 * corresponde a cada microsiemens de mas. Con el factor de calibracion Cal en (mg/L)/(uS/cm)
 * y la conductividad por encima de la de base, c(t) = Cal * (C(t) - C0), de donde
 *
 *     Q = M / (Cal * Sigma),     Sigma = integral((C(t) - C0) dt)
 *
 * que es la ecuacion (2) de Merz y Doppmann (2006), la guia practica del ICIMOD.
 *
 * LA CALIBRACION VA EN EL SENTIDO DE LA GUIA. Cal es concentracion POR conductividad --la
 * pendiente de concentracion frente a conductividad, entre 0,45 y 0,6 con sal comun-- y no al
 * reves: solo asi la formula de arriba da un caudal. La pantalla dibuja la conductividad en
 * el eje vertical, que es lo que se mide y donde esta el error, y el ajuste se hace en ese
 * sentido; el factor es la inversa de esa pendiente.
 */
data class ConductivityReading(
    val atEpochMillis: Long,
    /** Conductividad en microsiemens por centimetro. */
    val microSiemensPerCm: Double,
)

/**
 * La herramienta de calibracion: agua del rio, solucion de referencia a gotas, y la
 * conductividad tras cada gota.
 *
 * Los valores por defecto son los del procedimiento de la guia (hoja 1): 500 mL de agua del
 * rio, una solucion de 3 g de sal por litro, y diez adiciones de 1 mL.
 */
data class SaltCalibration(
    val waterVolumeMl: Double = 500.0,
    val referenceVolumeMl: Double = 1000.0,
    val referenceSaltG: Double = 3.0,
    val incrementMl: Double = 1.0,
    /** Cuantas adiciones. La tabla tiene una fila mas: la del agua sola, sin sal anadida. */
    val points: Int = 10,
    /**
     * La conductividad medida tras cada adicion, en uS/cm. El indice es el numero de
     * adiciones, asi que la posicion 0 es el agua del rio sin nada. Nulo donde no se ha medido.
     */
    val conductivities: List<Double?> = emptyList(),
)

/** De donde salieron las lecturas de conductividad. Solo cambia lo que se cuenta de ellas. */
enum class ReadingsSource { MANUAL, IMPORTED }

data class SaltDilution(
    /** Masa de sal inyectada, en gramos. */
    val saltMassG: Double? = null,
    /**
     * Donde se echo la sal. Aparte de la posicion de la entrada, que es la del perfil donde se
     * mide: entre una y otra hay la distancia de mezcla, decenas de metros.
     */
    val injectionPosition: FieldPosition? = null,
    val injectionEpochMillis: Long? = null,
    /** Distancia entre la inyeccion y el punto de medida, en metros. */
    val injectionDistanceM: Double? = null,
    val injectionNotes: String = "",
    /** Factor de calibracion, en (mg/L)/(uS/cm). */
    val calibrationFactor: Double? = null,
    val calibration: SaltCalibration? = null,
    val readings: List<ConductivityReading> = emptyList(),
    val readingsSource: ReadingsSource = ReadingsSource.MANUAL,
    /** Nombre del fichero importado, si las lecturas vienen de un logger. */
    val importedFile: String? = null,
    /** Conductividad natural del agua, en uS/cm, elegida sobre el grafico. */
    val baseConductivity: Double? = null,
    /**
     * Donde empieza y acaba la integral. Null es "desde el principio" y "hasta el final".
     *
     * Existe por los logger: un fichero trae horas antes y despues del paso de la sal, y a lo
     * largo de horas la conductividad del rio deriva. Un microsiemens de deriva sobre tres
     * horas suma a Sigma tanto como la nube de sal entera, asi que integrar el fichero de
     * punta a punta puede doblar el error sin que el grafico lo delate.
     */
    val windowStartMillis: Long? = null,
    val windowEndMillis: Long? = null,
    /**
     * Si se pulso Calculate con los datos que hay AHORA. Cualquier cambio en lo que entra en
     * la cuenta lo vuelve a false: un caudal que se queda en pantalla despues de cambiar la
     * base dice un numero que ya no corresponde a lo que se ve.
     */
    val calculated: Boolean = false,
) {
    fun isEmpty(): Boolean =
        saltMassG == null && injectionPosition == null && injectionDistanceM == null &&
        injectionNotes.isBlank() && calibrationFactor == null && calibration == null &&
        readings.isEmpty() && baseConductivity == null
}

object SaltDilutionMath {

    /** Una fila de la tabla de calibracion. */
    data class CalibrationRow(
        /** Numero de adiciones de solucion de referencia: 0 es el agua sola. */
        val index: Int,
        /** Volumen total en el recipiente tras las adiciones, en mL. */
        val volumeMl: Double,
        /** Concentracion de sal ANADIDA, en g/L. */
        val concentrationGL: Double,
        val conductivity: Double?,
    )

    /**
     * La tabla de calibracion con las concentraciones calculadas.
     *
     * SE CUENTA EL VOLUMEN QUE CRECE. La guia redondea a 6, 12, 18... mg/L, que es dividir
     * la sal anadida por 500 mL siempre; en realidad tras diez adiciones hay 510 mL, y esa
     * diferencia del 2 % iria entera al factor de calibracion y de ahi al caudal.
     *
     * Es la sal ANADIDA, no la total: el agua del rio ya trae la suya, pero lo que se ajusta
     * es la pendiente, y a la pendiente le da igual el punto de partida.
     */
    fun calibrationRows(c: SaltCalibration): List<CalibrationRow> {
        val n = c.points.coerceIn(0, MAX_CALIBRATION_POINTS)
        val referenciaGL = if (c.referenceVolumeMl > 0) c.referenceSaltG / (c.referenceVolumeMl / 1000.0) else 0.0
        return (0..n).map { i ->
            val anadido = i * c.incrementMl
            val volumen = c.waterVolumeMl + anadido
            val conc = if (volumen > 0) anadido * referenciaGL / volumen else 0.0
            CalibrationRow(i, volumen, conc, c.conductivities.getOrNull(i))
        }
    }

    const val MAX_CALIBRATION_POINTS = 100

    /**
     * La recta de calibracion: conductividad (uS/cm) frente a concentracion (g/L).
     *
     * EN ESTE SENTIDO Y NO EN EL DE LA GUIA. La concentracion la fija la pipeta y es casi
     * exacta; lo que lleva el error es la lectura del conductimetro. Los minimos cuadrados
     * suponen el error en la variable de la izquierda, asi que la que va a la izquierda es la
     * conductividad. La guia hace la regresion al reves; con calibraciones buenas (R2 de
     * 0,999) da lo mismo, y con una mala esta es la que no sesga la pendiente.
     */
    data class Fit(
        /** uS/cm por g/L. */
        val slope: Double,
        /** uS/cm: la conductividad del agua sin sal anadida, segun la recta. */
        val intercept: Double,
        val r2: Double?,
        val n: Int,
    ) {
        /**
         * El factor de calibracion en (mg/L)/(uS/cm): la inversa de la pendiente, pasada de
         * g a mg. Null si la pendiente no es positiva --la conductividad tiene que subir con
         * la sal, y si no sube la calibracion esta mal, no hay factor que sacar.
         */
        val calibrationFactor: Double? get() = if (slope > 0) 1000.0 / slope else null
    }

    fun fit(rows: List<CalibrationRow>): Fit? {
        val pts = rows.mapNotNull { r ->
            r.conductivity?.takeIf { it.isFinite() }?.let { r.concentrationGL to it }
        }
        if (pts.size < 2) return null
        val n = pts.size
        val mx = pts.sumOf { it.first } / n
        val my = pts.sumOf { it.second } / n
        val sxx = pts.sumOf { (x, _) -> (x - mx) * (x - mx) }
        if (sxx <= 0.0) return null
        val sxy = pts.sumOf { (x, y) -> (x - mx) * (y - my) }
        val syy = pts.sumOf { (_, y) -> (y - my) * (y - my) }
        val pendiente = sxy / sxx
        val r2 = if (syy > 0) (sxy * sxy) / (sxx * syy) else null
        return Fit(pendiente, my - pendiente * mx, r2, n)
    }

    /** Las lecturas que entran en la integral: ordenadas y dentro de la ventana. */
    fun windowed(s: SaltDilution): List<ConductivityReading> =
        s.readings.sortedBy { it.atEpochMillis }.filter { r ->
            (s.windowStartMillis == null || r.atEpochMillis >= s.windowStartMillis) &&
            (s.windowEndMillis == null || r.atEpochMillis <= s.windowEndMillis)
        }

    /**
     * Sigma: la integral del exceso de conductividad sobre la base, en (uS/cm)*s.
     *
     * POR TRAPECIOS SOBRE LAS HORAS REALES, y no la suma de la guia (suma de lecturas por un
     * intervalo fijo). La suma supone que se leyo cada cinco segundos exactos; a mano nunca es
     * asi --se lee, se teclea, se vuelve a mirar-- y un logger puede cambiar de intervalo.
     * Con intervalo constante, los trapecios y la suma coinciden salvo en las dos puntas,
     * donde la nube ya paso y el exceso es cero.
     *
     * Un exceso negativo cuenta como cero: es ruido del conductimetro por debajo de la base,
     * no sal que falta.
     */
    fun sigma(readings: List<ConductivityReading>, base: Double): Double {
        val o = readings.sortedBy { it.atEpochMillis }
        var s = 0.0
        for (i in 1 until o.size) {
            val dt = (o[i].atEpochMillis - o[i - 1].atEpochMillis) / 1000.0
            if (dt <= 0.0) continue
            val a = max(o[i - 1].microSiemensPerCm - base, 0.0)
            val b = max(o[i].microSiemensPerCm - base, 0.0)
            s += (a + b) / 2.0 * dt
        }
        return s
    }

    /**
     * El caudal en m3/s.
     *
     * Con M en gramos, Cal en (mg/L)/(uS/cm) y Sigma en (uS/cm)*s, M*1000/(Cal*Sigma) da
     * litros por segundo, y entre mil, metros cubicos: los dos mil se cancelan.
     */
    fun dischargeM3s(saltMassG: Double, calibrationFactor: Double, sigma: Double): Double? {
        if (!saltMassG.isFinite() || !calibrationFactor.isFinite() || !sigma.isFinite()) return null
        if (saltMassG <= 0.0 || calibrationFactor <= 0.0 || sigma <= 0.0) return null
        return saltMassG / (calibrationFactor * sigma)
    }

    /** Lo que falta para poder calcular, en palabras. Vacio si no falta nada. */
    fun missing(s: SaltDilution): List<String> = buildList {
        if (s.saltMassG == null || s.saltMassG <= 0.0) add("the injected salt mass")
        if (s.calibrationFactor == null || s.calibrationFactor <= 0.0) add("the calibration factor")
        if (windowed(s).size < 2) add("at least two conductivity readings")
        if (s.baseConductivity == null) add("the base conductivity")
    }

    data class Result(
        val dischargeM3s: Double?,
        /** (uS/cm)*s */
        val sigma: Double,
        /** Lecturas que entraron en la cuenta. */
        val readings: Int,
        val peakExcess: Double?,
        val peakAtMillis: Long?,
        /** Cuanto duro la nube: del primer al ultimo exceso positivo. */
        val passageSeconds: Double?,
        val firstMillis: Long?,
        val lastMillis: Long?,
    )

    fun compute(s: SaltDilution): Result {
        val lecturas = windowed(s)
        val base = s.baseConductivity
        if (base == null || lecturas.size < 2) {
            return Result(null, 0.0, lecturas.size, null, null, null,
                          lecturas.firstOrNull()?.atEpochMillis, lecturas.lastOrNull()?.atEpochMillis)
        }
        val sig = sigma(lecturas, base)
        val pico = lecturas.maxByOrNull { it.microSiemensPerCm }
        val conExceso = lecturas.filter { it.microSiemensPerCm > base }
        val paso = if (conExceso.size >= 2)
            (conExceso.last().atEpochMillis - conExceso.first().atEpochMillis) / 1000.0 else null
        val q = if (s.saltMassG != null && s.calibrationFactor != null)
            dischargeM3s(s.saltMassG, s.calibrationFactor, sig) else null
        return Result(q, sig, lecturas.size,
                      pico?.let { it.microSiemensPerCm - base }, pico?.atEpochMillis, paso,
                      lecturas.first().atEpochMillis, lecturas.last().atEpochMillis)
    }

    /**
     * Inserta una lectura en su sitio por hora.
     *
     * Una lectura con la misma hora que otra la REEMPLAZA en vez de duplicarla: dos valores en
     * el mismo instante harian un intervalo de cero segundos, y la integral no sabria cual de
     * los dos vale.
     */
    fun addReading(l: List<ConductivityReading>, r: ConductivityReading): List<ConductivityReading> =
        (l.filter { it.atEpochMillis != r.atEpochMillis } + r).sortedBy { it.atEpochMillis }
}
