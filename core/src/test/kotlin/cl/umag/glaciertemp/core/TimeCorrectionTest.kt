package cl.umag.glaciertemp.core

import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.*

/**
 * La correccion lineal de marcas de tiempo y la cabecera de metadatos del CSV.
 *
 * Lo que se fija aqui son los DOS EXTREMOS, que es donde la definicion se puede torcer sin
 * que nadie lo note: la primera muestra no se corrige en absoluto, y en el instante de la
 * descarga la correccion es el desfase completo. Todo lo de en medio se sigue de esos dos.
 */
class TimeCorrectionTest {

    private val t0 = LocalDateTime.of(2026, 1, 1, 0, 0, 0)

    /** Volt + Temp + RH + HAtemp, la configuracion habitual. */
    private val SIG = 0x100F
    private val canales = LogFormat.fields(0x100F).size

    // Tantos valores como canales declara la firma: un registro con menos columnas de las
    // que anuncia la cabecera se descarta al reimportar, y el fallo aparece como "no se
    // pudo leer ninguna fila" en vez de como lo que es.
    private fun records(n: Int, stepSeconds: Long = 600): List<Record> =
        (0 until n).map { i ->
            Record(t0.plusSeconds(i * stepSeconds), List(canales) { 3.7 + it })
        }

    private fun meta(
        records: List<Record>,
        offsetSeconds: Long,
        downloadedAt: LocalDateTime = records.last().time,
    ) = DownloadMetadata(
        downloadedAt = downloadedAt,
        boardId = "GT001-556677",
        // La placa va adelantada [offsetSeconds] respecto a la referencia.
        boardTime = downloadedAt.plusSeconds(offsetSeconds),
        referenceTime = downloadedAt,
        reference = ClockReference.GPS,
    )

    @Test
    fun `la primera muestra no se corrige y la ultima se corrige entera`() {
        val rs = records(100)
        val m = meta(rs, 120)
        val fixed = assertNotNull(TimeCorrection.corrected(rs, m))

        assertEquals(rs.first().time, fixed.first(),
                     "la primera muestra no puede moverse: la correccion alli vale cero")
        assertEquals(rs.last().time.minusSeconds(120), fixed.last(),
                     "en el instante de la descarga hay que haber quitado el desfase entero")
    }

    @Test
    fun `la correccion crece de forma lineal con el tiempo transcurrido`() {
        val rs = records(101)                  // el indice 50 esta justo a la mitad
        val fixed = assertNotNull(TimeCorrection.corrected(rs, meta(rs, 200)))
        val medio = Duration.between(fixed[50], rs[50].time).seconds
        assertEquals(100L, medio, "a mitad de despliegue toca la mitad del desfase")

        val cuarto = Duration.between(fixed[25], rs[25].time).seconds
        assertEquals(50L, cuarto)
    }

    @Test
    fun `un reloj atrasado mueve las marcas hacia adelante`() {
        val rs = records(10)
        val fixed = assertNotNull(TimeCorrection.corrected(rs, meta(rs, -60)))
        assertTrue(fixed.last() > rs.last().time,
                   "si la placa iba atrasada, la hora real era posterior a la registrada")
        assertEquals(rs.last().time.plusSeconds(60), fixed.last())
    }

    // --- los tres casos en que no hay nada que corregir ---------------------

    @Test
    fun `un solo registro no define una deriva`() {
        val rs = records(1)
        assertNull(TimeCorrection.corrected(rs, meta(rs, 120)))
    }

    @Test
    fun `un desfase de cero no produce columna`() {
        val rs = records(10)
        assertNull(TimeCorrection.corrected(rs, meta(rs, 0)),
                   "una columna identica a la original afirmaria que se ha corregido algo")
    }

    @Test
    fun `una descarga anterior al primer registro es dato incoherente`() {
        val rs = records(10)
        val m = meta(rs, 120, downloadedAt = t0.minusHours(1))
        assertNull(TimeCorrection.corrected(rs, m))
    }

    @Test
    fun `sin metadatos no hay correccion`() {
        assertNull(TimeCorrection.corrected(records(10), null))
    }

    // --- CSV ---------------------------------------------------------------

