package cl.umag.glaciertemp.core.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Los valores esperados NO salen de este codigo: se calcularon aparte con la formula de
 * skyline.m, y algunos se pueden comprobar a mano. Un test que compara el codigo consigo
 * mismo no prueba nada.
 */
class ShieldingTest {

    private fun plano(gradosDeElevacion: Double) =
        HorizonProfile(DoubleArray(72) { gradosDeElevacion }, 5)

    @Test fun `sin relieve y sin buzamiento no hay apantallamiento`() {
        assertEquals(1.0, Shielding.compute(null).factor, 1e-12)
        assertEquals(1.0, Shielding.compute(plano(0.0)).factor, 1e-9)
    }

    @Test fun `un horizonte plano a 30 grados da lo que dice la formula a mano`() {
        // 1 - sin(30)^3,3 = 1 - 0,5^3,3
        assertEquals(0.898468, Shielding.compute(plano(30.0)).factor, 1e-5)
    }

    @Test fun `un horizonte a 90 grados no deja pasar nada`() {
        assertEquals(0.0, Shielding.compute(plano(90.0)).factor, 1e-9)
    }

    @Test fun `una superficie vertical deja exactamente medio cielo`() {
        // La comprobacion mas fuerte del exponente: un plano vertical tapa un hemisferio
        // entero, y la integral tiene que dar 0,5 clavado sin depender de ningun ajuste.
        assertEquals(0.5, Shielding.compute(null, 0.0, 90.0).factor, 2e-3)
    }

    @Test fun `los buzamientos coinciden con las tablas publicadas`() {
        // Valores de las calculadoras CRONUS / ICE-D para una superficie que buza.
        listOf(10.0 to 0.9994, 20.0 to 0.9939, 30.0 to 0.9774,
               45.0 to 0.9208, 60.0 to 0.8175).forEach { (dip, esperado) ->
            assertEquals(esperado, Shielding.compute(null, 0.0, dip).factor, 1e-3,
                         "buzamiento de $dip grados")
        }
    }

    @Test fun `el rumbo no cambia el resultado si no hay relieve`() {
        // Una superficie aislada que buza 40 grados apantalla lo mismo mire a donde mire.
        val a = Shielding.compute(null, 0.0, 40.0).factor
        val b = Shielding.compute(null, 137.0, 40.0).factor
        assertEquals(a, b, 1e-6)
    }

    @Test fun `el compuesto no es el producto, y falla en LAS DOS direcciones`() {
        // ES LA PARTE QUE NO ES OBVIA del algoritmo, y el error que invita a cometer no
        // tiene un solo signo. Multiplicar los dos factores solo coincidiria con el maximo
        // si los dos horizontes taparan trozos de cielo independientes, y no lo son:
        //
        //   - horizontes que apenas se solapan -> el producto se queda CORTO (apantalla de
        //     menos), porque cada factor ignora lo que tapa el otro;
        //   - horizontes que se solapan mucho  -> el producto se pasa (apantalla de mas),
        //     porque cuenta dos veces el mismo trozo de cielo.
        //
        // Aqui se comprueban los dos casos: no vale con recordar "el producto siempre da
        // mas" ni "siempre da menos".
        val v = DoubleArray(72)
        for (b in 0 until 72) {
            val az = (b + 0.5) * 5.0
            if (az in 45.0..135.0) v[b] = 60.0      // muro al este
        }
        val r = Shielding.compute(HorizonProfile(v, 5), strikeDeg = 0.0, dipDeg = 45.0)
        assertTrue(r.factor < r.fromTerrain * r.fromDip,
                   "el compuesto (${r.factor}) debe apantallar mas que el producto " +
                   "(${r.fromTerrain * r.fromDip})")
        // Y nunca mas que el peor de los dos por separado.
        assertTrue(r.factor <= minOf(r.fromTerrain, r.fromDip) + 1e-9)

        // CASO CONTRARIO: un muro en la MISMA mitad que tapa el buzamiento (el de 45 al este
        // oscurece el oeste). Solapandose casi del todo, el producto cuenta dos veces ese
        // cielo y apantalla DE MAS: 0,81 contra el 0,87 correcto.
        val w = DoubleArray(72)
        for (b in 0 until 72) if ((b + 0.5) * 5.0 > 180.0) w[b] = 40.0
        val r2 = Shielding.compute(HorizonProfile(w, 5), strikeDeg = 0.0, dipDeg = 45.0)
        assertTrue(r2.factor > r2.fromTerrain * r2.fromDip,
                   "con horizontes solapados el producto se pasa: ${r2.factor} vs " +
                   "${r2.fromTerrain * r2.fromDip}")
        assertTrue(r2.factor <= minOf(r2.fromTerrain, r2.fromDip) + 1e-9)
    }

