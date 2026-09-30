package cl.umag.glaciertemp.app

import androidx.test.platform.app.InstrumentationRegistry
import cl.umag.glaciertemp.core.geo.geoid.GeoidModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Descarga de teselas SIN RED: el "servidor" lee de los assets de la prueba. Se sirve el
 * manifiesto con dos teselas, la de Punta Arenas intacta y una vecina CORRUPTA, y se
 * comprueba el recorrido entero: huella, escritura atomica, que la mala no llega al disco,
 * que lo bueno no se borra, y que despues Geoids da la N de EGM2008 (GeoidEval: 10,1511).
 */
class GeoidDownloadTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var root: File
    private val pedidas = mutableListOf<String>()

    private fun servidor(url: String): ByteArray? {
        val nombre = url.substringAfterLast('/')
        pedidas += nombre
        // aapt no conserva los assets terminados en .gz: la tesela va como .gz.bin
        val b = runCatching { assets.open("geoid-test/$nombre").use { it.readBytes() } }.getOrNull()
            ?: runCatching { assets.open("geoid-test/$nombre.bin").use { it.readBytes() } }.getOrNull()
        // la vecina existe en el manifiesto pero llega estropeada
        if (nombre == "egm2008-1m-S60W070.gtg.gz") return ByteArray(1000) { 7 }
        return b
    }

    @Before fun preparar() {
        Geoids.init(ctx)
        root = File(ctx.cacheDir, "geoid-test").apply { deleteRecursively(); mkdirs() }
        Geoids.store = GeoidStore(root, ::servidor, "https://example.invalid/")
    }

    @After fun limpiar() {
        root.deleteRecursively()
        Geoids.store = null
        Geoids.invalidate(GeoidModel.EGM2008)
    }

    @Test fun baja_verifica_y_usa_la_tesela() {
        val store = Geoids.store!!
        assertEquals(404222L + 382969L, store.pendingBytes(GeoidModel.EGM2008, -53.16, -70.91))

        val r = store.downloadArea(GeoidModel.EGM2008, -53.16, -70.91)!!
        assertEquals("resultado $r", 1, r.downloaded)
        assertEquals(1, r.failed)                // la corrupta, rechazada por su SHA-256
        assertTrue(File(root, "egm2008/egm2008-1m-S60W080.gtg.gz").isFile)
        assertFalse(File(root, "egm2008/egm2008-1m-S60W070.gtg.gz").exists())
        assertTrue(root.walk().none { it.name.endsWith(".tmp") })
        // las 7 teselas de la zona que no estan en el manifiesto no se piden
        assertEquals(setOf("egm2008-1m-manifest.json", "egm2008-1m-S60W080.gtg.gz",
                           "egm2008-1m-S60W070.gtg.gz"), pedidas.toSet())

        // y la tesela bajada sirve: la N de EGM2008 en Punta Arenas
        assertTrue(Geoids.available(GeoidModel.EGM2008, -53.16, -70.91))
        assertEquals(10.1511, Geoids.undulation(GeoidModel.EGM2008, -53.16, -70.91)!!, 1e-4)

        // Segunda vez: lo que ya esta no se vuelve a bajar
        val r2 = store.downloadArea(GeoidModel.EGM2008, -53.16, -70.91)!!
        assertEquals(1, r2.alreadyThere)
        assertEquals(0, r2.downloaded)

        // Borrar: el modelo deja de estar disponible
        store.delete(GeoidModel.EGM2008)
        assertFalse(Geoids.available(GeoidModel.EGM2008, -53.16, -70.91))
        assertNull(Geoids.undulation(GeoidModel.EGM2008, -53.16, -70.91))
    }
}
