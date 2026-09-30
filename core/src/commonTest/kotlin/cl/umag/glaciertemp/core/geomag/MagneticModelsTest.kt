package cl.umag.glaciertemp.core.geomag

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Los modelos contra referencias INDEPENDIENTES de este codigo: los valores de prueba
 * oficiales de WMM2025 y la salida del programa oficial de IGRF-14 (igrf14.f). Ver
 * tools/geomag/gen_geomag.py. Corren en JVM y en Native: si el codigo comun diera otros
 * numeros en Kotlin/Native, fallaria aqui antes de llegar a un iPhone.
 */
class MagneticModelsTest {

    private fun filas(texto: String) = texto.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() }.map { l -> l.split(' ').map(String::toDouble) }.toList()

    private fun difAngulo(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180) d -= 360
        while (d <= -180) d += 360
        return abs(d)
    }

    @Test
    fun wmm2025ReproduceLosValoresDePruebaOficiales() {
        val f = filas(WMM2025_TEST_VALUES)
        assertEquals(100, f.size)
        for (p in f) {
            val r = MagneticModels.evaluate(MagneticModels.WMM2025, p[2], p[3], p[1] * 1000, p[0])!!
            val donde = "ano ${p[0]} lat ${p[2]} lon ${p[3]} alt ${p[1]} km"
            assertTrue(abs(r.x - p[7]) < 0.05, "X $donde: ${r.x} vs ${p[7]}")
            assertTrue(abs(r.y - p[8]) < 0.05, "Y $donde: ${r.y} vs ${p[8]}")
            assertTrue(abs(r.z - p[9]) < 0.05, "Z $donde: ${r.z} vs ${p[9]}")
            // D e I vienen redondeados a 0,01 grados en el fichero oficial
            assertTrue(difAngulo(r.declination, p[4]) <= 0.006, "D $donde: ${r.declination} vs ${p[4]}")
            assertTrue(abs(r.inclination - p[5]) <= 0.006, "I $donde: ${r.inclination} vs ${p[5]}")
            assertTrue(abs(r.declinationRate - p[11]) <= 1e-5,
                       "dD/dt $donde: ${r.declinationRate} vs ${p[11]}")
        }
    }

    @Test
    fun igrf14CoincideConElProgramaOficial() {
        val f = filas(IGRF14_REFERENCE)
        assertTrue(f.size > 300)
        for (p in f) {
            val (ano, alt, lat, lon) = listOf(p[0], p[1], p[2], p[3])
            val r = MagneticModels.evaluate(MagneticModels.IGRF14, lat, lon, alt * 1000, ano)!!
            val donde = "ano $ano lat $lat lon $lon alt $alt km"
            // igrf14.f arrastra alguna constante en precision simple: cerca del polo se ven
            // 0,012 nT sobre 56000 (2e-7). Se tolera 1e-6 relativo, que es redondeo, no modelo.
            val tol = 1e-6 * p[7] + 0.001
            assertTrue(abs(r.x - p[4]) < tol, "X $donde: ${r.x} vs ${p[4]}")
            assertTrue(abs(r.y - p[5]) < tol, "Y $donde: ${r.y} vs ${p[5]}")
            assertTrue(abs(r.z - p[6]) < tol, "Z $donde: ${r.z} vs ${p[6]}")
            // La declinacion hereda el error relativo de X e Y dividido por H: cerca de los
            // polos magneticos, con H de pocos cientos de nT, 0,01 nT ya son milesimas de grado.
            val dOficial = atan2(p[5], p[4]) * 180 / kotlin.math.PI
            val hh = sqrt(p[4] * p[4] + p[5] * p[5])
            assertTrue(difAngulo(r.declination, dOficial) < 1e-4 + 2 * tol / hh * 180 / kotlin.math.PI,
                       "D $donde: ${r.declination} vs $dOficial (H $hh)")
            // Tasa, con las derivadas que da el propio programa oficial (IGRF14SYN, isv=1)
            val h = sqrt(p[4] * p[4] + p[5] * p[5])
            val dRate = (p[4] * p[9] - p[5] * p[8]) / (h * h) * 180 / kotlin.math.PI
            assertTrue(abs(r.declinationRate - dRate) < 1e-4 + 1e-5 * abs(dRate),
                       "dD/dt $donde: ${r.declinationRate} vs $dRate")
        }
    }

    @Test
    fun fueraDeValidezNoHayNumero() {
        assertNull(MagneticModels.evaluate(MagneticModels.WMM2025, -53.0, -71.0, 0.0, 2024.99))
        assertNull(MagneticModels.evaluate(MagneticModels.WMM2025, -53.0, -71.0, 0.0, 2030.01))
        assertNull(MagneticModels.evaluate(MagneticModels.IGRF14, -53.0, -71.0, 0.0, 1899.99))
        assertNull(MagneticModels.evaluate(MagneticModels.IGRF14, -53.0, -71.0, 0.0, 2030.01))
        assertEquals("IGRF-14", MagneticModels.forDate(1990.0)?.name)
        assertEquals("WMM2025", MagneticModels.forDate(2026.7)?.name)
        assertNull(MagneticModels.forDate(2031.0))
    }

    @Test
    fun avisoCercaDelPoloMagnetico() {
        // Cerca del polo sur magnetico (64S 136E aprox. en 2026) H cae por debajo de 2000 nT
        val r = MagneticModels.evaluate(MagneticModels.WMM2025, -64.0, 136.0, 0.0, 2026.0)!!
        assertEquals(Reliability.UNRELIABLE, r.reliability)
        val pa = MagneticModels.evaluate(MagneticModels.WMM2025, -53.16, -70.91, 0.0, 2026.75)!!
        assertEquals(Reliability.NORMAL, pa.reliability)
    }

    @Test
    fun anoDecimal() {
        assertEquals(2024.0, DecimalYear.fromEpochMillis(DecimalYear.epochMillisOf(2024, 1, 1)), 1e-12)
        // 2024 es bisiesto: el 2 de julio a mediodia UTC es exactamente la mitad
        assertEquals(2024.5, DecimalYear.fromEpochMillis(DecimalYear.epochMillisOf(2024, 7, 2, 0.0)), 1e-9)
        assertEquals(1900.0, DecimalYear.fromEpochMillis(DecimalYear.epochMillisOf(1900, 1, 1)), 1e-12)
        assertEquals(1970, DecimalYear.yearOf(0L))
        assertEquals(1969, DecimalYear.yearOf(-1L))
        val t = DecimalYear.epochMillisOf(2026, 9, 30, 13.5)
        assertTrue(abs(t - DecimalYear.toEpochMillis(DecimalYear.fromEpochMillis(t))) <= 1)
    }

    @Test
    fun fechasEscritasAMano() {
        assertEquals(DecimalYear.epochMillisOf(1950, 3, 1), DecimalYear.parseUtc("1950-03-01"))
        assertEquals(DecimalYear.epochMillisOf(2024, 2, 29, 13.5), DecimalYear.parseUtc("2024-02-29 13:30"))
        assertNull(DecimalYear.parseUtc("2023-02-29"))
        assertNull(DecimalYear.parseUtc("2026-04-31"))
        assertNull(DecimalYear.parseUtc("2026-13-01"))
        assertNull(DecimalYear.parseUtc("26-01-01"))
        assertNull(DecimalYear.parseUtc("2026-01-01 24:00"))
    }

    @Test
    fun identificaElModeloDeAndroid() {
        val p = AndroidMagneticModel.probes
        assertEquals(4, p.size)
        // un "telefono" que contesta exactamente como WMM2020 de AOSP
        val como2020 = AndroidMagneticModel.identify { lat, lon, _ ->
            p.first { it.latitude == lat && it.longitude == lon }.wmm2020 + 0.005
        }
        assertEquals("WMM2020", como2020)
        val como2015 = AndroidMagneticModel.identify { lat, lon, _ ->
            p.first { it.latitude == lat && it.longitude == lon }.wmm2015
        }
        assertEquals("WMM2015", como2015)
        // uno actualizado por el fabricante a WMM2025 no se confunde con ninguno
        val otro = AndroidMagneticModel.identify { lat, lon, t ->
            MagneticModels.evaluate(MagneticModels.IGRF14, lat, lon, 0.0,
                                    DecimalYear.fromEpochMillis(t))!!.declination
        }
        assertNull(otro)
    }
}
