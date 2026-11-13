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

    // ---------------- la incertidumbre frente a la dispersion que se observa ----------------

    @Test
    fun `sin precision declarada la incertidumbre sale de la dispersion y no de un metro supuesto`() {
        // Los mismos arreglos a dos escalas, independientes y sin sigma declarada. Con el
        // metro supuesto las dos series salian con la MISMA incertidumbre, 1/raiz(n) = 0,05 m,
        // se dispersaran 3 m o 10.
        val n = 400
        val nada = List<Double?>(n) { null }
        val a3 = WeightedEstimate.of(serieCorrelada(n, 3.0, 0.0, 41), nada, List(n) { 0 })!!
        val a10 = WeightedEstimate.of(serieCorrelada(n, 10.0, 0.0, 41), nada, List(n) { 0 })!!
        // Lo que respalda la dispersion es sigma/raiz(n): 0,15 y 0,50 m.
        assertEquals(0.15, a3.standardError, 0.05)
        assertEquals(0.50, a10.standardError, 0.15)
        // Y misma semilla, o sea la misma serie a otra escala: la incertidumbre escala igual.
        assertEquals(10.0 / 3.0, a10.standardError / a3.standardError, 1e-6)
    }

    @Test
    fun `un telefono que declara mas precision de la que tiene no rebaja la incertidumbre`() {
        // Declara 1,5 m y los arreglos se dispersan 3 m. Solo con lo declarado salia
        // 1,5/raiz(n) = 0,075 m, la mitad de lo que la dispersion respalda.
        val n = 400
        val a = WeightedEstimate.of(serieCorrelada(n, 3.0, 0.0, 43), List(n) { 1.5 }, List(n) { 0 })!!
        assertEquals(0.15, a.standardError, 0.05)
    }

    @Test
    fun `un telefono pesimista se queda con lo que declara`() {
        // Declara 5 m y se dispersa 1 m. La escala solo actua hacia arriba, asi que la
        // incertidumbre sigue siendo la declarada, 5/raiz(n_ef), y no baja a la observada.
        val n = 400
        val a = WeightedEstimate.of(serieCorrelada(n, 1.0, 0.0, 44), List(n) { 5.0 }, List(n) { 0 })!!
        assertEquals(5.0 / kotlin.math.sqrt(a.nEffective), a.standardError, 1e-9)
    }

    @Test
    fun `una salida congelada es una medida y no mil`() {
        // Un receptor que repite el mismo arreglo no esta midiendo mil veces. Con varianza
        // nula la autocorrelacion es 0/0, y lo que salia dependia del redondeo de la media.
        // Con una coordenada cualquiera queda un residuo, rho se pega al tope y n_ef = n/99:
        // 1200 arreglos iguales con 4 m declarados daban +-1,15 m.
        val v = List(1200) { 4301234.567 }
        val a = WeightedEstimate.of(v, List(1200) { 4.0 }, List(1200) { 0 })!!
        assertEquals(1.0, a.nEffective)
        assertEquals(4.0, a.standardError, 1e-6)
        // Y con un valor que la media reproduce exacta salia rho = 0 y n_ef = n: +-0,12 m.
        val b = WeightedEstimate.of(List(1200) { 100.0 }, List(1200) { 4.0 }, List(1200) { 0 })!!
        assertEquals(1.0, b.nEffective)
        assertEquals(4.0, b.standardError, 1e-6)
    }

    /**
     * Cuantas veces mayor es el error REAL de la estimacion que la incertidumbre que declara,
     * repitiendo la medicion contra un valor conocido (cero). Uno es decir la verdad; por
     * encima de uno, prometer de mas.
     */
    private fun calibracion(
        repeticiones: Int,
        medir: (Int) -> Triple<List<Double>, List<Double?>, List<Int>>,
    ): Double {
        var error2 = 0.0
        var declarado2 = 0.0
        repeat(repeticiones) { k ->
            val (v, s, ses) = medir(k)
            val a = WeightedEstimate.of(v, s, ses)!!
            error2 += a.estimate * a.estimate
            declarado2 += a.standardError * a.standardError
        }
        return kotlin.math.sqrt(error2 / declarado2)
    }

    @Test
    fun `la incertidumbre declarada se parece al error real`() {
        // Lo que ninguna prueba miraba: no si la incertidumbre sube o baja, sino si DICE LA
        // VERDAD. Sesiones de 300 arreglos con rho = 0,5, repetidas 300 veces.
        val n = 300
        fun caso(real: Double, declarada: Double?) = calibracion(300) { k ->
            Triple(serieCorrelada(n, real, 0.5, 1000 + k), List(n) { declarada }, List(n) { 0 })
        }
        // Con la precision bien declarada ya decia la verdad, y la sigue diciendo.
        val bien = caso(3.0, 3.0)
        assertTrue(bien in 0.75..1.15, "bien declarada: $bien")
        // Sin precision declarada prometia 3 veces de mas con 3 m de dispersion y 9 con 10 m.
        val sin3 = caso(3.0, null)
        val sin10 = caso(10.0, null)
        assertTrue(sin3 in 0.75..1.15, "sin precision, 3 m: $sin3")
        assertTrue(sin10 in 0.75..1.15, "sin precision, 10 m: $sin10")
        // Con un telefono optimista, 2 veces de mas.
        val optimista = caso(3.0, 1.5)
        assertTrue(optimista in 0.75..1.15, "telefono optimista: $optimista")
        // Con uno pesimista queda muy por debajo de uno: de menos, que es el lado prudente.
        assertTrue(caso(1.0, 5.0) < 0.5)
    }

    // ------------------------ el desacuerdo entre sesiones ------------------------

    @Test
    fun `dos visitas que no concuerdan no se promedian como si concordaran`() {
        // Dos sesiones buenas, de 300 arreglos independientes con 3 m declarados y reales,
        // pero con las medias separadas 6 m: el sesgo propio de cada dia, que es justo lo que
        // una segunda visita viene a destapar. Cada una declara +-0,17 m, y combinadas daban
        // +-0,12 m para dos medias que distan 6 m.
        val n = 300
        val a = serieCorrelada(n, 3.0, 0.0, 51)
        val b = serieCorrelada(n, 3.0, 0.0, 52).map { it + 6.0 }
        val juntas = WeightedEstimate.of(a + b, List(2 * n) { 3.0 }, List(n) { 0 } + List(n) { 1 })!!
        assertEquals(2, juntas.sessions)
        // La estimacion sigue siendo el punto medio, como antes.
        assertEquals(3.0, juntas.estimate, 0.5)
        // Y la incertidumbre cubre el desacuerdo: con dos sesiones iguales de buenas, la
        // mitad de lo que distan.
        assertEquals(3.0, juntas.standardError, 0.5)
    }

    @Test
    fun `el sesgo de cada dia entra en la incertidumbre`() {
        // Calibracion de lo que la ayuda de la app recomienda hacer: volver otro dia. Cada
        // visita lleva un sesgo propio de 2 m (otra geometria, otra atmosfera). Antes la
        // incertidumbre declarada era 5 veces menor que el error real.
        val n = 200
        fun visitas(k: Int, sesgo: Double): Triple<List<Double>, List<Double?>, List<Int>> {
            val r = Random(5000 + k)
            val v = (0 until 2).flatMap { j ->
                val b = gauss(r) * sesgo
                serieCorrelada(n, 3.0, 0.5, 6000 + 2 * k + j).map { it + b }
            }
            return Triple(v, List(2 * n) { 3.0 }, List(n) { 0 } + List(n) { 1 })
        }
        val conSesgo = calibracion(300) { visitas(it, 2.0) }
        assertTrue(conSesgo in 0.75..1.25, "dos visitas con sesgo diario de 2 m: $conSesgo")
        // Cuando las visitas SI concuerdan el contraste casi no actua: queda algo por debajo
        // de uno, del lado prudente, y no pierde lo que la segunda visita aporta.
        val sinSesgo = calibracion(300) { visitas(it, 0.0) }
        assertTrue(sinSesgo in 0.6..1.1, "dos visitas que concuerdan: $sinSesgo")
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
