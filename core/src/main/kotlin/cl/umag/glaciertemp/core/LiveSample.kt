package cl.umag.glaciertemp.core

/**
 * Una lectura en directo: lo que la placa mide AHORA, sin grabarlo.
 *
 * El formato no es nuevo. La placa emite la misma fila compacta que el volcado LOGC,
 * precedida de "LIVE", de modo que las columnas son las que anuncia la firma del log --que
 * la app ya conoce por INFO-- en el mismo orden y con las mismas unidades. Inventar un
 * formato aparte habria significado dos sitios donde decir que canales hay, y el dia que uno
 * de los dos se quede atras la app ensena la humedad bajo la etiqueta de temperatura.
 *
 * Los valores llegan YA formateados por la placa y aqui no se reinterpretan. Son texto para
 * ensenar: convertirlos a numero para volver a formatearlos solo anadiria una forma de
 * equivocarse --el redondeo, la coma decimal, el NaN-- sin ganar nada.
 */
data class LiveSample(val time: String, val values: List<String>) {

    companion object {
        const val PREFIX = "LIVE"

        /** La placa empieza anunciando la cadencia con la que se quedo. */
        fun isStart(line: String): Boolean = line.trimStart().startsWith("$PREFIX begin")

        /**
         * Fin del modo. Son DOS por un motivo: "end" es que paro porque se lo pedimos y
         * "timeout" que se le acabo el plazo. Quien mira la pantalla necesita saber cual de
         * los dos fue, porque el segundo significa que hay que volver a pedirlo.
         */
        fun isEnd(line: String): Boolean {
            val t = line.trimStart()
            return t.startsWith("$PREFIX end") || t.startsWith("$PREFIX timeout")
        }

        fun isTimeout(line: String): Boolean = line.trimStart().startsWith("$PREFIX timeout")

        /**
         * Una muestra, o null si la linea no lo es.
         *
         * [expectedValues] no es opcional y no es una comodidad: por radio una linea puede
         * llegar cortada, y una muestra a la que le falta el ultimo campo tiene todos los
         * demas bien. Ensenarla desplazaria las etiquetas un puesto sin que nada lo delate.
         * Se descarta entera, que es lo mismo que hace el volcado binario cuando el CRC de
         * un bloque no cuadra.
         */
        fun parse(line: String, expectedValues: Int): LiveSample? {
            val t = line.trim()
            if (!t.startsWith(PREFIX)) return null
            val resto = t.removePrefix(PREFIX).trim()
            // Las lineas de control comparten el prefijo y no son muestras.
            if (isStart(t) || isEnd(t)) return null
            val partes = resto.split(",")
            if (partes.size != expectedValues + 1) return null
            val hora = partes[0].trim()
            if (hora.isEmpty()) return null
            return LiveSample(hora, partes.drop(1).map { it.trim() })
        }
    }
}
