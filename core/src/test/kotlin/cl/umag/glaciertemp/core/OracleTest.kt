package cl.umag.glaciertemp.core

import kotlin.test.*

/**
 * Compara la salida de :core contra la de decode_logh.py, que ya esta probada en campo.
 * Los vectores se generan con tools/make_test_vectors.py y viven en test/resources.
 */
class OracleTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "falta $name" }
            .bufferedReader().readText()

    private fun caseNames() = listOf("std", "ds1", "ds3", "ds8", "analog")

    @Test fun `el CSV coincide con el del oraculo en todos los vectores`() {
        for (name in caseNames()) {
            val capture = IntelHex.parse(resource("$name.logh"))
            assertEquals(0, capture.badLines, "$name: lineas Intel HEX invalidas")
            val sig = assertNotNull(capture.signature, "$name: sin signature en la cabecera")
            val records = LogDecoder.decode(capture.data, sig)
            val mine = CsvExporter.export(records, sig).trimEnd('\n')
            val theirs = resource("$name.csv").trimEnd('\n')
            assertEquals(theirs, mine, "$name: el CSV difiere del oraculo")
        }
    }

    @Test fun `el signature se recupera de la cabecera de la captura`() {
        assertEquals(0x141F, IntelHex.parse(resource("ds3.logh")).signature)
    }
}
