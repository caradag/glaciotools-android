package cl.umag.glaciertemp.core

import java.time.LocalDateTime
import kotlin.test.*

class CsvImporterTest {

    private fun sample(signature: Int, n: Int = 5): List<Record> {
        val fields = LogFormat.fields(signature)
        return (0 until n).map { i ->
            Record(LocalDateTime.of(2026, 1, 1, 0, 0, 0).plusMinutes(10L * i),
                fields.indices.map { j -> (i + 1) * 1.5 + j })
        }
    }

    @Test
    fun `exportar e importar devuelve exactamente lo mismo`() {
        // La prueba que de verdad importa: el importador es la inversa del exportador. Si
        // alguna vez dejan de serlo, un CSV guardado hoy no se podra abrir manana.
        for (sig in listOf(0x100F, 0x101F, 0x141F, 0x1E1F, 0x11FF)) {
            val original = sample(sig)
            val loaded = CsvImporter.parse(CsvExporter.export(original, sig))
            assertEquals(sig, loaded.signature, "signature distinto para 0x%04X".format(sig))
            assertEquals(CsvExporter.export(original, sig),
                CsvExporter.export(loaded.records, loaded.signature))
        }
    }

    @Test
    fun `reconstruye el signature de la cabecera`() {
        assertEquals(0x100F, CsvImporter.signatureFromHeader(
            listOf("Volt", "Temp", "RH", "HAtemp")))
        assertEquals(0x101F, CsvImporter.signatureFromHeader(
            listOf("Volt", "Temp", "RH", "HAtemp", "DS0")))
        assertEquals(0x141F, CsvImporter.signatureFromHeader(
            listOf("Volt", "Temp", "RH", "HAtemp", "DS0", "DS1", "DS2")))
    }

    @Test
    fun `NaN se lee como lectura fallida`() {
        val log = CsvImporter.parse(
            "Time,Volt,Temp,RH,HAtemp\n" +
            "2026-01-01 00:00:00,1.50,NaN,55.0,3.20\n")
        assertNull(log.records[0].values[1])
        assertEquals(1.5, log.records[0].values[0])
    }

    @Test
    fun `una fila corrupta se salta sin perder el resto`() {
        val log = CsvImporter.parse(
            "Time,Volt,Temp,RH,HAtemp\n" +
            "2026-01-01 00:00:00,1.50,2.0,55.0,3.20\n" +
            "esto no es una fila\n" +
            "2026-01-01 00:10:00,1.49,2.1,55.1,3.21\n")
        assertEquals(2, log.records.size)
        assertEquals(1, log.skipped)
    }

    @Test
    fun `rechaza un orden de columnas que el firmware no produce`() {
        // Con las columnas cambiadas de sitio el grafico saldria plausible y equivocado,
        // que es peor que un error.
        val e = assertFailsWith<CsvFormatException> {
            CsvImporter.parse("Time,Temp,Volt,RH,HAtemp\n2026-01-01 00:00:00,1,2,3,4\n")
        }
        assertTrue(e.message!!.contains("Column order"), e.message!!)
    }

    @Test
    fun `dice que columna no reconoce`() {
        val e = assertFailsWith<CsvFormatException> {
            CsvImporter.parse("Time,Volt,Presion\n2026-01-01 00:00:00,1,2\n")
        }
        assertTrue(e.message!!.contains("Presion"), e.message!!)
    }

    @Test
    fun `el cero negativo se normaliza`() {
        // "-0.00" se leia como -0.0, y BigDecimal --que usa el exportador-- no tiene cero
        // negativo, asi que al reexportar salia "0.00". Como -0.0 != 0.0, el mismo dato
        // dejaba de compararse igual consigo mismo tras una ida y vuelta.
        val log = CsvImporter.parse(
            "Time,Volt,Temp,RH,HAtemp\n2026-01-01 00:00:00,1.50,-0.00,55.0,3.20\n")
        assertEquals(0.0, log.records[0].values[1])
        val again = CsvImporter.parse(CsvExporter.export(log.records, log.signature))
        assertEquals(log.records, again.records)
    }

    @Test
    fun `rechaza un fichero que no es de GlacioTools`() {
        assertFailsWith<CsvFormatException> { CsvImporter.parse("fecha;valor\n1;2\n") }
        assertFailsWith<CsvFormatException> { CsvImporter.parse("") }
    }

    @Test
    fun `tolera saltos de linea de Windows y una coma al final`() {
        val log = CsvImporter.parse(
            "Time,Volt,Temp,RH,HAtemp,\r\n2026-01-01 00:00:00,1.50,2.0,55.0,3.20,\r\n")
        assertEquals(1, log.records.size)
        assertEquals(0x100F, log.signature)
    }

    @Test
    fun `un fichero sin ninguna fila legible es un error, no un log vacio`() {
        assertFailsWith<CsvFormatException> {
            CsvImporter.parse("Time,Volt,Temp,RH,HAtemp\nbasura\n")
        }
    }
}
