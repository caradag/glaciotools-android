package cl.umag.glaciertemp.core.fieldbook

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ficheros de logger como los que exportan los programas de cada fabricante.
 *
 * Las horas esperadas se escriben aparte con java.time a partir del texto de la fila, no con
 * el lector: si el lector se equivocara de orden dia/mes, el caso tiene que fallar.
 */
class ConductivityImportTest {

    private val PA: ZoneId = ZoneId.of("America/Punta_Arenas")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, z: ZoneId) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(z).toInstant().toEpochMilli()

    @Test fun `HOBO U24 con cabeceras entrecomilladas y huso en la columna`() {
        val f = """
            "Plot Title: 21123456"
            "#","Date Time, GMT-04:00","Low Range, μS/cm (LGR S/N: 21123456, SEN S/N: 21123456)","High Range, μS/cm (LGR S/N: 21123456, SEN S/N: 21123456)","Temp, °C (LGR S/N: 21123456, SEN S/N: 21123456)"
            1,09/29/26 09:10:00 AM,52.0,60.0,4.12
            2,09/29/26 09:10:05 AM,95.5,101.0,4.12
            3,09/29/26 09:10:10 AM,71.25,79.0,4.13
        """.trimIndent()
        val r = ConductivityImport.parse(f, PA)
        assertNull(r.error, r.error)
        assertEquals(3, r.readings.size)
        assertTrue(r.column!!.header.startsWith("Low Range"), "el rango bajo por defecto")
        assertEquals(2, r.candidates.size, "temperatura NO es candidata")
        val z = ZoneOffset.ofHours(-4)
        assertEquals(ms(2026, 9, 29, 9, 10, 0, z), r.readings[0].atEpochMillis)
        assertEquals(ms(2026, 9, 29, 9, 10, 10, z), r.readings[2].atEpochMillis)
        assertEquals(listOf(52.0, 95.5, 71.25), r.readings.map { it.microSiemensPerCm })
        // Eligiendo la otra columna:
        val alta = ConductivityImport.parse(f, PA, column = r.candidates[1].index)
        assertEquals(listOf(60.0, 101.0, 79.0), alta.readings.map { it.microSiemensPerCm })
    }

    @Test fun `Solinst con preambulo, fecha y hora separadas y mS por cm`() {
        val f = """
            Serial_number:
            2187654
            Project ID:
            Rio
            Date,Time,ms,LEVEL,TEMPERATURE,CONDUCTIVITY (mS/cm)
            2026/10/14,14:02:00,0,1.234,3.9,0.0521
            2026/10/14,14:02:05,0,1.234,3.9,0.0874
        """.trimIndent()
        val r = ConductivityImport.parse(f, PA)
        assertNull(r.error, r.error)
        assertEquals(listOf(52.1, 87.4), r.readings.map { it.microSiemensPerCm }.map { Math.round(it * 10) / 10.0 })
        assertEquals(ms(2026, 10, 14, 14, 2, 5, PA), r.readings[1].atEpochMillis)
        assertTrue(r.warnings.any { "mS/cm" in it })
        assertTrue(r.warnings.any { "time zone" in it }, "sin huso en el fichero, se avisa")
    }

    @Test fun `hoja europea con punto y coma y coma decimal`() {
        val f = "Fecha;Hora;EC µS/cm\n14.10.2026;10:00:00;45,5\n14.10.2026;10:00:05;61,25\n"
        val r = ConductivityImport.parse(f, PA)
        assertNull(r.error, r.error)
        assertEquals(listOf(45.5, 61.25), r.readings.map { it.microSiemensPerCm })
        assertEquals(ms(2026, 10, 14, 10, 0, 5, PA), r.readings[1].atEpochMillis)
    }

    @Test fun `dia y mes se deciden con el resto del fichero`() {
        // La primera fila (03/10) se lee de las dos maneras; la tercera (13/10) solo como
        // dia/mes, y eso decide el fichero entero.
        val f = "datetime,conductivity\n03/10/2026 23:59:50,40\n03/10/2026 23:59:55,41\n13/10/2026 00:00:00,42\n"
        val r = ConductivityImport.parse(f, PA)
        assertEquals(ms(2026, 10, 3, 23, 59, 50, PA), r.readings[0].atEpochMillis)
        assertEquals(3, r.readings.size)
    }

    @Test fun `si el fichero no lo decide, decide la hora de inyeccion`() {
        val f = "datetime,conductivity\n03/04/2026 10:00:00,40\n03/04/2026 10:00:05,41\n"
        val inyeccion = ms(2026, 3, 4, 9, 58, 0, PA)          // 4 de marzo
        val r = ConductivityImport.parse(f, PA, elapsedOrigin = inyeccion)
        assertEquals(ms(2026, 3, 4, 10, 0, 0, PA), r.readings[0].atEpochMillis)
        val r2 = ConductivityImport.parse(f, PA, elapsedOrigin = ms(2026, 4, 3, 9, 58, 0, PA))
        assertEquals(ms(2026, 4, 3, 10, 0, 0, PA), r2.readings[0].atEpochMillis)
    }

    @Test fun `ISO 8601 con desfase no necesita adivinar`() {
        val f = "timestamp,SpC (uS/cm),Cond (uS/cm)\n2026-10-14T10:00:00-03:00,50,48\n2026-10-14T10:00:01-03:00,51,49\n"
        val r = ConductivityImport.parse(f, ZoneId.of("UTC"))
        assertTrue(r.column!!.header.startsWith("SpC"), "la especifica primero")
        assertEquals(ms(2026, 10, 14, 13, 0, 1, ZoneId.of("UTC")), r.readings[1].atEpochMillis)
        assertTrue(r.warnings.none { "time zone" in it })
    }

    @Test fun `segundos desde la inyeccion`() {
        val f = "elapsed,conductivity\n0,30\n5,55\n10,31\n"
        val t0 = 1_800_000_000_000L
        val r = ConductivityImport.parse(f, PA, elapsedOrigin = t0)
        assertEquals(listOf(t0, t0 + 5000, t0 + 10000), r.readings.map { it.atEpochMillis })
    }

    @Test fun `filas rotas se saltan y se cuentan`() {
        val f = "datetime,conductivity\n2026-10-14 10:00:00,40\nbasura,\n2026-10-14 10:00:10,\n2026-10-14 10:00:20,44\n"
        val r = ConductivityImport.parse(f, PA)
        assertEquals(2, r.readings.size)
        assertEquals(2, r.skippedRows)
    }

    @Test fun `sin columna de conductividad lo dice`() {
        val r = ConductivityImport.parse("time,level\n2026-10-14 10:00:00,1\n", PA)
        assertNotNull(r.error)
        assertTrue(r.readings.isEmpty())
    }

    // ------------------- el fichero real del conductimetro del PIRP 2024 -------------------

    /**
     * Un extracto de `CDA01001.XLS` (PIRP 2024, Bernal), sin tocar: texto separado por
     * tabuladores con fin de linea CR, cabecera `Ch1_Value`/`Ch1_Unit`, la unidad fila a fila
     * (uS que pasa a mS en salmuera), 99999999 fuera de escala, catorce filas del 1 de enero
     * de 2000 con el reloj sin poner, y el paso de sal de las 14:20 del 26 de noviembre.
     */
    private fun cda() = javaClass.getResource("/conductivity/CDA01001-excerpt.XLS")!!.readText()

    @Test fun `el fichero del PIRP se lee entero y bien`() {
        val r = ConductivityImport.parse(cda(), PA)
        assertNull(r.error, r.error)
        assertEquals("Ch1_Value", r.column!!.header)
        assertEquals(1, r.candidates.size, "Ch2 es temperatura y no se ofrece")
        assertEquals(9, r.overRange)
        assertEquals(14, r.farDated, "las del 2000, con el reloj sin poner")
        // 23/11: 11 filas, 9 fuera de escala -> 2; 26/11: 601. Total 603.
        assertEquals(603, r.readings.size)
        // 0002.000 mS es 2000 uS: con un factor unico por columna saldria 2.
        assertTrue(r.readings.any { it.microSiemensPerCm == 2000.0 })
        assertTrue(r.warnings.any { "mS" in it } && r.warnings.any { "over range" in it } &&
                   r.warnings.any { "far from the rest" in it }, r.warnings.toString())
        // No hay horas repetidas en el fichero: las lejanas no pueden contarse como tales.
        assertTrue(r.warnings.none { "repeated" in it }, r.warnings.toString())
        assertEquals(ms(2024, 11, 26, 14, 20, 23, PA),
                     r.readings.first { it.microSiemensPerCm == 26.8 }.atEpochMillis)
    }

    @Test fun `el paso de sal del 26 de noviembre da el Sigma calculado aparte`() {
        // Referencia: trapecios en Python sobre las mismas filas, base 21, de 14:15 a 14:34.
        val r = ConductivityImport.parse(cda(), PA)
        val s = SaltDilution(readings = r.readings, baseConductivity = 21.0,
                             windowStartMillis = ms(2024, 11, 26, 14, 15, 0, PA),
                             windowEndMillis = ms(2024, 11, 26, 14, 34, 0, PA))
        val res = SaltDilutionMath.compute(s)
        assertEquals(570, res.readings)
        assertEquals(2921.5, res.sigma, 1e-6)
        assertEquals(31.1 - 21.0, res.peakExcess!!, 1e-9)
    }

    @Test fun `el recorte alrededor de la inyeccion deja solo esa medicion`() {
        val r = ConductivityImport.parse(cda(), PA)
        val iny = ms(2024, 11, 26, 14, 18, 0, PA)
        val c = ConductivityImport.aroundInjection(r.readings, iny, beforeMin = 3, afterMin = 15)
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.atEpochMillis in (iny - 180_000)..(iny + 900_000) })
        assertTrue(c.none { it.microSiemensPerCm >= 2000.0 }, "la salmuera del 23 queda fuera")
    }

    @Test fun `un libro de Excel binario se rechaza diciendo que es`() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte())
        val r = ConductivityImport.parseBytes(ole + ByteArray(100), PA)
        assertTrue(r.error!!.contains("Excel workbook"), r.error)
        val xlsx = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100)
        assertTrue(ConductivityImport.parseBytes(xlsx, PA).error!!.contains("Excel workbook"))
    }

    @Test fun `un fichero en Latin-1 con la micro de un byte se lee`() {
        val txt = "datetime,EC (\u00B5S/cm)\n2026-10-14 10:00:00,40\n2026-10-14 10:00:05,41\n"
        val r = ConductivityImport.parseBytes(txt.toByteArray(Charsets.ISO_8859_1), PA)
        assertNull(r.error, r.error)
        assertEquals(listOf(40.0, 41.0), r.readings.map { it.microSiemensPerCm })
        assertTrue(r.warnings.none { "assumed" in it }, "la unidad se reconocio: ${r.warnings}")
    }

    @Test fun `el fichero del PIRP tal como sale del disco`() {
        val b = javaClass.getResource("/conductivity/CDA01001-excerpt.XLS")!!.readBytes()
        assertEquals(603, ConductivityImport.parseBytes(b, PA).readings.size)
    }
}
