package cl.umag.glaciertemp.core.fieldbook

import java.util.Calendar
import java.util.TimeZone

/** Una nota de audio dentro de una entrada del diario. */
data class JournalAudio(val file: String, val durationMillis: Long? = null)

/**
 * Una entrada del diario de campana.
 *
 * QUE LO DIFERENCIA DE UNA FieldEntry. Una anotacion de libreta documenta UNA COSA --una
 * baliza, un punto GNSS, una muestra-- y por eso tiene campos con nombre y se exporta a una
 * tabla. Una entrada de diario documenta UN RATO: lo que paso, lo que se vio, lo que salio
 * mal. No hay campos que rellenar porque no se sabe de antemano que habra que contar.
 *
 * De ahi que tenga su propio almacen y no un EntryType mas: meterla entre los tipos de
 * anotacion la habria colado en los filtros rapidos, en el dialogo de New Entry y en las
 * tablas del CSV, tres sitios donde no significa nada.
 */
data class JournalEntry(
    val id: String,
    val campaignId: String,
    /**
     * Cuando ocurrio lo que se cuenta, NO cuando se escribio.
     *
     * Se rellena con la hora de creacion porque casi siempre coinciden, pero es editable: en
     * terreno se escribe de noche, en la tienda, y lo que se cuenta paso a mediodia. Si la
     * marca fuera la de escritura, un dia entero de trabajo quedaria fechado a las 23:40.
     */
    val epochMillis: Long,
    val title: String = "",
    val text: String = "",
    val photos: List<String> = emptyList(),
    val audio: List<JournalAudio> = emptyList(),
) {
    fun mediaFiles(): List<String> = photos + audio.map { it.file }

    /** Vacia del todo: ni titulo, ni texto, ni medios. */
    fun isEmpty(): Boolean =
        title.isBlank() && text.isBlank() && photos.isEmpty() && audio.isEmpty()
}

/** Un dia del diario: su clave, su titulo y lo que se anoto ese dia. */
data class JournalDay(
    val key: String,
    val title: String,
    val entries: List<JournalEntry>,
)

/**
 * Como se reparten las entradas en dias, y cuando hay que recordar que falta uno.
 *
 * Reglas puras y sin Android para poder probarlas: el reparto por dias depende de la zona
 * horaria y de los cambios de hora, y esas son justo las cosas que fallan una vez al ano y
 * nadie reproduce a mano.
 */
object JournalDays {

    /**
     * La clave del dia, en hora LOCAL.
     *
     * En hora local y no UTC porque un dia de terreno es el que vivio quien lo escribe. En
     * Patagonia son tres horas al oeste: con claves UTC, todo lo anotado despues de las
     * nueve de la noche caeria en el dia siguiente, y el diario mostraria jornadas partidas
     * justo por la parte que mas se escribe, la de la cena.
     */
    fun dayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Medianoche local del dia al que pertenece ese instante. */
    fun dayStart(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** La clave del dia anterior a ese instante. */
    fun previousDayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        dayKey(dayStart(millis, zone) - 12 * 3_600_000L, zone)

    /**
     * Agrupa las entradas en dias.
     *
     * Los dias van del MAS RECIENTE al mas antiguo, porque lo que se hace a diario es anadir
     * lo de hoy y el recordatorio habla de ayer: obligar a recorrer una campana de seis
     * semanas para llegar a lo de esta tarde seria cobrarle el desplazamiento a la accion
     * mas frecuente. Dentro de cada dia, en cambio, las entradas van en orden ascendente,
     * que es como se leen los acontecimientos de una jornada.
     */
    fun group(entries: List<JournalEntry>, titles: Map<String, String>,
              zone: TimeZone = TimeZone.getDefault()): List<JournalDay> =
        entries.groupBy { dayKey(it.epochMillis, zone) }
            .toSortedMap(compareByDescending { it })
            .map { (k, l) -> JournalDay(k, titles[k].orEmpty(), l.sortedBy { it.epochMillis }) }
}

/**
 * El recordatorio de que falta el diario de ayer.
 *
 * NO ES UNA OBLIGACION. Un dia de campana puede no tener nada que contar --se espero a que
 * dejara de llover, se viajo-- y el aviso no dice que falte algo, dice que quiza se olvido.
 * De ahi que se pueda descartar, y que descartarlo valga solo para ESE dia: el olvido del
 * miercoles no debe silenciar el del jueves.
 */
object JournalReminder {

    /**
     * La fecha que falta por completar, o null si no hay nada que recordar.
     *
     * @param dayKeys los dias que YA tienen alguna entrada
     * @param dismissedKey el dia cuyo aviso se descarto, si alguno
     */
    fun missingDay(dayKeys: Set<String>, nowMillis: Long, dismissedKey: String?,
                   zone: TimeZone = TimeZone.getDefault()): String? {
        val ayer = JournalDays.previousDayKey(nowMillis, zone)
        if (ayer in dayKeys) return null
        if (ayer == dismissedKey) return null
        return ayer
    }
}
