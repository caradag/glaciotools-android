package cl.umag.glaciertemp.core.geo

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class WeightedEstimateTest {

    private fun gauss(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** Ruido AR(1): cada muestra se parece a la anterior, como el error de un GNSS. */
    private fun serieCorrelada(n: Int, sigma: Double, rho: Double, semilla: Int): List<Double> {
        val r = Random(semilla)
        var x = gauss(r) * sigma
        return (0 until n).map {
            x = rho * x + kotlin.math.sqrt(1 - rho * rho) * gauss(r) * sigma
            x
        }
    }

    @Test
    fun `una muestra precisa pesa mas que una imprecisa`() {
        // Dos lecturas que se contradicen; la que el receptor declara buena manda.
        val a = WeightedEstimate.of(
            values = listOf(0.0, 10.0),
            sigmas = listOf(1.0, 10.0),
            sessions = listOf(0, 0))!!
        // Pesos 1 y 1/100: la media cae a 10/101 del valor malo, no a la mitad.
        assertEquals(10.0 / 101.0, a.estimate, 1e-9)

        // Y con las precisiones al reves, la estimacion se va al otro lado.
        val b = WeightedEstimate.of(listOf(0.0, 10.0), listOf(10.0, 1.0), listOf(0, 0))!!
        assertEquals(1000.0 / 101.0, b.estimate, 1e-9)
    }

    @Test
    fun `sin precision declarada los pesos son iguales`() {
        val a = WeightedEstimate.of(listOf(0.0, 10.0), listOf(null, null), listOf(0, 0))!!
        assertEquals(5.0, a.estimate, 1e-9)
    }

    @Test
    fun `un disparate se descarta y se cuenta, no se pondera`() {
        // El caso malo de verdad: un GNSS suelta un arreglo a cientos de metros Y lo declara
        // con buena precision, con lo que la ponderacion le daria MAS peso.
        val buenas = (0 until 200).map { 100.0 + (it % 7 - 3) * 0.5 }
        val v = buenas + listOf(700.0)
        val s = buenas.map { 5.0 } + listOf(0.5)
        val ses = List(v.size) { 0 }

        val a = WeightedEstimate.of(v, s, ses)!!
        assertEquals(1, a.rejected, "no se descarto el disparate")
        assertTrue(abs(a.estimate - 100.0) < 1.0,
                   "el disparate arrastro la estimacion a ${a.estimate}")
    }

    @Test
    fun `si medio conjunto sale disparatado, el que esta mal es el cribado`() {
        // Dos grupos legitimos separados: la MAD sale minuscula y el criterio se llevaria la
        // mitad de los datos. Ahi lo correcto es no cribar nada.
        val v = List(50) { 0.0 } + List(50) { 100.0 }
        val a = WeightedEstimate.of(v, List(100) { null }, List(100) { 0 })!!
        assertEquals(0, a.rejected)
        assertEquals(100, a.n)
    }

    @Test
    fun `muestras correlacionadas valen menos que su numero`() {
        // Mil arreglos en veinte minutos no son mil datos: son unos pocos repetidos mil
        // veces. Si n_ef fuera n, la pantalla prometeria una precision que no existe.
        val n = 1000
        val correlada = serieCorrelada(n, sigma = 5.0, rho = 0.95, semilla = 3)
        val independiente = serieCorrelada(n, sigma = 5.0, rho = 0.0, semilla = 3)

        val c = WeightedEstimate.of(correlada, List(n) { null }, List(n) { 0 })!!
        val i = WeightedEstimate.of(independiente, List(n) { null }, List(n) { 0 })!!

        assertTrue(c.nEffective < n / 10.0,
                   "con rho=0,95 n_ef deberia caer mucho, y dio ${c.nEffective}")
        assertTrue(i.nEffective > n * 0.7,
                   "sin correlacion n_ef deberia parecerse a n, y dio ${i.nEffective}")
        // Y por tanto la incertidumbre declarada es mucho mayor en la serie correlacionada.
        assertTrue(c.standardError > i.standardError * 3,
                   "la correlacion no encarecio la incertidumbre: " +
                   "${c.standardError} vs ${i.standardError}")
    }

    @Test
    fun `una sesion corta aparte pesa mas que su numero de muestras`() {
        // Lo que se buscaba: volver otro dia informa mas que quedarse una hora el primero.
        // Sesion larga y muy correlacionada, con un sesgo de 3 m; sesion corta sin sesgo.
        val larga = serieCorrelada(1200, sigma = 4.0, rho = 0.97, semilla = 11).map { it + 3.0 }
        val corta = serieCorrelada(60, sigma = 4.0, rho = 0.97, semilla = 12)

        val v = larga + corta
        val ses = List(larga.size) { 0 } + List(corta.size) { 1 }
        val juntas = WeightedEstimate.of(v, List(v.size) { null }, ses)!!

        // Si contaran las muestras a peso, la estimacion estaria practicamente en 3 (la
        // larga tiene veinte veces mas muestras). Con el reparto por sesiones se acerca
        // mucho mas al punto medio.
        val porMuestras = (larga.sum() + corta.sum()) / v.size
        assertTrue(abs(juntas.estimate) < abs(porMuestras),
                   "la sesion corta no gano peso: ${juntas.estimate} vs $porMuestras")
        assertEquals(2, juntas.sessions)
    }

    @Test
    fun `dos sesiones informan mas que una sola del doble de larga`() {
        val unaLarga = serieCorrelada(2000, sigma = 4.0, rho = 0.97, semilla = 21)
        val dosMitades = serieCorrelada(1000, sigma = 4.0, rho = 0.97, semilla = 22) +
                         serieCorrelada(1000, sigma = 4.0, rho = 0.97, semilla = 23)

        val a = WeightedEstimate.of(unaLarga, List(2000) { null }, List(2000) { 0 })!!
        val b = WeightedEstimate.of(dosMitades, List(2000) { null },
                                    List(1000) { 0 } + List(1000) { 1 })!!

        assertTrue(b.standardError < a.standardError,
                   "dos visitas no salieron mejor que una del doble: " +
                   "${b.standardError} vs ${a.standardError}")
    }

    @Test
    fun `con una sola muestra la incertidumbre es la que declara el receptor`() {
        val a = WeightedEstimate.of(listOf(42.0), listOf(6.0), listOf(0))!!
        assertEquals(42.0, a.estimate)
        assertEquals(6.0, a.standardError, 1e-9,
                     "con una muestra la incertidumbre es su propia precision, no cero")
        assertEquals(0.0, a.sd)
        assertEquals(1.0, a.nEffective)
    }

    @Test
    fun `sin muestras no hay estimacion`() {
        assertNull(WeightedEstimate.of(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `las tres listas tienen que describir las mismas muestras`() {
        assertFailsWith<IllegalArgumentException> {
            WeightedEstimate.of(listOf(1.0, 2.0), listOf(1.0), listOf(0, 0))
        }
    }

    @Test
    fun `la estimacion cae donde esta el valor de verdad`() {
        // Sin sesgo y con ruido moderado, el resultado tiene que acercarse al valor real
        // dentro de la incertidumbre que el mismo declara.
        val v = serieCorrelada(500, sigma = 5.0, rho = 0.9, semilla = 31).map { it + 250.0 }
        val a = WeightedEstimate.of(v, List(500) { 5.0 }, List(500) { 0 })!!
        assertTrue(abs(a.estimate - 250.0) < 3 * a.standardError,
                   "la estimacion (${a.estimate}) se aparta de 250 mas de lo que su propia " +
                   "incertidumbre (${a.standardError}) admite")
    }
}
