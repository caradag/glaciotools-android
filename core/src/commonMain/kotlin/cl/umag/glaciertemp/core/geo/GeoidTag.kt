package cl.umag.glaciertemp.core.geo

/** El geoide aplicado a una exportacion: su nombre y la N usada, en metros. */
data class GeoidTag(val model: String, val undulation: Double)
