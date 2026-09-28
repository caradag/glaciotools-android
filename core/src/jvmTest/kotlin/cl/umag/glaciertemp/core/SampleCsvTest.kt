package cl.umag.glaciertemp.core

import kotlin.test.*

/**
 * Comprueba el CSV de ejemplo que genera tools/make_sample_csv.py.
 *
 * Un fichero de prueba que no se prueba es una trampa: si no carga, quien lo use no sabra
 * si falla el fichero o la app. Aqui se recorre la misma cadena que en el telefono --
 * importar, graficar, estimar la bateria-- sobre el fichero real.
 */
class SampleCsvTest {

    private fun sample(): String = requireNotNull(
        javaClass.getResourceAsStream("/glaciotools_ejemplo.csv")
    ) { "falta glaciotools_ejemplo.csv en los recursos de prueba" }
        .bufferedReader().readText()

    @Test
    fun `el ejemplo se carga y trae los canales esperados`() {
        val log = CsvImporter.parse(sample())
        assertEquals(0x101F, log.signature, "cuatro canales de serie mas una sonda DS18B20")
        assertEquals(0, log.skipped, "ninguna fila deberia ser ilegible")
        assertTrue(log.records.size > 6000, "son ${log.records.size} registros")
        assertEquals(listOf("Volt", "Temp", "RH", "HAtemp", "DS0"),
            LogFormat.fields(log.signature).map { it.name })
    }

    @Test
    fun `trae lecturas fallidas y un hueco, que es lo que rompe un grafico`() {
        val log = CsvImporter.parse(sample())
        assertTrue(log.records.any { it.values.any { v -> v == null } },
            "el ejemplo deberia incluir lecturas NaN")

        val step = java.time.Duration.ofMinutes(10)
        val gap = log.records.zipWithNext().maxOf {
            java.time.Duration.between(it.first.time, it.second.time)
        }
        assertTrue(gap > step.multipliedBy(6), "esperaba un hueco largo, el mayor fue $gap")
    }

    @Test
    fun `se puede graficar cualquiera de sus canales`() {
        val log = CsvImporter.parse(sample())
        for (f in LogFormat.fields(log.signature)) {
            val serie = Chart.series(log.records, log.signature, f.name)
            assertFalse(serie.isEmpty, "la serie ${f.name} salio vacia")
        }
    }

    @Test
    fun `la estimacion de bateria da un resultado utilizable`() {
        val log = CsvImporter.parse(sample())
        val e = Battery.estimate(log.records, log.signature,
            BatteryType.ALKALINE_ENERGIZER, BatteryType.ALKALINE_ENERGIZER.nominalMah, 1)
        // El ejemplo descarga de 1,545 V a 1,245 V en 45 dias a proposito, para que la
        // regresion tenga pendiente clara y la estimacion no salga "no disponible".
        val a = assertIs<BatteryEstimate.Available>(e, "la estimacion no fue utilizable: $e")
        assertTrue(a.mahPerDay > 0, "consumo diario no positivo: ${a.mahPerDay}")
        assertTrue(a.daysRemaining > 0, "vida restante no positiva: ${a.daysRemaining}")
    }

    @Test
    fun `reexportarlo produce un CSV que vuelve a cargarse igual`() {
        val log = CsvImporter.parse(sample())
        val again = CsvImporter.parse(CsvExporter.export(log.records, log.signature))
        assertEquals(log.signature, again.signature)
        assertEquals(log.records.size, again.records.size)
        val bad = log.records.indices.firstOrNull { log.records[it] != again.records[it] }
        assertNull(bad, "primera diferencia en $bad:\n  ${log.records.getOrNull(bad ?: 0)}\n  ${again.records.getOrNull(bad ?: 0)}")
    }
}
