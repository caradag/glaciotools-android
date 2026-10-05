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
    /**
     * Donde se estaba.
     *
     * SE PIDE SOLA AL CREAR LA ENTRADA, como en las notas. Un diario dice lo que paso; que
     * ademas diga donde convierte una frase como "aqui el hielo estaba limpio" en un dato
     * que se puede volver a encontrar. Y donde se escribio no se reconstruye despues.
     */
    val position: FieldPosition? = null,
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
    /**
     * Un hueco: un dia ENTRE dos dias escritos del que no hay nada. No existe en disco --es
     * un aviso de la lista, no un registro-- y por eso nunca llega a una exportacion.
     */
    val missing: Boolean = false,
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
     * La lista de dias con los HUECOS rellenos: cada dia que falta entre el mas antiguo y el
     * mas reciente aparece vacio y marcado como [JournalDay.missing].
     *
     * Se calcula al mostrar y no se guarda. Guardado seria un dia "escrito" sin que nadie lo
     * escribiera, y llegaria al documento exportado como si fuera un registro de terreno;
     * calculado, desaparece solo en cuanto se anota algo en el o se le pone titulo. Y cada
     * vez que se anade un dia nuevo --casi siempre el de hoy-- los que se saltaron hasta el
     * aparecen solos, que es justo lo que se quiere ver.
     *
     * @param days los dias tal como los devuelve [group]: del mas reciente al mas antiguo.
     */
    fun withGaps(days: List<JournalDay>): List<JournalDay> {
        if (days.size < 2) return days
        val porClave = days.associateBy { it.key }
        val desde = java.time.LocalDate.parse(days.last().key)
        val hasta = java.time.LocalDate.parse(days.first().key)
        // Una campana de anos con dos dias escritos no deberia generar setecientos
        // avisos: mas de un ano de hueco se deja como esta.
        if (java.time.temporal.ChronoUnit.DAYS.between(desde, hasta) > 366) return days
        return generateSequence(hasta) { it.minusDays(1) }
            .takeWhile { !it.isBefore(desde) }
            .map { d ->
                val k = d.toString()
                porClave[k] ?: JournalDay(k, "", emptyList(), missing = true)
            }.toList()
    }

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
     *
     * UN DIA CON TITULO EXISTE AUNQUE NO TENGA ENTRADAS. "Temporal, no se salio" es un
     * registro valido de un dia de campana, y a veces es todo lo que hay que decir de el.
     */
    fun group(entries: List<JournalEntry>, titles: Map<String, String>,
              zone: TimeZone = TimeZone.getDefault()): List<JournalDay> {
        val porDia = entries.groupBy { dayKey(it.epochMillis, zone) }
        val soloTitulo = titles.filter { (k, t) -> t.isNotBlank() && k !in porDia }.keys
        return (porDia.keys + soloTitulo).sortedDescending().map { k ->
            JournalDay(k, titles[k].orEmpty(), porDia[k].orEmpty().sortedBy { it.epochMillis })
        }
    }
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
     * @param dayKeys los dias que YA tienen alguna entrada o un titulo
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
