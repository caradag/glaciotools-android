package cl.umag.glaciertemp.core.fieldbook

/**
 * Que entradas pertenecen a que campana. Reglas puras, sin Android.
 *
 * POR QUE ESTAN AQUI Y NO EN EL ViewModel. Vivian como funciones privadas de
 * FieldbookViewModel, que necesita Android para instanciarse: no habia forma de probarlas
 * sin emulador, y en el emulador no hay forma de fabricar el caso que importa --una entrada
 * anterior a que las campanas existieran-- porque SELinux no deja escribir en los datos de
 * la app. O sea que la regla que decide si archivar vacia la pantalla era, en la practica,
 * imposible de comprobar. Movida aqui se prueba en la JVM en milisegundos.
 */
object CampaignView {

    /**
     * Lo que la lista principal debe mostrar.
     *
     * Mirando una archivada: solo las suyas. Si no: las de la campana abierta Y **las que no
     * tienen ninguna**. Estas ultimas son las anotadas antes de que existieran las campanas;
     * dejarlas fuera las haria desaparecer sin que nadie las hubiera archivado, que es
     * perder datos a los ojos del usuario aunque el fichero siga en disco.
     */
    fun inView(all: List<FieldEntry>, viewing: Campaign?, active: Campaign?): List<FieldEntry> =
        if (viewing != null) all.filter { it.campaignId == viewing.id }
        else all.filter { it.campaignId == null || it.campaignId == active?.id }

    /**
     * Las entradas sin campana, que al archivar hay que sellar con la que se cierra.
     *
     * EL DEFECTO QUE ARREGLA. `inView` deja pasar siempre las de campaignId nulo, asi que
     * archivar la campana abierta se llevaba sus entradas de la pantalla pero NO estas: se
     * quedaban a la vista para siempre. Para quien acaba de cerrar la campana, eso es que
     * archivar no ha hecho nada.
     *
     * Se sellan con la campana que se cierra, que es lo que significan: estuvieron a la
     * vista durante toda ella y son parte de ese trabajo de terreno.
     */
    fun unfiled(all: List<FieldEntry>): List<FieldEntry> = all.filter { it.campaignId == null }
}
