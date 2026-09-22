package cl.umag.glaciertemp.core.fieldbook

/**
 * La libreta de terreno: lo que se anota estando en el glaciar.
 *
 * Todo vive en `:core` --Java puro, sin dependencias-- por la misma razon que los puntos de
 * GPS: el manejo de ficheros y las cuentas se prueban enteros en el escritorio, sin telefono
 * ni emulador. Lo unico que se queda en `:app` es lo que toca camara, microfono y alarmas.
 *
 * UNA DECISION QUE GOBIERNA EL RESTO: los nombres de personas y receptores se guardan en
 * cada entrada como TEXTO, no como referencia a la lista. Eso es lo que hace que borrar un
 * receptor de la lista de receptores disponibles no toque ni una de las mediciones ya
 * hechas con el. Una referencia obligaria a decidir que hacer con los huerfanos, y la
 * respuesta correcta --no perder el dato de terreno-- es justamente la que sale sola si no
 * hay referencia que romper.
 */

/** Que clase de entrada es. El nombre va tal cual al fichero, asi que no se renombra. */
enum class EntryType { NOTE, STAKE, GNSS, DENDRO }

/** De donde salio una coordenada. Cambia lo que significa su antiguedad. */
enum class PositionSource {
    /** Del receptor del propio telefono, en el momento de pedirla. */
    PHONE,

    /**
     * De un punto promediado en GPS tools.
     *
     * Su "antiguedad" no es un defecto: describe un sitio, que no se mueve, y ademas es mas
     * exacta que cualquier lectura suelta del telefono. Por eso se distingue del caso
     * anterior en vez de guardar las dos como una coordenada a secas.
     */
    SAVED_POINT,
}

/**
 * Una coordenada anotada en la libreta.
 *
 * Es un tipo propio y no [cl.umag.glaciertemp.core.GeoFix] porque responden a preguntas
 * distintas: GeoFix describe donde esta el telefono AHORA, con su antiguedad relativa al
 * momento de preguntarla, y esto es una posicion fechada que va a vivir en un fichero
 * durante anos, con constancia de COMO se obtuvo.
 */
data class FieldPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double? = null,
    val accuracyMetres: Double? = null,
    val source: PositionSource = PositionSource.PHONE,
    /** Nombre del punto guardado, cuando viene de uno. Se copia para que sobreviva a su borrado. */
    val pointName: String? = null,
    val pointId: String? = null,
    /** Cuando se tomo. En un punto guardado, el instante de su ultima muestra. */
    val atEpochMillis: Long = 0L,
) {
    /** Las coordenadas en una linea, que es como se leen en pantalla y en un export. */
    fun describe(): String = "%.5f, %.5f".format(java.util.Locale.ROOT, latitude, longitude)

    /** Lo que permite juzgarla: de donde salio, con que exactitud y a que altura. */
    fun detail(): String {
        val partes = ArrayList<String>()
        partes += when (source) {
            PositionSource.SAVED_POINT -> "saved point" + (pointName?.let { " “$it”" } ?: "")
            PositionSource.PHONE -> "phone GPS"
        }
        accuracyMetres?.let { partes += "accuracy %.0f m".format(java.util.Locale.ROOT, it) }
        altitudeMetres?.let { partes += "%.0f m (WGS84)".format(java.util.Locale.ROOT, it) }
        return partes.joinToString("  ·  ")
    }
}

/** Un fichero adjunto: vive en la carpeta de medios y la entrada lo nombra. */
data class Attachment(
    /** Nombre del fichero dentro de la carpeta de medios, sin ruta. */
    val file: String,
    val atEpochMillis: Long = 0L,
    /** Duracion, solo en audio. */
    val durationMillis: Long? = null,
)

/**
 * Una medicion GNSS de alta precision: el receptor y los dos instantes.
 *
 * La duracion NO se guarda, se calcula. Guardarla ademas de los extremos crea dos fuentes
 * para el mismo dato, y el dia que alguien corrija una marca a mano la duracion guardada se
 * queda diciendo lo de antes -- que es justo el caso que la especificacion pide que
 * funcione.
 */
