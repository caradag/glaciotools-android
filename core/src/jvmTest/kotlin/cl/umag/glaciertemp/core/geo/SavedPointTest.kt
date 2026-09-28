package cl.umag.glaciertemp.core.geo

import cl.umag.glaciertemp.core.ClockReference
import cl.umag.glaciertemp.core.DownloadMetadata
import cl.umag.glaciertemp.core.GeoFix
import cl.umag.glaciertemp.core.fieldbook.PositionSource
import cl.umag.glaciertemp.core.geo.SavedPoint.toFieldPosition
import cl.umag.glaciertemp.core.geo.SavedPoint.toGeoFix
import java.io.File
import java.time.LocalDateTime
import kotlin.test.*

class SavedPointTest {

    private val T = 1_700_000_000_000L
    private lateinit var dir: File
    private lateinit var store: GpsPointStore

    @BeforeTest
    fun preparar() {
        dir = File(System.getProperty("java.io.tmpdir"),
                   "savedpoint-${System.nanoTime()}").apply { mkdirs() }
        store = GpsPointStore(dir)
    }

    @AfterTest
    fun limpiar() { dir.deleteRecursively() }

    private fun muestras(n: Int) = (0 until n).map {
        GpsSample(T + it * 1000L, -53.1638 + it * 1e-7, -70.9171, 120.0, 4.0, 8.0,
                  sessionStartMillis = T)
    }

    @Test
    fun `un punto medido se resuelve con su nombre, su altura y su error estandar`() {
        val id = store.create("Estaca 3", T)
        store.append(id, muestras(30))

        val s = assertNotNull(SavedPoint.solve(store, id))
        assertEquals("Estaca 3", s.name)
        assertEquals(30, s.samples)
        assertEquals(-70.9171, s.longitude, 1e-6)
        assertNotNull(s.altitudeMetres)
        assertNotNull(s.standardErrorMetres)
    }

    /**
     * Un punto creado y nunca medido NO es un punto en el origen. Sin esto, elegirlo en la
     * libreta escribiria una coordenada inventada con aspecto de medida.
     */
    @Test
    fun `un punto sin muestras no tiene solucion`() {
        val id = store.create("Vacio", T)
        assertNull(SavedPoint.solve(store, id))
        assertEquals(emptyList(), SavedPoint.solutions(store).map { it.name })
    }

    @Test
    fun `como coordenada de libreta queda marcada como punto guardado`() {
        val id = store.create("BASE1", T)
        store.append(id, muestras(10))
        val p = assertNotNull(SavedPoint.solve(store, id)).toFieldPosition()

        assertEquals(PositionSource.SAVED_POINT, p.source)
        assertEquals("BASE1", p.pointName)
        assertEquals(id, p.pointId)
        assertTrue("saved point “BASE1”" in p.detail(), p.detail())
    }

    /**
     * Lo que distingue una coordenada elegida de un arreglo oportunista viejo: la descarga
     * dice DE DONDE salio en vez de cuantos dias tiene. La antiguedad de un punto promediado
     * no lo desacredita -- situa un sitio, que no se mueve.
     */
    @Test
    fun `como posicion de descarga dice el punto y no la antiguedad`() {
        val id = store.create("Estaca 3", T)
        store.append(id, muestras(30))
        val fix = assertNotNull(SavedPoint.solve(store, id)).toGeoFix()
        val meta = DownloadMetadata(LocalDateTime.of(2026, 1, 1, 12, 0), position = fix)

        val detalle = assertNotNull(meta.positionDetail())
        assertTrue("saved point “Estaca 3”" in detalle, detalle)
        assertFalse("old" in detalle, "no debe hablar de antiguedad: $detalle")
    }

    @Test
    fun `una posicion del telefono sigue mostrando su antiguedad`() {
        val meta = DownloadMetadata(
            LocalDateTime.of(2026, 1, 1, 12, 0),
            position = GeoFix(-53.16, -70.91, accuracyMetres = 8.0, ageSeconds = 600),
            reference = ClockReference.GPS)
        val detalle = assertNotNull(meta.positionDetail())
        assertTrue("old" in detalle, detalle)
    }

    /**
     * Un telefono en espanol escribe la coma decimal con `%.5f` sin Locale, y esa linea va a
     * la cabecera del CSV, donde la coma ya significa otra cosa.
     */
    @Test
    fun `las coordenadas se escriben con punto aunque el idioma use coma`() {
        val antes = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-CL"))
            val meta = DownloadMetadata(
                LocalDateTime.of(2026, 1, 1, 12, 0),
                position = GeoFix(-53.16385, -70.91712))
            assertEquals("-53.16385, -70.91712", meta.positionDescription())
        } finally {
            java.util.Locale.setDefault(antes)
        }
    }
}
