package cl.umag.glaciertemp.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeightCheckTest {

    private fun conChecksum(cuerpo: String): String {
        var x = 0
        for (c in cuerpo) x = x xor c.code
        return "$" + cuerpo + "*" + x.toString(16).uppercase().padStart(2, '0')
    }

    @Test
    fun leeGgaDeVariasConstelaciones() {
        // Ejemplo clasico de la documentacion NMEA, checksum 47
        val g = Nmea.parseGga("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")
        assertNotNull(g)
        assertEquals(12 * 3600 + 35 * 60 + 19.0, g.timeOfDaySeconds, 1e-9)
        assertEquals(545.4, g.mslAltitude, 1e-9)
        assertEquals(46.9, g.geoidSeparation, 1e-9)
        val gn = Nmea.parseGga(conChecksum("GNGGA,120000.00,5309.600,S,07054.600,W,1,12,0.7,24.0,M,10.2,M,,"))
        assertEquals(10.2, gn!!.geoidSeparation, 1e-9)
    }

    @Test
    fun rechazaLoQueNoSirve() {
        assertNull(Nmea.parseGga("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"))  // checksum
        assertNull(Nmea.parseGga(conChecksum("GPGGA,123519,4807.038,N,01131.000,E,0,00,,,M,,M,,")))   // sin arreglo
        assertNull(Nmea.parseGga(conChecksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")))
        assertNull(Nmea.parseGga(conChecksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,,M,,")))  // sin N
    }

    private fun gga(msl: Double, n: Double) = Gga(0.0, msl, n, 1)

    @Test
    fun telefonoQueDaElipsoidal() {
        val c = HeightCheck(required = 10)
        repeat(9) { assertEquals(HeightVerdict.UNVERIFIED, c.offer(34.3 + it * 0.1, gga(24.1 + it * 0.1, 10.2))) }
        assertEquals(HeightVerdict.ELLIPSOIDAL, c.offer(34.3, gga(24.1, 10.2)))
    }

    @Test
    fun telefonoQueDaNivelDelMar() {
        val c = HeightCheck(required = 10)
        repeat(10) { c.offer(24.1, gga(24.1, 10.2)) }
        assertEquals(HeightVerdict.MSL, c.verdict)
        assertEquals(10.2, c.lastSeparation!!, 1e-12)
    }

    @Test
    fun conSeparacionPequenaNoSeVota() {
        val c = HeightCheck(required = 3)
        repeat(20) { c.offer(101.0, gga(100.0, 1.0)) }
        assertEquals(HeightVerdict.UNVERIFIED, c.verdict)
        assertEquals(0, c.ellipsoidalVotes + c.mslVotes)
    }

    @Test
    fun cuentaLosParesAunqueNoVoten() {
        val c = HeightCheck(required = 3)
        repeat(4) { c.offer(101.0, gga(100.0, 1.0)) }
        c.offer(34.3, gga(24.1, 10.2))
        assertEquals(5, c.pairs)
        assertEquals(4, c.smallSeparation)
        assertEquals(1, c.ellipsoidalVotes)
    }

    @Test
    fun unaContradiccionAnulaElVeredicto() {
        val c = HeightCheck(required = 3)
        repeat(5) { c.offer(34.3, gga(24.1, 10.2)) }
        c.offer(24.1, gga(24.1, 10.2))
        assertEquals(HeightVerdict.UNVERIFIED, c.verdict)
        assertTrue(c.contradictory)
    }

    @Test
    fun emparejaPorHoraDelDia() {
        assertTrue(HeightCheck.matches(Gga(43200.0, 0.0, 10.0, 1), 43200.4))
        assertTrue(HeightCheck.matches(Gga(86399.8, 0.0, 10.0, 1), 0.1))   // a traves de medianoche
        assertTrue(!HeightCheck.matches(Gga(43200.0, 0.0, 10.0, 1), 43201.0))
    }
}
