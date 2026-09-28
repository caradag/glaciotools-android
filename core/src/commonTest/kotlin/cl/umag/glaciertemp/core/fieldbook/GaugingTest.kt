package cl.umag.glaciertemp.core.fieldbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La aritmetica del aforo.
 *
 * Lo que se vigila aqui no es que el codigo corra: es que el numero que sale sea el caudal.
 * Un error en esta clase no da un fallo, da un caudal equivocado y perfectamente creible, y
 * nadie vuelve al rio a comprobarlo. Por eso los casos de referencia son canales de geometria
 * conocida, donde el resultado se sabe de antemano sin usar este codigo para calcularlo.
 */
class GaugingTest {

    @Test fun `un perfil que cabe justo da tramos enteros`() {
        val t = Gauging.bins(7.0, 0.2)
        assertEquals(35, t.size, "7 m en tramos de 20 cm son 35 tramos")
        assertEquals(0.0, t.first().startM, 1e-9)
        assertEquals(0.1, t.first().centreM, 1e-9, "el primer centro esta a I/2")
        assertEquals(7.0, t.last().endM, 1e-9, "el ultimo tramo acaba en la orilla")
        assertEquals(6.9, t.last().centreM, 1e-9)
        t.forEach { assertEquals(0.2, it.widthM, 1e-9) }
    }

    @Test fun `el ultimo tramo puede ser mas corto y su centro es el suyo`() {
        // EL CASO QUE MAS FACIL SE HACE MAL. Con 7,1 m el ultimo tramo mide 10 cm, no 20, y
        // su centro esta a 7,05 y no a 7,10. Darle el centro nominal pondria la medida fuera
        // del agua; darle el ancho nominal contaria 10 cm de rio que no existen.
        val t = Gauging.bins(7.1, 0.2)
        assertEquals(36, t.size)
        assertEquals(7.0, t.last().startM, 1e-9)
        assertEquals(7.1, t.last().endM, 1e-9)
        assertEquals(0.1, t.last().widthM, 1e-9, "el ultimo tramo mide lo que queda")
        assertEquals(7.05, t.last().centreM, 1e-9, "su centro es el suyo, no el nominal")
    }

    @Test fun `los tramos cubren el ancho exactamente y sin solaparse`() {
        for ((ancho, paso) in listOf(7.0 to 0.2, 7.1 to 0.2, 1.0 to 0.3, 12.34 to 0.5)) {
            val t = Gauging.bins(ancho, paso)
            assertEquals(0.0, t.first().startM, 1e-12, "empieza en la orilla")
            assertEquals(ancho, t.last().endM, 1e-12, "acaba en la otra orilla")
            for (i in 1 until t.size)
                assertEquals(t[i - 1].endM, t[i].startM, 1e-12, "hay un hueco o un solape")
            assertEquals(ancho, t.sumOf { it.widthM }, 1e-9,
                         "los anchos tienen que sumar el ancho del rio")
        }
    }

    @Test fun `un ancho o un intervalo imposibles no generan tabla`() {
        assertTrue(Gauging.bins(null, 0.2).isEmpty())
        assertTrue(Gauging.bins(7.0, null).isEmpty())
        assertTrue(Gauging.bins(0.0, 0.2).isEmpty())
        assertTrue(Gauging.bins(-7.0, 0.2).isEmpty())
        assertTrue(Gauging.bins(7.0, 0.0).isEmpty())
        assertTrue(Gauging.bins(Double.NaN, 0.2).isEmpty())
        // Un dedo: 2 cm donde iban 20. Se corta, y binCount sigue diciendo cuantos serian
        // para poder explicar por que no hay tabla.
        assertTrue(Gauging.bins(70.0, 0.02).isEmpty(), "3500 tramos no se generan")
        assertEquals(3500, Gauging.binCount(70.0, 0.02))
    }

    @Test fun `la profundidad de medida es el 60 por ciento desde arriba`() {
        assertEquals(0.6, Gauging.measurementDepthM(1.0, fromBed = false)!!, 1e-9)
        assertEquals(0.4, Gauging.measurementDepthM(1.0, fromBed = true)!!, 1e-9)
        assertEquals(0.27, Gauging.measurementDepthM(0.45, fromBed = false)!!, 1e-9)
        // Las dos formas describen EL MISMO punto: tienen que sumar la profundidad.
        for (d in listOf(0.1, 0.45, 1.0, 2.37)) {
            val arriba = Gauging.measurementDepthM(d, false)!!
            val abajo = Gauging.measurementDepthM(d, true)!!
            assertEquals(d, arriba + abajo, 1e-9, "no son el mismo punto medido al reves")
        }
        assertNull(Gauging.measurementDepthM(null, false))
        assertNull(Gauging.measurementDepthM(0.0, false), "sin agua no hay donde medir")
    }

