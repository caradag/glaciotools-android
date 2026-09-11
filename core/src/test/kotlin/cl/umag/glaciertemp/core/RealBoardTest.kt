package cl.umag.glaciertemp.core

import kotlin.test.*

/**
 * Oraculo contra HARDWARE REAL.
 *
 * Los dos ficheros salieron de una placa GlacierTemp (firmware 2.7, id DF652892D71B4237,
 * signature 0x1007) el 2026-09-05: la carga util de `LOGB=0,99` y las filas que la MISMA
 * placa emitio por `LOGC`. Si el decodificador de la app produce otra cosa, es la app la que
 * se equivoca -- no hay margen de interpretacion.
 *
 * Hasta aqui todo se validaba contra el simulador, y el simulador ya dejo pasar un fallo
 * (respondia con el codigo del comando en vez del rotulo). Esto no puede.
 */
class RealBoardTest {

    private val signature = 0x1007

    private fun payload(): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/real_board_logb.bin")).readBytes()

    private fun boardRows(): List<String> = requireNotNull(
        javaClass.getResourceAsStream("/real_board_logc.csv"))
        .bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun `la firma real describe tres canales de diez bytes`() {
        assertEquals(listOf("Volt", "Temp", "RH"), LogFormat.fields(signature).map { it.name })
        assertEquals(10, LogFormat.recordBytes(signature))
        assertEquals(1000, payload().size, "cien registros de diez bytes")
    }

    @Test
    fun `el CSV de la app coincide con el que emite la placa`() {
        val records = LogDecoder.decode(payload(), signature)
        val esperado = boardRows()
        assertEquals(esperado.size, records.size)

        val mios = records.map { CsvExporter.row(it, signature) }
        val primeraDiferencia = esperado.indices.firstOrNull { esperado[it] != mios[it] }
        assertNull(primeraDiferencia,
            "fila $primeraDiferencia:\n  placa: ${esperado.getOrNull(primeraDiferencia ?: 0)}" +
            "\n  app:   ${mios.getOrNull(primeraDiferencia ?: 0)}")
    }

    @Test
    fun `el identificador corto de la placa es el CRC-32 del completo`() {
        // Comprobado contra la placa: mostro F2AA6E89 para DF652892D71B4237.
        val uid = byteArrayOf(
            0xDF.toByte(), 0x65, 0x28, 0x92.toByte(),
            0xD7.toByte(), 0x1B, 0x42, 0x37)
        val crc = java.util.zip.CRC32().apply { update(uid) }.value
        assertEquals("F2AA6E89", "%08X".format(crc))
    }
}