data class GnssSession(
    val receiver: String = "",
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    /** Duracion programada en minutos, cuando se eligio una. */
    val plannedMinutes: Int? = null,
    /**
     * Altura de la antena sobre la marca, en centimetros.
     *
     * Sin ella la posicion vertical no significa nada: el receptor mide donde esta SU centro
     * de fase, no donde esta el punto. Es el dato que mas veces se olvida en terreno y el
     * unico que no se puede reconstruir despues, y por eso la app lo recuerda al terminar.
     */
    val antennaHeightCm: Double? = null,
) {
    val durationMillis: Long?
        get() {
            val a = startEpochMillis ?: return null
            val b = endEpochMillis ?: return null
            return (b - a).takeIf { it >= 0 }
        }

    /** Si la medicion esta en marcha: empezada y sin terminar. */
    val running: Boolean get() = startEpochMillis != null && endEpochMillis == null

    /** Instante en que vence la duracion programada, o null si no hay ninguna. */
    val plannedEndEpochMillis: Long?
        get() {
            val a = startEpochMillis ?: return null
            val m = plannedMinutes ?: return null
            return a + m * 60_000L
        }

    val isEmpty: Boolean
        get() = receiver.isBlank() && startEpochMillis == null && endEpochMillis == null &&
                antennaHeightCm == null

    /** Lo que falta para que la medicion sirva, en palabras. Vacio si no falta nada. */
    fun missingForUse(hasPosition: Boolean): List<String> = buildList {
        if (antennaHeightCm == null) add("the antenna height")
        if (!hasPosition) add("an approximate position")
    }
}

/** Lo que puede llevar dentro una nota general. */
enum class NoteItemKind { TEXT, PHOTO, AUDIO }

/**
 * Un trozo de nota general, con su propia marca de tiempo.
 *
 * La nota no es un campo de texto con adjuntos colgando: es una SUCESION de anotaciones
 * fechadas. Es lo que pide la especificacion --cada entrada que se agrega puede llevar su
 * marca-- y ademas es lo que se hace de verdad en terreno: se escribe algo, se sigue
 * andando, y media hora despues se anade otra cosa a la misma observacion. Con un solo
 * campo de texto, esas dos anotaciones quedan con la misma hora, que es falso.
 */
data class NoteItem(
    val kind: NoteItemKind,
    val atEpochMillis: Long,
    val text: String = "",
    val file: String? = null,
    val durationMillis: Long? = null,
)

/** Una medicion de altura de una baliza, con su posible medicion GNSS asociada. */
data class StakeMeasurement(
    val atEpochMillis: Long,
    val person: String = "",
    /** Altura de baliza expuesta sobre la superficie, en centimetros. */
    val exposedHeightCm: Double? = null,
    val photos: List<String> = emptyList(),
    val gnss: GnssSession? = null,
)

/**
 * Una entrada de la libreta.
 *
 * Un solo tipo con los campos de los cuatro en vez de una jerarquia: la lista, el
 * almacenamiento y las columnas comunes --quien, cuando, donde-- son los mismos para todos,
 * y una jerarquia obligaria a repetir esa parte cuatro veces o a inventar una clase base que
 * no es mas que esto. Los campos que no le tocan a un tipo quedan nulos o vacios y no se
 * escriben al fichero.
 */