    @Test fun `un canal rectangular da el caudal que dice el libro`() {
        // REFERENCIA INDEPENDIENTE: 7 m de ancho, 1 m de calado, 1 m/s en todas partes son
        // 7 m3/s. No hace falta este codigo para saberlo, que es justo lo que lo hace valido
        // como comprobacion.
        val g = StreamGauging(widthM = 7.0, intervalM = 0.2,
                              bins = List(35) { GaugingBin(depthM = 1.0, velocityMps = 1.0) })
        val r = Gauging.summarize(g)
        assertEquals(35, r.binCount)
        assertTrue(r.isComplete)
        assertEquals(7.0, r.dischargeM3s!!, 1e-9)
        assertEquals(7.0, r.areaM2!!, 1e-9)
        assertEquals(1.0, r.meanVelocityMps!!, 1e-12)
        assertEquals(1.0, r.maxVelocityMps!!, 1e-12)
    }

    @Test fun `un canal triangular da la mitad del rectangular`() {
        // Segunda referencia con respuesta conocida: un fondo en V de 2 m de calado maximo
        // sobre 10 m de ancho tiene la mitad del area del rectangulo que lo contiene. Con
        // velocidad constante, el caudal tambien es la mitad. El area discretizada no da
        // exactamente 10 --son trapecios-- pero el error tiene que ser del orden del tramo.
        val n = 100
        val ancho = 10.0
        val paso = ancho / n
        val t = Gauging.bins(ancho, paso)
        val bins = t.map { b ->
            val x = b.centreM
            val calado = 2.0 * (1.0 - kotlin.math.abs(x - ancho / 2) / (ancho / 2))
            GaugingBin(depthM = calado, velocityMps = 0.5)
        }
        val r = Gauging.summarize(StreamGauging(ancho, paso, bins = bins))
        assertEquals(10.0, r.areaM2!!, 0.05, "area del triangulo")
        assertEquals(5.0, r.dischargeM3s!!, 0.03, "la mitad del rectangular")
    }

    @Test fun `la velocidad cambia el caudal tramo a tramo y no se promedia antes`() {
        // Dos tramos de 1 m2 cada uno, uno a 2 m/s y otro a 0 m/s: 2 m3/s. Si el codigo
        // promediara las velocidades antes de multiplicar saldria lo mismo AQUI, asi que se
        // usan areas distintas, donde promediar primero da 3 y lo correcto es 4.
        val g = StreamGauging(widthM = 2.0, intervalM = 1.0, bins = listOf(
            GaugingBin(depthM = 1.0, velocityMps = 1.0),   // 1 m2 * 1 = 1
            GaugingBin(depthM = 3.0, velocityMps = 1.0)))  // 3 m2 * 1 = 3
        assertEquals(4.0, Gauging.summarize(g).dischargeM3s!!, 1e-9)
    }

    @Test fun `un tramo a medias no aporta un cero disfrazado de dato`() {
        // EL FALLO QUE ESTO IMPIDE: contar los tramos sin medir como caudal cero da un total
        // que parece terminado estando a medias, y en terreno ese numero se apunta.
        val g = StreamGauging(widthM = 3.0, intervalM = 1.0, bins = listOf(
            GaugingBin(depthM = 1.0, velocityMps = 2.0),
            GaugingBin(depthM = 1.0),                    // falta la velocidad
            GaugingBin(velocityMps = 2.0)))              // falta la profundidad
        val r = Gauging.summarize(g)
        assertEquals(3, r.binCount)
        assertEquals(1, r.completeBins)
        assertTrue(!r.isComplete, "no puede darse por terminado")
        assertEquals(2.0, r.dischargeM3s!!, 1e-9, "solo suma el tramo completo")
        assertEquals(2, r.binsWithDepth)
        assertEquals(2, r.binsWithVelocity)
        assertEquals(2.0, r.areaM2!!, 1e-9, "el area si cuenta los dos con profundidad")
    }

    @Test fun `sin ningun dato no hay numeros inventados`() {
        val r = Gauging.summarize(StreamGauging(widthM = 3.0, intervalM = 1.0))
        assertEquals(3, r.binCount)
        assertNull(r.dischargeM3s, "null y no cero")
        assertNull(r.areaM2)
        assertNull(r.meanVelocityMps)
        assertNull(r.maxVelocityMps)
        assertTrue(r.depthTimes.isEmpty)
    }

    @Test fun `una velocidad negativa se respeta porque un remolino existe`() {
        val g = StreamGauging(widthM = 2.0, intervalM = 1.0, bins = listOf(
            GaugingBin(depthM = 1.0, velocityMps = 2.0),
            GaugingBin(depthM = 1.0, velocityMps = -0.5)))
        assertEquals(1.5, Gauging.summarize(g).dischargeM3s!!, 1e-9,
                     "el contraflujo resta, que es lo que hace")
    }

