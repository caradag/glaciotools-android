package cl.umag.glaciertemp.core

/**
 * El reloj de pared, en milisegundos desde 1970.
 *
 * Es lo unico de `core` que no se puede escribir una sola vez: cada plataforma tiene su
 * llamada. Se declara aqui y cada objetivo pone la suya, que es justo para lo que sirve
 * `expect`/`actual` -- el codigo comun sigue pudiendo pedir la hora sin saber donde corre.
 */
internal expect fun nowMillis(): Long
