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

/**
 * Con los numeros REALES medidos en un telefono en Chile el 2026-09-24.
 *
 * Es la prueba que faltaba: el defecto no se podia reproducir en el emulador --no entrega
 * satelites en GnssStatus-- y solo aparecio contra el cielo de verdad. Estos son los datos
 * tal cual salieron en pantalla.
 */
class ModelCheckRealSkyTest {

    /** (constelacion, svid, model az, model el, seen az, seen el) tal como se vieron. */
    private val medido = listOf(
        Triple("C", 13, listOf(19.4, -78.2, 286.0, 42.0)),
        Triple("C", 14, listOf(244.6, 71.4, 109.0, 46.0)),
        Triple("G", 11, listOf(222.4, 16.1, 222.0, 15.0)),
        Triple("G", 6, listOf(240.4, 47.0, 240.0, 46.0)),
        Triple("C", 27, listOf(212.3, 8.9, 212.0, 8.0)),
        Triple("G", 1, listOf(23.8, 10.4, 23.0, 10.0)),
        Triple("C", 28, listOf(248.6, 35.3, 248.0, 35.0)),
        Triple("C", 26, listOf(68.4, 25.5, 68.0, 25.0)),
        Triple("G", 3, listOf(83.4, 49.4, 83.0, 49.0)),
        Triple("G", 9, listOf(295.0, 59.4, 295.0, 59.0)),
        Triple("G", 31, listOf(135.4, 22.2, 135.0, 22.0)),
        Triple("G", 4, listOf(128.0, 81.8, 127.0, 82.0)),
        Triple("C", 21, listOf(105.2, 22.0, 105.0, 22.0)),
    )

    private fun filas() = medido.map { (letra, svid, v) ->
        val c = if (letra == "G") Constellation.GPS else Constellation.BEIDOU
        SatComparison(c, svid, v[0], v[1], v[2], v[3],
                      ModelCheck.separationDeg(v[0], v[1], v[2], v[3]))
    }

    @Test fun `los dos BeiDou-2 se separan, y el resto coincide dentro de grado y medio`() {
        val (malos, buenos) = filas().partition {
            it.separationDeg > ModelCheck.IDENTITY_MISMATCH_DEG
        }
        assertEquals(setOf(13, 14), malos.map { it.svid }.toSet(),
                     "solo C13 y C14 deberian quedar fuera")
        assertEquals(11, buenos.size)
        assertTrue(buenos.maxOf { it.separationDeg } < 1.5,
                   "los buenos llegan a ${buenos.maxOf { it.separationDeg }} grados")
    }

    /**
     * Y esto es lo que hacia inutil el numero: sin separar las dos averias, once satelites
     * coincidiendo dentro de un grado quedaban tapados por uno mal identificado.
     */
    @Test fun `sin separar, el peor tapaba a los once buenos`() {
        val peorDeTodos = filas().maxOf { it.separationDeg }
        assertTrue(peorDeTodos > 130.0, "el peor bruto era $peorDeTodos")
    }
}