    @Test fun `las horas dan principio fin y mediana`() {
        val g = StreamGauging(widthM = 5.0, intervalM = 1.0, bins = listOf(
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 1000L),
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 5000L),
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 2000L),
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 9000L),
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 3000L)))
        val t = Gauging.summarize(g).depthTimes
        assertEquals(1000L, t.firstMillis)
        assertEquals(9000L, t.lastMillis)
        assertEquals(3000L, t.medianMillis, "la mediana, no la media (que seria 4000)")
    }

    @Test fun `con un numero par de medidas la mediana es la media de las dos centrales`() {
        val g = StreamGauging(widthM = 4.0, intervalM = 1.0, bins = listOf(
            GaugingBin(velocityMps = 1.0, velocityFirstEditMillis = 100L),
            GaugingBin(velocityMps = 1.0, velocityFirstEditMillis = 200L),
            GaugingBin(velocityMps = 1.0, velocityFirstEditMillis = 400L),
            GaugingBin(velocityMps = 1.0, velocityFirstEditMillis = 800L)))
        assertEquals(300L, Gauging.summarize(g).velocityTimes.medianMillis)
    }

    @Test fun `la mediana usa la primera edicion y no la correccion`() {
        // Corregir un dato a las 11:40 no mueve la hora a la que se midio el rio.
        val g = StreamGauging(widthM = 3.0, intervalM = 1.0, bins = listOf(
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 100L, depthLastEditMillis = 100L),
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 200L, depthLastEditMillis = 200L),
            GaugingBin(depthM = 1.0, depthFirstEditMillis = 300L,
                       depthLastEditMillis = 99_000L)))
        val t = Gauging.summarize(g).depthTimes
        assertEquals(300L, t.lastMillis, "la correccion tardia no alarga el aforo")
        assertEquals(200L, t.medianMillis)
    }

    @Test fun `cambiar el ancho no tira lo ya medido`() {
        val medidos = listOf(GaugingBin(depthM = 1.0), GaugingBin(depthM = 2.0),
                             GaugingBin(depthM = 3.0))
        assertEquals(listOf(1.0, 2.0, 3.0, null),
                     Gauging.resize(medidos, 4).map { it.depthM })
        assertEquals(listOf(1.0, 2.0), Gauging.resize(medidos, 2).map { it.depthM })
        assertTrue(Gauging.resize(medidos, 0).isEmpty())
        // Y se sabe cuanto se perderia ANTES de perderlo.
        assertEquals(0, Gauging.wouldLose(medidos, 3))
        assertEquals(1, Gauging.wouldLose(medidos, 2))
        assertEquals(3, Gauging.wouldLose(medidos, 0))
        assertEquals(0, Gauging.wouldLose(listOf(GaugingBin(), GaugingBin()), 0),
                     "tramos vacios no se pierden porque no habia nada")
    }

    @Test fun `teclear un numero no cuenta como haberlo corregido despues`() {
        // EL FALLO QUE ESTO ARREGLA, visto ejecutando la app: la casilla se guarda en cada
        // PULSACION, asi que escribir "0.85" deja la primera y la ultima edicion separadas
        // por un segundo. Comparandolas a secas, toda casilla de mas de un digito parecia
        // corregida y el aviso saltaba siempre, que es lo mismo que no tenerlo.
        assertTrue(!Gauging.wasCorrected(1_000L, 1_000L), "una sola pulsacion")
        assertTrue(!Gauging.wasCorrected(1_000L, 3_500L), "tecleado del tiron")
        assertTrue(!Gauging.wasCorrected(1_000L, 31_000L), "justo en el limite, no")
        assertTrue(Gauging.wasCorrected(1_000L, 31_001L), "pasado el limite, si")
        assertTrue(Gauging.wasCorrected(1_000L, 1_000L + 30 * 60_000L),
                   "media hora despues es una correccion de verdad")
        assertTrue(!Gauging.wasCorrected(null, null), "una casilla vacia no se corrigio")
        assertTrue(!Gauging.wasCorrected(1_000L, null))
    }

    @Test fun `el color se normaliza contra lo medido en este perfil`() {
        assertEquals(0.0, Gauging.velocityFraction(0.2, 0.2, 1.4)!!, 1e-9)
        assertEquals(1.0, Gauging.velocityFraction(1.4, 0.2, 1.4)!!, 1e-9)
        assertEquals(0.5, Gauging.velocityFraction(0.8, 0.2, 1.4)!!, 1e-9)
        // Todas iguales: el medio, no una division por cero.
        assertEquals(0.5, Gauging.velocityFraction(1.0, 1.0, 1.0)!!, 1e-9)
        assertNull(Gauging.velocityFraction(null, 0.0, 1.0))

        val g = StreamGauging(widthM = 2.0, intervalM = 1.0, bins = listOf(
            GaugingBin(velocityMps = 0.3), GaugingBin(velocityMps = 1.7)))
        assertEquals(0.3 to 1.7, Gauging.velocityRange(g))
        assertNull(Gauging.velocityRange(StreamGauging(widthM = 2.0, intervalM = 1.0)))
    }
}
