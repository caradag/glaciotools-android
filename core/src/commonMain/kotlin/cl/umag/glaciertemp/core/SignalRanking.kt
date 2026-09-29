package cl.umag.glaciertemp.core

/**
 * El orden de la lista de modulos BLE: por potencia MEDIA, rehecho cada [windowMs].
 *
 * POR QUE NO SE ORDENA POR LA ULTIMA LECTURA. El RSSI de un anuncio BLE salta 10 dB de un
 * anuncio al siguiente sin que nada se mueva --reflexiones, la mano en el telefono, el
 * cuerpo de quien lo sostiene--, y cada modulo anuncia varias veces por segundo. Con una
 * docena alrededor, ordenar por el ultimo valor reordenaba la lista varias veces por
 * segundo: los botones cambiaban de sitio justo mientras se intentaba leer uno y pulsarlo.
 *
 * Aqui el orden solo cambia en [reorder], que se llama cada [windowMs], y se decide por la
 * media de lo recibido en esa ventana: un salto suelto no mueve nada, un modulo que de verdad
 * esta mas cerca sube en la siguiente vuelta. Un modulo NUEVO entra en el acto --al final,
 * para no desplazar a los que ya se estaban leyendo-- y se coloca en la siguiente vuelta.
 *
 * El tiempo se pasa desde fuera para poder probarlo sin esperar.
 */
class SignalRanking(private val windowMs: Long = 2_000L) {

    private val muestras = HashMap<String, ArrayDeque<Pair<Long, Int>>>()
    private val orden = ArrayList<String>()
    private val mostrado = HashMap<String, Int>()

    /**
     * Anota un anuncio. Devuelve true si el modulo es nuevo, que es cuando la lista tiene
     * que publicarse sin esperar a la siguiente vuelta.
     */
    fun sighting(key: String, rssi: Int, tMs: Long): Boolean {
        val cola = muestras.getOrPut(key) { ArrayDeque() }
        cola.addLast(tMs to rssi)
        podar(cola, tMs)
        if (key in mostrado) return false
        orden += key
        // Hasta la primera vuelta se ensena lo unico que se sabe: esta lectura.
        mostrado[key] = rssi
        return true
    }

    /**
     * Recalcula la media de cada modulo sobre la ultima ventana y reordena.
     *
     * Un modulo del que no llego nada en la ventana conserva su ultima media: dejar de
     * oirlo dos segundos no lo acerca ni lo aleja, y mandarlo al fondo lo haria saltar.
     * Los empates conservan el orden previo, por la misma razon.
     */
    fun reorder(tMs: Long) {
        for ((key, cola) in muestras) {
            podar(cola, tMs)
            if (cola.isNotEmpty()) mostrado[key] = mediaRedondeada(cola)
        }
        val previo = orden.withIndex().associate { (i, k) -> k to i }
        orden.sortWith(compareByDescending<String> { mostrado[it] ?: Int.MIN_VALUE }
            .thenBy { previo[it] ?: Int.MAX_VALUE })
    }

    /** Las claves en el orden en que se muestran. */
    fun order(): List<String> = orden.toList()

    /** La potencia que se muestra junto al modulo: la media de la ultima vuelta. */
    fun shownRssi(key: String): Int? = mostrado[key]

    fun clear() {
        muestras.clear(); orden.clear(); mostrado.clear()
    }

    private fun podar(cola: ArrayDeque<Pair<Long, Int>>, tMs: Long) {
        while (cola.isNotEmpty() && cola.first().first <= tMs - windowMs) cola.removeFirst()
    }

    // La media de dBm se hace sobre los dBm y no sobre la potencia lineal: lo que se quiere
    // es un orden estable, no una medida radiometrica, y en dBm un valor atipico pesa menos.
    private fun mediaRedondeada(cola: ArrayDeque<Pair<Long, Int>>): Int {
        val m = cola.sumOf { it.second.toLong() }.toDouble() / cola.size
        return kotlin.math.round(m).toInt()
    }
}
