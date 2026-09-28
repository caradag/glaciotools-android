package cl.umag.glaciertemp.core.geo

import cl.umag.glaciertemp.core.GeoFix
import cl.umag.glaciertemp.core.fieldbook.FieldPosition
import cl.umag.glaciertemp.core.fieldbook.PositionSource

/**
 * La solucion de un punto de GPS tools, lista para usarla como coordenada en otro sitio.
 *
 * Existe porque hay ya dos consumidores --la libreta de terreno y la posicion de una descarga--
 * y las dos conversiones son la misma cuenta. Escrita dos veces, el dia que alguien cambie de
 * que campo sale la exactitud, la cambia en uno.
 *
 * LA EXACTITUD QUE SE PROPAGA ES EL ERROR ESTANDAR DE LA ESTIMACION, no la dispersion de las
 * muestras. Son cosas distintas y la confusion favorece siempre al mismo lado: la dispersion
 * describe lo que hace el receptor y el error estandar lo que se sabe del sitio despues de
 * promediar, que es lo unico que interesa de un punto ya medido. Propagar la dispersion haria
 * parecer peor una posicion promediada durante una hora que una lectura suelta.
 */
object SavedPoint {

    /** Un punto resuelto: donde esta, con que incertidumbre y de cuando es. */
    data class Solution(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        /** Sobre el elipsoide WGS84, o null si ninguna muestra la trajo. */
        val altitudeMetres: Double?,
        /** Error estandar horizontal de la estimacion, en metros. */
        val standardErrorMetres: Double?,
        val samples: Int,
        val sessions: Int,
        val lastEpochMillis: Long,
    )

    /**
     * Resuelve un punto guardado, o null si no se puede leer o no tiene ninguna muestra.
     *
     * Un punto sin muestras NO es un punto en el origen: es un punto que se creo y del que
     * nunca se llego a medir nada. Devolver null obliga a quien llama a decirlo en vez de
     * ofrecer una coordenada inventada.
     */
    fun solve(store: GpsPointStore, id: String): Solution? {
        val p = store.load(id) ?: return null
        val st = GpsAverager().apply { addAll(p.samples) }.stats() ?: return null
        return Solution(
            id = p.header.id,
            name = p.header.name.ifBlank { "Point" },
            latitude = st.estimateLatitude,
            longitude = st.estimateLongitude,
            altitudeMetres = st.altitude?.estimate,
            standardErrorMetres = st.horizontalStandardError,
            samples = st.samples,
            sessions = st.sessions,
            lastEpochMillis = st.lastEpochMillis,
        )
    }

    /** Todos los puntos que tienen solucion, el mas reciente primero. */
    fun solutions(store: GpsPointStore): List<Solution> =
        store.list().mapNotNull { solve(store, it.id) }

    /**
     * Como posicion de una descarga.
     *
     * [GeoFix.ageSeconds] queda en CERO y el origen se dice en [GeoFix.sourceLabel]. La
     * antiguedad de un punto promediado no significa lo que significa la de un arreglo
     * oportunista: un arreglo de hace tres horas puede ser de otro sitio, mientras que un
     * punto describe un lugar, que no se mueve. Escribir "fix 3 days old" en el CSV de una
     * coordenada deliberadamente elegida sugeriria un defecto donde no lo hay.
     */
    fun Solution.toGeoFix(): GeoFix = GeoFix(
        latitude = latitude,
        longitude = longitude,
        altitudeMetres = altitudeMetres,
        accuracyMetres = standardErrorMetres,
        ageSeconds = 0,
        sourceLabel = "saved point “$name” ($samples fixes)",
    )

    /** Como coordenada de una entrada de libreta. */
    fun Solution.toFieldPosition(): FieldPosition = FieldPosition(
        latitude = latitude,
        longitude = longitude,
        altitudeMetres = altitudeMetres,
        accuracyMetres = standardErrorMetres,
        source = PositionSource.SAVED_POINT,
        pointName = name,
        pointId = id,
        atEpochMillis = lastEpochMillis,
    )
}