    @Test
    fun `la columna corregida va inmediatamente despues de Time`() {
        val rs = records(10)
        val csv = CsvExporter.export(rs, SIG, meta(rs, 120), corrected = true)
        val header = csv.lines().first { !it.startsWith("#") }
        assertTrue(header.startsWith("Time,${TimeCorrection.COLUMN},"),
                   "cabecera inesperada: $header")
    }

    @Test
    fun `pedir la columna sin desfase medido no la escribe`() {
        val rs = records(10)
        val csv = CsvExporter.export(rs, SIG, meta(rs, 0), corrected = true)
        val header = csv.lines().first { !it.startsWith("#") }
        assertFalse(header.contains(TimeCorrection.COLUMN))
    }

    @Test
    fun `la cabecera de metadatos va en lineas de comentario`() {
        val rs = records(10)
        val m = meta(rs, 37).copy(
            boardFullId = "0011223344556677",
            position = GeoFix(-0.14231, -78.83412, accuracyMetres = 8.0, ageSeconds = 41),
        )
        val csv = CsvExporter.export(rs, SIG, m, corrected = false)
        val comentarios = csv.lines().takeWhile { it.startsWith("#") }

        assertTrue(comentarios.any { it.contains("board: GT001-556677") })
        assertTrue(comentarios.any { it.contains("0011223344556677") })
        assertTrue(comentarios.any { it.contains("-0.14231") && it.contains("-78.83412") })
        assertTrue(comentarios.any { it.contains("accuracy 8 m") })
        assertTrue(comentarios.any { it.contains("41 seconds old") })
        assertTrue(comentarios.any { it.contains("clock reference: GPS") })
        assertTrue(comentarios.any { it.contains("board ahead") },
                   "el sentido del desfase tiene que ir en palabras")
    }

    @Test
    fun `una nota de varias lineas lleva su propio comentario en cada una`() {
        val rs = records(5)
        val m = meta(rs, 10).copy(note = "Fin de campana.\nSensor 3 movido 40 cm.")
        val csv = CsvExporter.export(rs, SIG, m, corrected = false)
        val comentarios = csv.lines().takeWhile { it.startsWith("#") }

        assertTrue(comentarios.any { it.contains("note: Fin de campana.") })
        assertTrue(comentarios.any { it.contains("Sensor 3 movido 40 cm.") })
        // Ninguna linea de la nota puede quedar fuera del comentario: si la segunda saliera
        // desnuda, el fichero dejaria de ser un CSV valido justo a partir de ahi.
        val sueltas = csv.lines().filter { it.contains("Sensor 3 movido") && !it.startsWith("#") }
        assertTrue(sueltas.isEmpty(), "una linea de la nota salio fuera del comentario")
    }

    @Test
    fun `una nota vacia no escribe la linea`() {
        val rs = records(5)
        val csv = CsvExporter.export(rs, SIG, meta(rs, 10).copy(note = "   "), false)
        assertFalse(csv.contains("note:"))
    }

    @Test
    fun `el CSV con metadatos y columna corregida se vuelve a abrir igual`() {
        // El viaje de ida y vuelta es lo que garantiza que anadir la cabecera no ha roto la
        // funcion de abrir un fichero, que existe desde antes y que nadie relacionaria con
        // este cambio.
        val rs = records(20)
        val m = meta(rs, 90).copy(
            note = "Prueba",
            position = GeoFix(-53.1, -70.9, 12.0, 5),
        )
        val csv = CsvExporter.export(rs, SIG, m, corrected = true)
        assertTrue(csv.startsWith("#"))
        assertTrue(csv.contains(TimeCorrection.COLUMN))

        val vuelto = CsvImporter.parse(csv)
        assertEquals(SIG, vuelto.signature)
        assertEquals(rs.size, vuelto.records.size)
        assertEquals(0, vuelto.skipped)
        assertEquals(rs.map { it.time }, vuelto.records.map { it.time },
                     "al reabrir tiene que quedar la marca ORIGINAL, no la corregida")
        assertEquals(rs.map { it.values }, vuelto.records.map { it.values })
    }

    @Test
    fun `un CSV sin metadatos se sigue exportando como antes`() {
        val rs = records(5)
        assertEquals(CsvExporter.export(rs, SIG),
                     CsvExporter.export(rs, SIG, null, corrected = true),
                     "sin metadatos no hay nada que anadir y el fichero no puede cambiar")
    }
}
