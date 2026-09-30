package cl.umag.glaciertemp.core.geo

import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import cl.umag.glaciertemp.core.geo.geoid.GeoidProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeightReferenceTest {
    private val soloEgm96 = GeoidProvider { m, _, _ -> if (m == GeoidModel.EGM96) 8.76 else null }

    @Test
    fun geoidalRestaNyLoDice() {
        val r = HeightReport.report(34.0, -53.16, -70.91,
                                    HeightReference.Orthometric(GeoidModel.EGM96), soloEgm96)
        assertEquals(25.24, r.value!!, 1e-9)
        assertEquals(34.0, r.ellipsoidal)
        assertEquals(8.76, r.undulation)
        assertEquals("m above the EGM96 geoid", r.label)
        assertNull(r.fallbackReason)
    }

    @Test
    fun sinDatosDelGeoideCaeAElipsoidalConEtiqueta() {
        val r = HeightReport.report(34.0, -53.16, -70.91,
                                    HeightReference.Orthometric(GeoidModel.EGM2008), soloEgm96)
        assertEquals(34.0, r.value)
        assertNull(r.model)
        assertEquals(HeightReport.ELLIPSOID_LABEL, r.label)
        assertEquals("EGM2008 not available here", r.fallbackReason)
    }

    @Test
    fun elipsoidalYSinAltura() {
        assertEquals(34.0, HeightReport.report(34.0, 0.0, 0.0, HeightReference.Ellipsoidal, soloEgm96).value)
        assertNull(HeightReport.report(null, 0.0, 0.0, HeightReference.Orthometric(GeoidModel.EGM96),
                                       soloEgm96).value)
    }

    @Test
    fun teselas() {
        assertEquals(-60 to -80, GeoidModel.tileCorner(-53.16, -70.91))
        assertEquals("egm2008-1m-S60W080.gtg.gz", GeoidModel.tileName(GeoidModel.EGM2008, -60, -80))
        assertEquals(80 to -180, GeoidModel.tileCorner(90.0, 180.0))   // 180 E = -180
        assertEquals(-90 to -180, GeoidModel.tileCorner(-90.0, -180.0))
        assertEquals(0 to 0, GeoidModel.tileCorner(0.0, 0.0))
        assertEquals("egm2008-1m-N00E000.gtg.gz", GeoidModel.tileName(GeoidModel.EGM2008, 0, 0))
    }
}