    @Test fun `las dos contribuciones se informan por separado`() {
        val r = Shielding.compute(plano(30.0), 0.0, 45.0)
        assertEquals(0.898468, r.fromTerrain, 1e-5)
        assertEquals(0.9208, r.fromDip, 1e-3)
        // Un buzamiento de 45 tapa mas que un horizonte de 30 en la mitad del contorno, asi
        // que el compuesto queda por debajo del que solo mira al relieve.
        assertTrue(r.factor < r.fromTerrain)
    }

    @Test fun `un horizonte por debajo de la horizontal no aumenta la produccion`() {
        // Desde una cumbre no llega MAS radiacion que desde un llano despejado.
        assertEquals(1.0, Shielding.compute(plano(-25.0)).factor, 1e-9)
    }

    @Test fun `la interpolacion da la vuelta por el norte`() {
        // Dos puntos, en 350 y en 10 grados: lo de en medio pasa por el norte.
        val h = Shielding.interpolate(doubleArrayOf(350.0, 10.0), doubleArrayOf(0.0, 20.0))
        // En el sector 360 (indice 359) se esta a mitad de camino: 10 grados.
        assertEquals(10.0, Math.toDegrees(h[359]), 1.0)
    }

    @Test fun `el horizonte devuelto tiene un valor por grado`() {
        val r = Shielding.compute(plano(12.0))
        assertEquals(360, r.horizonDeg.size)
        assertEquals(12.0, r.horizonDeg[0], 1e-6)
    }
}

class StrikeDipTest {

    private fun matriz(vararg v: Double) = FloatArray(9) { v[it].toFloat() }

    @Test fun `un telefono tumbado en horizontal no da buzamiento`() {
        val R = matriz(1.0, 0.0, 0.0,
                       0.0, 1.0, 0.0,
                       0.0, 0.0, 1.0)
        val (rumbo, buz) = Shielding.strikeDipFrom(R)
        assertEquals(0.0, buz, 1e-6)
        assertEquals(0.0, rumbo, 1e-6)
    }

    @Test fun `una superficie que cae al este da rumbo norte`() {
        // Convenio de skyline.m: rumbo 0 y buzamiento 45 es caer 45 grados hacia el ESTE.
        // La normal se inclina hacia arriba de la pendiente, o sea hacia el oeste.
        val d = Math.toRadians(45.0)
        // Normal inclinada hacia el OESTE (arriba de la pendiente): tercera columna
        // (E, N, U) = (-sin d, 0, cos d).
        val R = matriz(
            Math.cos(d), 0.0, -Math.sin(d),
            0.0, 1.0, 0.0,
            Math.sin(d), 0.0, Math.cos(d))
        val (rumbo, buz) = Shielding.strikeDipFrom(R)
        assertEquals(45.0, buz, 0.5)
        assertEquals(0.0, Angles.wrap(rumbo), 1.0)
    }

    @Test fun `una superficie que cae al norte da rumbo oeste`() {
        // Girando sobre el eje este-oeste la pendiente cae al norte, y el rumbo es 270.
        val d = Math.toRadians(45.0)
        val R = matriz(1.0, 0.0, 0.0,
                       0.0, Math.cos(d), -Math.sin(d),
                       0.0, Math.sin(d), Math.cos(d))
        val (rumbo, buz) = Shielding.strikeDipFrom(R)
        assertEquals(45.0, buz, 0.5)
        assertEquals(270.0, rumbo, 1.0)
    }

    @Test fun `un buzamiento despreciable no inventa un rumbo`() {
        val d = Math.toRadians(0.2)
        val R = matriz(1.0, 0.0, 0.0,
                       0.0, Math.cos(d), -Math.sin(d),
                       0.0, Math.sin(d), Math.cos(d))
        assertEquals(0.0 to 0.0, Shielding.strikeDipFrom(R))
    }
}
