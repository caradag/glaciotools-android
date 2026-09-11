package cl.umag.glaciertemp.core

import kotlin.test.*

class IntelHexTest {

    @Test fun `un checksum malo descarta la linea en vez de decodificarla`() {
        // Ese es el motivo de usar Intel HEX y no un volcado crudo.
        val good = ":0400000001020304F2"
        assertEquals(4, IntelHex.parse(good).data.size)
        assertEquals(0, IntelHex.parse(good).badLines)
        val bad = ":0400000001020304FF"
        assertEquals(1, IntelHex.parse(bad).badLines)
        assertEquals(0, IntelHex.parse(bad).data.size)
    }

    @Test fun `un digito hexadecimal invalido se rechaza`() {
        assertEquals(1, IntelHex.parse(":04000000010203ZZF2").badLines)
    }

    @Test fun `los registros tipo 04 desplazan la direccion base`() {
        val text = ":020000040001F9\n:0400000005060708E2\n:00000001FF"
        val cap = IntelHex.parse(text)
        assertEquals(0x10000 + 4, cap.data.size)
        assertEquals(5, cap.data[0x10000].toInt())
    }
}
