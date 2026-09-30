package cl.umag.glaciertemp.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceInfoErrorsTest {

    private val base = "INFO fw=3.6 proto=5 id=E5A1B2C3D4E5F607 sig=0x100F rec=12 count=200 " +
        "flash=8388608 sid=GT001-E5F607 baud=115200 fastbaud=230400"

    @Test fun `lee el registro de fallos de la linea INFO`() {
        val i = assertNotNull(DeviceInfo.parse("$base err=0x3218 errn=17\r\n"))
        assertEquals(listOf(8, 1, 2, 3), i.sensorErrors!!.codes)
        assertEquals(17, i.sensorErrors!!.failedAttempts)
        // Los campos de siempre no se ven afectados por los nuevos.
        assertEquals(230400, i.fastBaud)
        assertEquals("GT001-E5F607", i.displayId)
    }

    @Test fun `un firmware anterior a 3_6 no trae el registro y no se inventa`() {
        val i = assertNotNull(DeviceInfo.parse("$base\r\n"))
        assertNull(i.sensorErrors)
    }
}
