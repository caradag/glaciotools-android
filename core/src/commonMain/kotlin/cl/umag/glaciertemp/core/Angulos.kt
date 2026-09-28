package cl.umag.glaciertemp.core

import kotlin.math.PI

/**
 * Grados a radianes y al reves, con el MISMO orden de operaciones que `java.lang.Math`.
 *
 * Kotlin comun no trae `toRadians`/`toDegrees`: son de `java.lang.Math`. Se copian aqui en
 * vez de escribir la conversion a ojo en cada sitio, y se copian LITERALMENTE:
 * `Math.toRadians` es `d / 180.0 * PI` --no `d * PI / 180.0`-- y `Math.toDegrees` es
 * `r * 180.0 / PI`. Las dos formas difieren en el ultimo bit del double, y por aqui pasa la
 * proyeccion UTM, que esta contrastada contra PROJ al milimetro: ahi ese bit se ve.
 */
internal fun radianes(grados: Double): Double = grados / 180.0 * PI

internal fun grados(radianes: Double): Double = radianes * 180.0 / PI
