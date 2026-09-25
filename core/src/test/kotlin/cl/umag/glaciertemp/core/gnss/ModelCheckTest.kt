package cl.umag.glaciertemp.core.gnss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCheckTest {

    private val gps: List<Tle> by lazy {
        Tle.parse(javaClass.getResourceAsStream("/gps-ops-20260924.tle")!!
                      .bufferedReader().readText(), Constellation.GPS)
    }

    @Test fun `el svid sale del nombre en GPS y BeiDou, y no se adivina en los demas`() {
        assertEquals(22, gps.first { it.name.contains("PRN 22") }.svid)
        val bds = Tle.parse("""
BEIDOU-2 IGSO-1 (C06)
1 36828U 10036A   26266.50000000  .00000000  00000+0  00000+0 0  9999
2 36828  52.0000 100.0000 0050000 200.0000 160.0000  1.00270000 55555
""".trimIndent(), Constellation.BEIDOU)
        assertEquals(6, bds.single().svid)

        val gal = Tle.parse("""
GSAT0101 (GALILEO-PFM)
1 37846U 11060A   26266.50000000  .00000000  00000+0  00000+0 0  9999
2 37846  56.0000 100.0000 0003000 200.0000 160.0000  1.70470000 55555
""".trimIndent(), Constellation.GALILEO)
        assertNull(gal.single().svid, "GSAT0101 es el numero de serie, no el PRN: no se adivina")
    }

    /**
     * La separacion angular NO es restar azimut y elevacion. Cerca del cenit, dos direcciones
     * con noventa grados de azimut entre ellas estan casi juntas.
     */
    @Test fun `la separacion es angular de verdad`() {
        assertEquals(1.0, ModelCheck.separationDeg(0.0, 40.0, 0.0, 41.0), 1e-9)
        assertEquals(0.0, ModelCheck.separationDeg(123.0, 55.0, 123.0, 55.0), 1e-9)
        // A 89 grados de elevacion, 90 grados de azimut son menos de dos grados reales.
        val cerca = ModelCheck.separationDeg(0.0, 89.0, 90.0, 89.0)
        assertTrue(cerca < 1.5, "cerca del cenit deberia salir pequeno, salio $cerca")
        // En el horizonte, esos mismos 90 grados de azimut son 90 grados.
        assertEquals(90.0, ModelCheck.separationDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
    }

    /**
     * EL CASO QUE IMPORTA: si lo observado coincide con el modelo, el error es cero. Se
     * construye la observacion CON el propio modelo, asi que cualquier desacuerdo seria un
     * fallo del emparejamiento, no de la fisica.
     */
    @Test fun `un cielo que coincide da error cero`() {
        val t = gps.first().epochMillis
        val obs = gps.mapNotNull { sat ->
            val id = sat.svid ?: return@mapNotNull null
            val p = SkyModel.skyPos(sat, t, -53.15, -73.0)
            if (p.elevationDeg <= 0) null
            else ObservedSat(Constellation.GPS, id, p.azimuthDeg, p.elevationDeg)
        }
        val r = ModelCheck.compare(gps, obs, t, -53.15, -73.0)
        assertTrue(r.matched >= 4, "solo se emparejaron ${r.matched}")
        assertTrue(r.maxErrorDeg!! < 1e-6, "error ${r.maxErrorDeg}")
    }

    /** Y si uno esta desplazado, se reporta ESE, que es el peor. */
    @Test fun `se reporta el peor, no la media`() {
        val t = gps.first().epochMillis
        val obs = gps.mapNotNull { sat ->
            val id = sat.svid ?: return@mapNotNull null
            val p = SkyModel.skyPos(sat, t, -53.15, -73.0)
            if (p.elevationDeg <= 0) null
            else ObservedSat(Constellation.GPS, id, p.azimuthDeg, p.elevationDeg)
        }
        val movido = obs.mapIndexed { i, o ->
            if (i == 0) o.copy(elevationDeg = o.elevationDeg + 3.0) else o
        }
        val r = ModelCheck.compare(gps, movido, t, -53.15, -73.0)
        assertEquals(3.0, r.maxErrorDeg!!, 1e-6)
    }

    /** Sin nada que emparejar no se inventa un numero. */
    @Test fun `sin emparejamientos devuelve null`() {
        val r = ModelCheck.compare(gps, emptyList(), gps.first().epochMillis, -53.15, -73.0)
        assertEquals(0, r.matched)
        assertNull(r.maxErrorDeg)
    }

    /** Un svid que el modelo no conoce no cuenta: no hay con que comparar. */
    @Test fun `un satelite desconocido se ignora en vez de contarse como error`() {
        val t = gps.first().epochMillis
        val r = ModelCheck.compare(
            gps, listOf(ObservedSat(Constellation.GPS, 99, 10.0, 40.0)), t, -53.15, -73.0)
        assertEquals(0, r.matched)
        assertNull(r.maxErrorDeg)
    }
}