data class FieldEntry(
    val id: String,
    val type: EntryType,
    val createdEpochMillis: Long,
    val updatedEpochMillis: Long = createdEpochMillis,
    /** Quien observo, midio o tomo la muestra. */
    val person: String = "",
    val position: FieldPosition? = null,
    /**
     * La campana de terreno a la que pertenece, o null si se anoto fuera de ninguna.
     *
     * Se guarda el ID y no el nombre, al reves que las personas y los receptores. La
     * diferencia importa: un nombre de persona es el DATO --quien midio-- mientras que la
     * campana es una agrupacion que se renombra ("Bernal 2026" -> "Bernal feb 2026") sin que
     * eso cambie nada de lo medido. Guardando el nombre, renombrar obligaria a reescribir
     * todas las entradas y a que una quedara atras.
     */
    val campaignId: String? = null,

    // --- NOTE ---
    /** Titulo escrito a mano. Vacio significa "usa las primeras palabras del texto". */
    val title: String = "",
    val items: List<NoteItem> = emptyList(),

    // --- STAKE ---
    val stakeName: String = "",
    /** Longitud total de la baliza, en centimetros. */
    val stakeLengthCm: Double? = null,
    val measurements: List<StakeMeasurement> = emptyList(),

    // --- GNSS ---
    val pointName: String = "",
    val gnss: GnssSession? = null,

    // --- DENDRO ---
    val sampleLabel: String = "",
    val species: String = "",
    /** Altura sobre el suelo a la que se extrajo la muestra, en centimetros. */
    val samplingHeightCm: Double? = null,
    /** Perimetro del tronco a la altura de muestreo, en centimetros. */
    val trunkPerimeterCm: Double? = null,
    val notes: String = "",

    /** Fotografias de la entrada. En STAKE cuelgan de cada medicion, no de aqui. */
    val photos: List<String> = emptyList(),
) {
    /**
     * Como se llama la entrada en la lista.
     *
     * Cada tipo tiene su propio identificador natural --la etiqueta fisica de la muestra, el
     * nombre de la baliza-- y usar ese y no un titulo aparte evita que el nombre que se ve
     * en la lista y el que esta escrito en la cinta de la muestra puedan discrepar.
     */
    fun title(): String = when (type) {
        // El titulo escrito gana; si no hay, las primeras palabras del texto. Un titulo
        // obligatorio seria un campo mas que rellenar antes de poder anotar nada, y en
        // terreno eso es lo que hace que la observacion acabe en papel.
        EntryType.NOTE -> title.trim().ifBlank {
            items.firstOrNull { it.kind == NoteItemKind.TEXT }
                ?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                ?: "(untitled note)"
        }
        EntryType.STAKE -> stakeName.ifBlank { "(unnamed stake)" }
        EntryType.GNSS -> pointName.ifBlank { "(unnamed point)" }
        EntryType.DENDRO -> sampleLabel.ifBlank { "(unlabelled sample)" }
    }

    /** Todos los ficheros de medios a los que apunta la entrada, del tipo que sea. */
    fun mediaFiles(): List<String> =
        items.mapNotNull { it.file } + photos + measurements.flatMap { it.photos }

    /** La medicion GNSS en marcha que haya en la entrada, del tipo que sea. */
    fun runningGnss(): GnssSession? =
        gnss?.takeIf { it.running }
            ?: measurements.firstNotNullOfOrNull { it.gnss?.takeIf { g -> g.running } }
}

/**
 * Tasa de ablacion entre dos mediciones consecutivas de una baliza, en cm/dia.
 *
 *     tasa = (altura expuesta actual - altura expuesta anterior) / dias transcurridos
 *
 * POSITIVA significa ablacion: la superficie baja y la baliza queda mas al aire. Negativa
 * significa acumulacion. El signo sale de la formula tal cual y no se toca, porque invertirlo
 * para que "ablacion" y "positivo" coincidan siempre obligaria a explicar el signo en cada
 * sitio donde aparece.
 *
 * Es cambio de superficie en centimetros de hielo o nieve, NO equivalente en agua: para eso
 * haria falta la densidad, que no se mide aqui. La pantalla lo dice.
 */
object Ablation {

    const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * La tasa entre dos mediciones, o null si no se puede calcular.
     *
     * Devuelve null --y no cero-- cuando falta una altura o cuando las dos mediciones caen en
     * el mismo instante. Un cero ahi seria una tasa medida de cero, que es una afirmacion
     * sobre el glaciar; lo que hay es ausencia de dato.
     */
    fun ratePerDay(previous: StakeMeasurement, current: StakeMeasurement): Double? {
        val h0 = previous.exposedHeightCm ?: return null
        val h1 = current.exposedHeightCm ?: return null
        val days = (current.atEpochMillis - previous.atEpochMillis) / MILLIS_PER_DAY
        if (days <= 0.0) return null
        return (h1 - h0) / days
    }

    /**
     * Las tasas de una serie completa, alineadas con las mediciones: la primera es siempre
     * null porque no tiene anterior.
     *
     * Ordena por fecha antes de calcular. Una medicion anadida despues pero fechada antes
     * --que es lo que pasa al corregir una hora a mano-- tiene que entrar en su sitio, o la
     * tasa saldria negativa por el orden de insercion y no por lo que hizo el glaciar.
     */
    fun rates(measurements: List<StakeMeasurement>): List<Double?> {
        val orden = measurements.sortedBy { it.atEpochMillis }
        return orden.mapIndexed { i, m ->
            if (i == 0) null else ratePerDay(orden[i - 1], m)
        }
    }

    /** Las mediciones en el orden en que se muestran: cronologico. */
    fun chronological(measurements: List<StakeMeasurement>): List<StakeMeasurement> =
        measurements.sortedBy { it.atEpochMillis }
}
