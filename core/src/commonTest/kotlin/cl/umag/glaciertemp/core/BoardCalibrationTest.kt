package cl.umag.glaciertemp.core

import cl.umag.glaciertemp.core.BoardCalibration.BoardPoint
import cl.umag.glaciertemp.core.BoardCalibration.Plan
import cl.umag.glaciertemp.core.BoardCalibration.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardCalibrationTest {

    /** La primera serie del sensor de presion del 2026-10-13: metros contra cuentas. */
    private val serie = listOf(Point(0.0, 4), Point(1.0, 274), Point(2.0, 533), Point(3.0, 756))

    @Test fun `una recta exacta da su pendiente y RMSE cero`() {
        val f = assertNotNull(BoardCalibration.fit(listOf(Point(0.0, 10), Point(1.0, 110),
                                                           Point(2.0, 210))))
        assertEquals(100.0, f.slope, 1e-9)
        assertEquals(10.0, f.intercept, 1e-9)
        assertEquals(0.0, f.rmse, 1e-12)
        assertEquals(3, f.n)
    }

    @Test fun `el RMSE va en unidades reales y no en cuentas`() {
        // 100 cuentas por unidad y residuos de +-10 cuentas: 0,1 unidades, no 10.
        val f = assertNotNull(BoardCalibration.fit(listOf(
            Point(0.0, 10), Point(0.0, -10), Point(1.0, 110), Point(1.0, 90))))
        assertEquals(100.0, f.slope, 1e-9)
        assertEquals(0.1, f.rmse, 1e-9)
    }

    @Test fun `sin dos valores reales distintos no hay recta`() {
        assertNull(BoardCalibration.fit(listOf(Point(2.0, 350))))
        assertNull(BoardCalibration.fit(listOf(Point(2.0, 350), Point(2.0, 360))))
        assertNull(BoardCalibration.fit(emptyList()))
    }

    /**
     * Las respuestas TAL COMO SALEN de la placa: generadas compilando lightOStream.h en el PC
     * con los mismos `<<` que el firmware. La primera version de estas pruebas usaba
     * "1270mV (712)", sin los espacios, y la app fallo con la placa real.
     */
    @Test fun `la cuenta se lee de la respuesta real de la placa`() {
        assertEquals(249, BoardCalibration.rawFromReply("1591 mV (249 )\n"))
        assertEquals(96, BoardCalibration.rawFromReply("Depth [m] : 0.534 V (96 )\n"))
        assertEquals(3, BoardCalibration.rawFromReply("A0 : -0.012 V (3 )"))
        assertEquals(12, BoardCalibration.rawFromReply("Flow (1) [m3/s] : 3.000 V (12 )"))
    }

    @Test fun `la cuenta se lee del ultimo parentesis de la respuesta`() {
        assertEquals(712, BoardCalibration.rawFromReply("1270mV (712)\n"))
        assertEquals(96, BoardCalibration.rawFromReply("Depth [m]:0.534V (96)\n"))
        // El nombre del canal puede traer sus propios parentesis.
        assertEquals(12, BoardCalibration.rawFromReply("Flow (1) [m3/s]:3.000V (12)"))
        assertNull(BoardCalibration.rawFromReply("Pin disabled at compile"))
        assertNull(BoardCalibration.rawFromReply(""))
    }

    @Test fun `los puntos que se mandan estan sobre la recta y en los extremos medidos`() {
        val f = assertNotNull(BoardCalibration.fit(serie))
        val p = BoardCalibration.plan(f, serie) as Plan.Ok
        // En los extremos del rango, para que el redondeo pese lo menos posible.
        assertEquals(0.0, p.first.real, 0.01)
        assertEquals(3.0, p.second.real, 0.01)
        listOf(p.first, p.second).forEach {
            assertEquals(f.rawAt(it.real), it.raw.toDouble(), 0.5 * abs(f.slope) * 0.001 + 1e-9,
                         "el punto $it no esta sobre la recta")
        }
    }

    /**
     * Lo que importa de verdad: la recta que la placa CALCULA con los dos puntos guardados
     * --con su aritmetica entera, la de twoPointMv-- es la ajustada, en todo el rango.
     */
    @Test fun `la placa reproduce la recta ajustada con los dos puntos`() {
        val f = assertNotNull(BoardCalibration.fit(serie))
        val p = BoardCalibration.plan(f, serie) as Plan.Ok
        fun placa(count: Int): Long {
            val v1 = p.first.thousandths; val c1 = p.first.raw.toLong()
            val v2 = p.second.thousandths; val c2 = p.second.raw.toLong()
            val d = c2 - c1
            return (v1 * d + (v2 - v1) * (count - c1)) / d   // trunca hacia cero, como en C
        }
        for (c in 4..756) {
            val esperado = f.realAt(c.toDouble()) * 1000
            assertTrue(abs(placa(c) - esperado) <= 2.0,
                       "cuenta $c: placa ${placa(c)}, recta $esperado")
        }
    }

    @Test fun `el comando lleva tres decimales y la cuenta tras el signo mayor`() {
        val c = BoardCalibration.command(BoardCalibration.analog(0), 1, BoardPoint(2.0, 258))
        assertEquals("A01=2.000>258", c)
        assertEquals("V2=-0.500>7",
                     BoardCalibration.command(BoardCalibration.VOLTAGE, 2, BoardPoint(-0.5, 7)))
    }

    @Test fun `solo confirma si la placa guardo exactamente lo mandado`() {
        val p = BoardPoint(2.0, 258)
        assertTrue(BoardCalibration.confirms("2000mV->258\n", p))
        // Lo que contesta de verdad (lightOStream, ver arriba).
        assertTrue(BoardCalibration.confirms("2000 mV-> 258\n", p))
        assertFalse(BoardCalibration.confirms("2000 mV-> 259\n", p))
        assertTrue(BoardCalibration.confirms("-500 mV-> 7", BoardPoint(-0.5, 7)))
        assertFalse(BoardCalibration.confirms("2000mV->259\n", p),
                    "un firmware viejo guarda la cuenta que lee, no la mandada")
        assertFalse(BoardCalibration.confirms("1999mV->258\n", p))
        assertFalse(BoardCalibration.confirms("Bad count\n", p))
        assertTrue(BoardCalibration.confirms("-500mV->7", BoardPoint(-0.5, 7)))
    }

    @Test fun `una recta que se sale del ADC o del rango de la placa no se manda`() {
        val fueraAdc = listOf(Point(0.0, 0), Point(1.0, 900), Point(2.0, 1100))
        val f1 = assertNotNull(BoardCalibration.fit(fueraAdc))
        assertTrue(BoardCalibration.plan(f1, fueraAdc) is Plan.Problem)

        val grande = listOf(Point(0.0, 10), Point(40.0, 900))
        val f2 = assertNotNull(BoardCalibration.fit(grande))
        val p2 = BoardCalibration.plan(f2, grande)
        assertTrue(p2 is Plan.Problem && "32.767" in p2.message, "$p2")

        // Pendiente minima pero no nula: los dos extremos caen en la misma cuenta.
        val plano = listOf(Point(0.0, 100), Point(1.0, 100), Point(0.25, 101))
        val f3 = assertNotNull(BoardCalibration.fit(plano))
        assertTrue(BoardCalibration.plan(f3, plano) is Plan.Problem)
    }

    @Test fun `los canales son la bateria y los pines compilados`() {
        assertEquals(listOf("V"), BoardCalibration.channels(null).map { it.key })
        assertEquals(listOf("V"), BoardCalibration.channels(0x000F).map { it.key })
        assertEquals(listOf("V", "A0"), BoardCalibration.channels(0x0020 or 0x000F).map { it.key })
        assertEquals(listOf("V", "A0", "A3"),
                     BoardCalibration.channels(0x0020 or 0x0100).map { it.key })
    }

    @Test fun `lo copiado lleva los pares con comas y el ajuste`() {
        val f = BoardCalibration.fit(serie)
        val p = f?.let { BoardCalibration.plan(it, serie) }
        val t = BoardCalibration.clipboard(BoardCalibration.analog(0), "Depth [m]", serie, f, p,
                                           "GT001-ABC123", "2026-10-14 10:00")
        assertTrue("real,raw\n0,4\n1,274\n2,533\n3,756\n" in t, t)
        assertTrue("RMSE" in t && "n = 4" in t, t)
        assertTrue("A01=" in t && "A02=" in t, t)
        assertTrue("Depth [m]" in t && "GT001-ABC123" in t && "2026-10-14 10:00" in t, t)
    }

    @Test fun `sin recta lo copiado lo dice en vez de inventarla`() {
        val t = BoardCalibration.clipboard(BoardCalibration.VOLTAGE, null,
                                           listOf(Point(1.2, 700)), null, null, null, "x")
        assertTrue("1.2,700" in t, t)
        assertTrue("needs at least two" in t, t)
    }
}
