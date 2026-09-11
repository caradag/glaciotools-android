package cl.umag.glaciertemp.transport

import kotlin.test.*

class GattProfileTest {

    private fun ch(uuid: String, notify: Boolean = false, write: Boolean = false,
                   wnr: Boolean = false, indicate: Boolean = false) =
        GattCharacteristic(uuid, notify = notify, indicate = indicate,
            write = write, writeNoResponse = wnr)

    private val gap = GattService("1800", listOf(ch("2a00")))
    private val devInfo = GattService("180a", listOf(ch("2a29")))

    @Test
    fun `detecta el HM-10 con una sola caracteristica`() {
        val p = GattProfiles.select(listOf(gap, devInfo,
            GattService("ffe0", listOf(ch("ffe1", notify = true, wnr = true)))))
        assertNotNull(p)
        assertEquals(GattProfiles.normalize("ffe1"), p.writeChar)
        assertEquals(p.writeChar, p.notifyChar)
        assertTrue(p.writeNoResponse)
    }

    @Test
    fun `en el Nordic UART no confunde el sentido de RX y TX`() {
        // El fallo clasico: RX y TX llevan el nombre desde el punto de vista del MODULO.
        // Escribir en 0003 y escuchar 0002 conecta bien y no recibe nunca nada.
        val p = GattProfiles.select(listOf(gap, GattService(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e", listOf(
                ch("6e400002-b5a3-f393-e0a9-e50e24dcca9e", write = true, wnr = true),
                ch("6e400003-b5a3-f393-e0a9-e50e24dcca9e", notify = true)))))
        assertNotNull(p)
        assertTrue(p.writeChar.startsWith("6e400002"))
        assertTrue(p.notifyChar.startsWith("6e400003"))
    }

    @Test
    fun `detecta el transparent UART de Microchip`() {
        val p = GattProfiles.select(listOf(GattService(
            "49535343-fe7d-4ae5-8fa9-9fafd205e455", listOf(
                ch("49535343-1e4d-4bd9-ba61-23c647249616", notify = true),
                ch("49535343-8841-43f4-a8d4-ecbe34729bb3", write = true, wnr = true)))))
        assertNotNull(p)
        assertEquals("Microchip transparent UART", p.name)
    }

    @Test
    fun `un FFE0 con dos caracteristicas separadas tambien vale`() {
        val p = GattProfiles.select(listOf(GattService("ffe0", listOf(
            ch("ffe1", notify = true),
            ch("ffe2", write = true)))))
        assertNotNull(p)
        assertTrue(p.notifyChar.startsWith("0000ffe1"))
        assertTrue(p.writeChar.startsWith("0000ffe2"))
    }

    @Test
    fun `un modulo desconocido se descubre por sus propiedades`() {
        val p = GattProfiles.select(listOf(gap, devInfo, GattService(
            "0000abcd-1234-5678-9abc-def012345678", listOf(
                ch("0000abce-1234-5678-9abc-def012345678", notify = true, write = true)))))
        assertNotNull(p)
        assertEquals(p.writeChar, p.notifyChar)
    }

    @Test
    fun `no elige el servicio de bateria ni el de informacion`() {
        // Ambos tienen caracteristicas con notify; sin la lista de exclusion, el
        // descubrimiento generico se quedaria con el primero y la app no recibiria nada.
        val p = GattProfiles.select(listOf(
            GattService("180f", listOf(ch("2a19", notify = true, write = true))),
            GattService("ffe0", listOf(ch("ffe1", notify = true, wnr = true)))))
        assertNotNull(p)
        assertTrue(p.service.startsWith("0000ffe0"))
    }

    @Test
    fun `un dispositivo sin puente serie devuelve null`() {
        assertNull(GattProfiles.select(listOf(gap, devInfo)))
    }

    @Test
    fun `una caracteristica que solo notifica no sirve para escribir`() {
        assertNull(GattProfiles.select(listOf(
            GattService("ffe0", listOf(ch("ffe1", notify = true))))))
    }

    @Test
    fun `acepta indicate ademas de notify`() {
        val p = GattProfiles.select(listOf(GattService(
            "0000abcd-1234-5678-9abc-def012345678", listOf(
                ch("0000abce-1234-5678-9abc-def012345678", indicate = true),
                ch("0000abcf-1234-5678-9abc-def012345678", write = true)))))
        assertNotNull(p)
        assertTrue(p.notifyChar.startsWith("0000abce"))
    }

    @Test
    fun `la forma corta y la larga del UUID son el mismo servicio`() {
        assertEquals(GattProfiles.normalize("FFE0"),
            GattProfiles.normalize("0000ffe0-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun `el payload descuenta la cabecera ATT y nunca baja de 20`() {
        assertEquals(20, GattProfiles.payloadForMtu(23))
        assertEquals(244, GattProfiles.payloadForMtu(247))
        // Un modulo que negocie un MTU absurdo no debe dejar la escritura en cero.
        assertEquals(20, GattProfiles.payloadForMtu(10))
    }
}
