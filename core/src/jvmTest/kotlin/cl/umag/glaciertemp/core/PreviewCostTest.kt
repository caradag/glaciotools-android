package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.test.*

/**
 * La vista previa tiene que costar lo que se VE, no lo que pesa el log.
 *
 * Este fichero existe por un fallo concreto: la vista previa se generaba exportando el log
 * entero y quedandose con las primeras lineas. Con cien mil registros eran casi seis megas
 * de texto en CADA pulsacion del campo de la nota, y el teclado tardaba segundos por letra.
 *
 * No se mide tiempo --seria una prueba inestable-- sino ACCESOS: la lista lleva la cuenta de
 * cuantos elementos se le han pedido. Es la misma propiedad y no depende de la maquina.
 */
class PreviewCostTest {

    /** Una lista que cuenta cuantos elementos se le piden de verdad. */
    private class CountingList(private val backing: List<Record>) : List<Record> by backing {
        var reads = 0
            private set
        override fun get(index: Int): Record { reads++; return backing[index] }
        override fun iterator(): Iterator<Record> {
            val it = backing.iterator()
            return object : Iterator<Record> {
                override fun hasNext() = it.hasNext()
                override fun next(): Record { reads++; return it.next() }
            }
        }
    }

    private val sig = 0x100F
    private val canales = LogFormat.fields(0x100F).size
    private val t0 = LocalDateTime.of(2026, 1, 1, 0, 0, 0)

    private fun records(n: Int) = (0 until n).map { i ->
        Record(t0.plusSeconds(i * 5L), List(canales) { 3.7 + it })
    }

    private fun meta(rs: List<Record>) = DownloadMetadata(
        downloadedAt = rs.last().time,
        boardId = "GT001-1B4237",
        boardTime = rs.last().time.plusSeconds(90),
        referenceTime = rs.last().time,
        reference = ClockReference.PHONE,
    )

    @Test
    fun `la vista previa no recorre el log entero`() {
        val base = records(100_000)
        val lista = CountingList(base)
        CsvExporter.preview(lista, sig, meta(base), corrected = false, maxRows = 12)
        // Doce filas, mas el primero y el ultimo que necesitan los metadatos. Un margen
        // amplio: lo que se vigila es el ORDEN DE MAGNITUD, no el numero exacto.
        assertTrue(lista.reads < 100, "la vista previa leyo ${lista.reads} registros de 100.000")
    }

    @Test
    fun `tampoco con la columna corregida activada`() {
        val base = records(100_000)
        val lista = CountingList(base)
        CsvExporter.preview(lista, sig, meta(base), corrected = true, maxRows = 12)
        assertTrue(lista.reads < 100,
                   "con la correccion activada leyo ${lista.reads} registros de 100.000")
    }

    @Test
    fun `la correccion como funcion da lo mismo que la lista completa`() {
        val rs = records(500)
        val m = meta(rs)
        val lista = assertNotNull(TimeCorrection.corrected(rs, m))
        val f = assertNotNull(TimeCorrection.corrector(rs, m))
        assertEquals(lista, rs.map(f),
                     "las dos formas de pedir la correccion tienen que coincidir")
    }

    @Test
    fun `la vista previa dice cuantas filas quedan fuera`() {
        val rs = records(1000)
        val texto = CsvExporter.preview(rs, sig, null, false, maxRows = 12)
        assertTrue(texto.contains("... 988 more rows"), texto.takeLast(80))
        // Y coincide fila a fila con lo que produciria la exportacion completa.
        val completo = CsvExporter.export(rs, sig).lines()
        val previa = texto.lines()
        assertEquals(completo[0], previa[0])
        for (i in 1..12) assertEquals(completo[i], previa[i], "fila $i")
    }
}
