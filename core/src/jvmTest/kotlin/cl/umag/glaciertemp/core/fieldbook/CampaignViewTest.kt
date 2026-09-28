package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La regla que decide si archivar una campana vacia la pantalla.
 *
 * Existe porque el defecto que prueba era IMPOSIBLE de reproducir donde vivia: la entrada
 * sin campana es anterior a que las campanas existieran, una instalacion nueva no la tiene,
 * y en el emulador SELinux no deja fabricarla escribiendo en los datos de la app.
 */
class CampaignViewTest {

    private fun entrada(id: String, campana: String?) =
        FieldEntry(id = id, type = EntryType.NOTE, createdEpochMillis = 1L,
                   updatedEpochMillis = 1L, campaignId = campana)

    private fun campana(id: String, archivada: Long? = null) =
        Campaign(id, "", 1L, archivada)

    @Test
    fun `la vista normal muestra la campana abierta y lo que no tiene ninguna`() {
        val a = campana("A")
        val todas = listOf(entrada("1", "A"), entrada("2", null), entrada("3", "B"))
        val vista = CampaignView.inView(todas, viewing = null, active = a)
        assertEquals(listOf("1", "2"), vista.map { it.id })
    }

    @Test
    fun `mirando una archivada solo salen las suyas`() {
        val b = campana("B", archivada = 9L)
        val todas = listOf(entrada("1", "A"), entrada("2", null), entrada("3", "B"))
        val vista = CampaignView.inView(todas, viewing = b, active = null)
        assertEquals(listOf("3"), vista.map { it.id })
    }

    /**
     * EL DEFECTO. Sin sellar, al archivar queda a la vista lo que no tenia campana, y para
     * quien acaba de cerrarla eso es que archivar no ha hecho nada.
     *
     * Esta prueba falla con el codigo anterior: `sellar` no existia y la entrada "2" seguia
     * saliendo con la campana ya archivada y ninguna abierta.
     */
    @Test
    fun `al archivar no queda nada a la vista si lo suelto se sella`() {
        val a = campana("A")
        var todas = listOf(entrada("1", "A"), entrada("2", null))

        // PRIMERO, EL DEFECTO, afirmado explicitamente. Sin esta linea la prueba pasaria
        // igual con el codigo roto, porque el sellado lo hace ella misma unas lineas mas
        // abajo: seria una prueba que no puede fallar, que es lo mismo que no tenerla.
        assertEquals(listOf("2"),
                     CampaignView.inView(todas, viewing = null, active = null).map { it.id },
                     "sin sellar, lo que no tenia campana sigue a la vista tras archivar")

        // Lo que hace archiveActiveCampaign: sellar lo suelto con la campana que se cierra.
        val sueltas = CampaignView.unfiled(todas).map { it.id }.toSet()
        assertEquals(setOf("2"), sueltas)
        todas = todas.map { if (it.id in sueltas) it.copy(campaignId = a.id) else it }

        // Archivada: ya no hay campana abierta.
        val vista = CampaignView.inView(todas, viewing = null, active = null)
        assertTrue(vista.isEmpty(), "quedaron a la vista: ${vista.map { it.id }}")
    }

    /** Y lo sellado sigue estando DENTRO de la campana, no se pierde. */
    @Test
    fun `lo sellado aparece al abrir la campana archivada`() {
        val a = campana("A", archivada = 9L)
        val todas = listOf(entrada("1", "A"), entrada("2", "A"))
        assertEquals(listOf("1", "2"),
                     CampaignView.inView(todas, viewing = a, active = null).map { it.id })
    }
}
