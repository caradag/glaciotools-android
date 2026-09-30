package cl.umag.glaciertemp.app

import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cl.umag.glaciertemp.core.geo.HeightVerdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Settings y la verificacion de la altura del telefono.
 *
 * El emulador no sirve para la verificacion de punta a punta: su GGA pone la altura de
 * `geo fix` como MSL con separacion 0, y con |N| < 2 m la comprobacion no vota (bien). Por
 * eso aqui se le dan a PhoneAltitude posiciones y GGA construidas, con el mismo camino que
 * recorren las reales: parseo, emparejado por hora, votos, veredicto guardado y correccion.
 */
class SettingsFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private var previo: AppSettings.HeightCheckState? = null

    @Before fun guardar() {
        AppSettings.init(rule.activity)
        previo = AppSettings.heightCheck.value
        PhoneAltitude.resetForTest()
    }

    @After fun restaurar() {
        previo?.let { AppSettings.setHeightCheck(it) }
        PhoneAltitude.resetForTest()
    }

    private fun gga(millis: Long, msl: Double, sep: Double): String {
        val s = (millis % 86_400_000L) / 1000
        val hhmmss = "%02d%02d%02d.00".format(s / 3600, s / 60 % 60, s % 60)
        val cuerpo = "GNGGA,$hhmmss,5309.600,S,07054.600,W,1,12,0.7,%.1f,M,%.1f,M,,".format(msl, sep)
        var x = 0
        for (c in cuerpo) x = x xor c.code
        return "$" + cuerpo + "*" + "%02X".format(x)
    }

    private fun loc(millis: Long, alt: Double) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = -53.16; longitude = -70.91; altitude = alt; time = millis
    }

    @Test fun telefono_que_entrega_nivel_del_mar_se_detecta_y_se_corrige() {
        AppSettings.setHeightCheck(AppSettings.HeightCheckState())
        val t0 = 1_790_000_000_000L
        for (i in 0 until 10) {
            val t = t0 + i * 1000L
            PhoneAltitude.onNmea(gga(t, 24.1, 10.2))
            PhoneAltitude.observe(loc(t, 24.1))
        }
        assertEquals(HeightVerdict.MSL, AppSettings.heightCheck.value.verdict)
        // corregida con la separacion de la GGA de su misma epoca
        assertEquals(34.3, PhoneAltitude.ellipsoidal(loc(t0 + 9000L, 24.1))!!, 1e-9)
        // sin GGA de esa epoca no se inventa: queda desconocida
        assertNull(PhoneAltitude.ellipsoidal(loc(t0 + 60_000L, 24.1)))
    }

    @Test fun telefono_que_entrega_elipsoidal_se_verifica_y_se_muestra() {
        AppSettings.setHeightCheck(AppSettings.HeightCheckState())
        val t0 = 1_790_000_000_000L
        for (i in 0 until 10) {
            val t = t0 + i * 1000L
            PhoneAltitude.onNmea(gga(t, 24.1, 10.2))
            PhoneAltitude.observe(loc(t, 34.3))
        }
        assertEquals(HeightVerdict.ELLIPSOIDAL, AppSettings.heightCheck.value.verdict)
        assertEquals(34.3, PhoneAltitude.ellipsoidal(loc(t0, 34.3))!!, 1e-9)

        rule.onNodeWithTag("home-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("st-height-verdict").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("st-height-verdict")
            .assertTextContains("Verified: this phone reports ellipsoidal heights", substring = true)
        rule.onNodeWithTag("st-height-verdict").assertTextContains("10.2 m", substring = true)
    }
}
