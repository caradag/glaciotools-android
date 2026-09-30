package cl.umag.glaciertemp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * La descarga HTTP real contra una release de GitHub, que redirige a su CDN: el mismo camino
 * que seguiran las teselas. Se usa un asset que ya existe (el APK de la v2.3); si no hay red,
 * la prueba se omite en vez de fallar.
 */
class GitHubDownloadTest {
    @Test fun sigue_la_redireccion_de_github_y_baja_el_fichero_entero() {
        val b = GeoidStore.httpGet(
            "https://github.com/caradag/glaciotools-android/releases/download/v2.3/glaciotools-2.3-debug.apk")
        assumeTrue("sin red", b != null)
        assertEquals(16_952_807, b!!.size)
        assertEquals('P'.code.toByte(), b[0]); assertEquals('K'.code.toByte(), b[1])   // un zip
    }

    @Test fun un_404_devuelve_null() {
        val ok = GeoidStore.httpGet("https://github.com/caradag/glaciotools-android/releases/download/v2.3/glaciotools-2.3-debug.apk")
        assumeTrue("sin red", ok != null)
        assertNull(GeoidStore.httpGet("https://github.com/caradag/glaciotools-android/releases/download/v2.3/no-existe.bin"))
    }
}
