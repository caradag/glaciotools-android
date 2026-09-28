package cl.umag.glaciertemp.core.geo

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsPointPhotosTest {

    private val dir = File(System.getProperty("java.io.tmpdir"),
                           "gpspoints-" + System.nanoTime())
    private val store = GpsPointStore(dir)

    @AfterTest fun limpia() { dir.deleteRecursively() }

    private fun muestra(t: Long) = GpsSample(t, -51.0, -73.0, sessionStartMillis = t)

    @Test fun `las fotos sobreviven al ida y vuelta de la cabecera`() {
        val id = store.create("P1")
        store.append(id, listOf(muestra(1000), muestra(2000)))
        assertTrue(store.setPhotos(id, listOf("a.jpg", "b.jpg")))
        assertEquals(listOf("a.jpg", "b.jpg"), store.photos(id))
    }

    @Test fun `anadir fotos NO pierde las muestras`() {
        // La cabecera se reescribe entera al cambiarla: es justo donde se pierden los datos
        // si el reescrito se hace mal.
        val id = store.create("P1")
        store.append(id, listOf(muestra(1000), muestra(2000), muestra(3000)))
        store.setPhotos(id, listOf("a.jpg"))
        assertEquals(3, store.load(id)!!.samples.size)
    }

    @Test fun `seguir anadiendo muestras despues de poner fotos funciona`() {
        val id = store.create("P1")
        store.append(id, listOf(muestra(1000)))
        store.setPhotos(id, listOf("a.jpg"))
        store.append(id, listOf(muestra(2000)))
        assertEquals(2, store.load(id)!!.samples.size)
        assertEquals(listOf("a.jpg"), store.photos(id))
    }

    @Test fun `un punto sin fotos no inventa ninguna`() {
        val id = store.create("P1")
        assertTrue(store.photos(id).isEmpty())
    }

    @Test fun `borrar el punto se lleva sus fotos`() {
        // Si no, quedarian en la carpeta sin nada que las nombre.
        val id = store.create("P1")
        val f = store.newMediaFile("jpg").apply { writeText("x") }
        store.setPhotos(id, listOf(f.name))
        assertTrue(f.exists())
        store.delete(id)
        assertFalse(f.exists(), "la foto deberia irse con el punto")
    }

    @Test fun `un fichero de una version anterior se lee sin fotos y sin romperse`() {
        // Compatibilidad hacia atras: los puntos ya medidos no tienen la clave photo.
        val id = store.create("P1")
        store.append(id, listOf(muestra(1000)))
        val texto = File(dir, "$id.gpspoint").readText()
        assertFalse(texto.contains("photo="), "un punto sin fotos no escribe la clave")
        assertEquals(1, GpsPointFile.parse(texto)!!.samples.size)
        assertTrue(GpsPointFile.parse(texto)!!.header.photos.isEmpty())
    }
}
