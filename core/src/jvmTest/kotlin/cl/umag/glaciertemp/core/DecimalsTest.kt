package cl.umag.glaciertemp.core

import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Decimals` contra `String.format`, que es el original al que sustituye.
 *
 * La regla de la casa es que un reemplazo se valida contra la implementacion de referencia
 * sobre datos de verdad, no contra lo que uno cree que deberia salir. Aqui la referencia es
 * `"%.Nf"` de la JVM, y lo que se mide es CUANTO se separan y DONDE: no se exige igualdad,
 * porque no la hay en los empates exactos, se exige que la diferencia nunca pase de una unidad
 * del ultimo decimal y que los casos que la app escribe de verdad salgan identicos.
 */
class DecimalsTest {

    private val previo: Locale = Locale.getDefault()
    @BeforeTest fun enEspanol() { Locale.setDefault(Locale.forLanguageTag("es-CL")) }
    @AfterTest fun restaura() { Locale.setDefault(previo) }

    private fun ref(v: Double, n: Int) = "%.${n}f".format(Locale.ROOT, v)

    @Test fun `nunca sale una coma, con el aparato en espanol`() {
        // El fallo original, convertido en prueba: aqui no hay idioma que consultar.
        assertEquals("1013,25", "%.2f".format(1013.25), "el entorno tiene que usar coma")
        for (n in 0..8) for (v in listOf(1013.25, -51.5, 0.1, 1234567.891, -0.0004)) {
            val s = Decimals.fixed(v, n)
            assertTrue(',' !in s, "salio una coma: $s")
        }
    }

    @Test fun `los valores que la app escribe de verdad salen identicos a la JVM`() {
        val casos = listOf(
            1013.25 to 2, 1013.25 to 1, -51.512345 to 8, -73.254321 to 8, 412.5 to 1,
            0.9542712 to 4, 0.5 to 4, 22.75 to 2, 5.25 to 2, 55.5 to 2, 3.5 to 1,
            0.0 to 2, -0.0 to 2, 100.0 to 0, 99.995 to 2, 1.0 to 8, 360.0 to 0)
        for ((v, n) in casos)
            assertEquals(ref(v, n), Decimals.fixed(v, n), "fixed($v, $n)")
    }

    @Test fun `el acarreo llega hasta la parte entera`() {
        assertEquals("1.0", Decimals.fixed(0.96, 1))
        assertEquals("10.00", Decimals.fixed(9.999, 2))
        assertEquals("100", Decimals.fixed(99.6, 0))
        assertEquals("1000.0", Decimals.fixed(999.99, 1))
    }

    @Test fun `la notacion cientifica se despliega en vez de colarse en el fichero`() {
        // 1e20.toString() es "1.0E20", y eso dentro de una columna no lo lee nadie.
        assertTrue('E' !in Decimals.fixed(1e20, 2) && 'e' !in Decimals.fixed(1e20, 2))
        assertEquals(ref(1e20, 2), Decimals.fixed(1e20, 2))
        assertEquals("0.00000100", Decimals.fixed(1e-6, 8))
        assertEquals("0.00", Decimals.fixed(1e-9, 2))
        assertEquals("-0.00", Decimals.fixed(-1e-9, 2), "el signo se conserva, como en la JVM")
    }

    @Test fun `trimmed quita los ceros que no dicen nada`() {
        assertEquals("1.5", Decimals.trimmed(1.5, 8))
        assertEquals("2", Decimals.trimmed(2.0, 8))
        assertEquals("-51.512345", Decimals.trimmed(-51.512345, 8))
        assertEquals("0", Decimals.trimmed(0.0, 4))
        assertEquals("0", Decimals.trimmed(-1e-9, 4))
    }

    @Test fun `contra la JVM sobre un millon de valores, la diferencia nunca pasa de un digito`() {
        val r = Random(20260928)
        var distintos = 0; var total = 0; var peor = 0.0; var ejemplo = ""
        for (n in 0..8) {
            repeat(120_000) {
                // Rangos que cubren lo que la app escribe: angulos, coordenadas, presiones,
                // factores entre 0 y 1, y magnitudes grandes por si acaso.
                val v = when (it % 5) {
                    0 -> r.nextDouble(-180.0, 180.0)
                    1 -> r.nextDouble(0.0, 1.0)
                    2 -> r.nextDouble(900.0, 1100.0)
                    3 -> r.nextDouble(-1e6, 1e6)
                    else -> r.nextDouble(-1.0, 1.0) * 10.0.pow(r.nextInt(-8, 9))
                }
                val a = Decimals.fixed(v, n); val b = ref(v, n)
                total++
                if (a != b) {
                    distintos++
                    val d = abs(a.toDouble() - b.toDouble())
                    if (d > peor) { peor = d; ejemplo = "fixed($v,$n) = $a  vs  JVM $b" }
                    assertTrue(d <= 1.0000001 * 10.0.pow(-n),
                               "se separo mas de un digito del ultimo decimal: $ejemplo")
                }
            }
        }
        println("Decimals vs String.format: $distintos de $total difieren " +
                "(${"%.4f".format(Locale.ROOT, 100.0 * distintos / total)} %), " +
                "peor caso $peor" + if (ejemplo.isNotEmpty()) "  [$ejemplo]" else "")
        // La discrepancia es de empates exactos y tiene que ser RARA. Si un cambio la
        // disparase, es que se rompio el redondeo y no que aparecieron mas empates.
        // CERO diferencias. No es una aspiracion: se midio, y por eso se puede exigir.
        assertEquals(0, distintos, "aparecio una diferencia con la JVM: $ejemplo")
    }

    @Test fun `lo escrito se puede volver a leer`() {
        val r = Random(7)
        repeat(50_000) {
            val v = r.nextDouble(-1e5, 1e5)
            val s = Decimals.fixed(v, 8)
            val leido = s.toDoubleOrNull()
            assertTrue(leido != null, "no parsea: '$s'")
            assertTrue(abs(leido!! - v) <= 1e-7, "ida y vuelta perdio el valor: $v -> $s")
        }
    }

    private fun Double.pow(e: Int) = Math.pow(this, e.toDouble())
}
