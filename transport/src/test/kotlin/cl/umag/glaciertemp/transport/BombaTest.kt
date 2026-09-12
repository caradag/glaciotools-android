package cl.umag.glaciertemp.transport

import java.io.File
import kotlin.test.*

/**
 * El enlace lo lee UN solo hilo, siempre.
 *
 * Antes cada operacion leia el puerto por su cuenta, y el terminal --que quiere verlo todo--
 * tenia que competir por esas mismas lecturas. Arbitrar esa competencia con cerrojos no la
 * resolvia: la volvia intermitente, y la salida que la placa produce por su cuenta aparecia
 * tarde o no aparecia.
 *
 * Con un solo lector, lo que llega esta siempre recogido. Estas pruebas fijan las tres cosas
 * de las que depende el resto: que se recoge lo no solicitado, que una operacion recibe SU
 * respuesta entera, y que lo que quedo de la operacion anterior no se cuela en la siguiente.
 */
class BombaTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir"))
        while (!File(d, "tools/fake_glaciertemp.py").exists()) d = d.parentFile ?: break
        return d
    }

    private fun <T> withSimulator(vararg extraArgs: String, body: (Transport) -> T): T {
        val script = File(repoRoot(), "tools/fake_glaciertemp.py")
        val proc = ProcessBuilder(
            listOf("python3", "-u", script.path, "--stdio") + extraArgs
        ).start()
        try {
            return PipeTransport(proc.inputStream, proc.outputStream).use { t ->
                t.open(); body(t)
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun `recoge lo que la placa dice sin que nadie se lo pida`() =
        withSimulator("--log-size", "20") { t ->
            val s = DeviceSession(t)
            // El banner del arranque no lo pide nadie: lo emite la placa al conectar, igual
            // que el bloque completo que sale al pulsar RESET. Nadie llama a ningun comando.
            val visto = StringBuilder()
            repeat(40) {
                visto.append(String(s.leerFlujo(100), Charsets.ISO_8859_1))
                if (visto.contains("Waiting commands")) return@repeat
            }
            assertTrue(visto.contains("GlacierTemp"),
                       "no se recogio el banner sin preguntar: <$visto>")
            s.cerrar()
        }

    @Test
    fun `una operacion recibe su respuesta entera, muchas veces seguidas`() =
        withSimulator("--log-size", "137") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            repeat(8) { vuelta ->
                val info = assertNotNull(s.info(), "INFO vacio en la vuelta $vuelta")
                assertEquals(137L, info.recordCount, "INFO truncado en la vuelta $vuelta")
                val ver = String(s.exchange(cl.umag.glaciertemp.core.Protocol.VERSION))
                assertTrue(ver.contains("proto="), "VER truncado en la vuelta $vuelta: <$ver>")
            }
            s.cerrar()
        }

    @Test
    fun `lo que quedo de la operacion anterior no se cuela en la siguiente`() =
        withSimulator("--log-size", "200") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            s.download(info)

            // La descarga termina al llegar el ultimo bloque, sin esperar al "LOGB end", asi
            // que ese texto queda en el buffer. Mandar un comando tiene que descartarlo.
            val ver = String(s.exchange(cl.umag.glaciertemp.core.Protocol.VERSION))
            assertFalse(ver.contains("LOGB"), "la respuesta trae cola del volcado: <$ver>")
            assertTrue(ver.contains("proto="))
            s.cerrar()
        }

    @Test
    fun `la descarga no pierde un solo byte`() =
        withSimulator("--log-size", "1500") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val info = assertNotNull(s.info())
            val payload = s.download(info)
            assertEquals(info.recordCount * info.recordBytes, payload.size.toLong(),
                         "la bomba entrego menos bytes de los que la placa mando")
            s.cerrar()
        }

    /**
     * La cadena COMPLETA que alimenta el terminal, sin que nadie mande nada.
     *
     * Es el fallo que se reporto: tras pulsar RESET, la placa escupe su bloque de arranque y
     * en la pantalla no salia nada hasta que el usuario escribia un comando cualquiera, y
     * entonces salia todo junto delante de la respuesta. La bomba sola no basta para
     * arreglarlo: el espia tiene que estar POR DEBAJO de ella, viendo sus lecturas. Si
     * alguien lo envuelve al reves, esta prueba se queda sin lineas.
     */
    @Test
    fun `el terminal ve el arranque de la placa sin mandar ningun comando`() =
        withSimulator("--log-size", "20") { t ->
            val lineas = java.util.Collections.synchronizedList(ArrayList<String>())
            val espiado = SerialTap.wrap(t) { linea, deLaPlaca ->
                if (deLaPlaca) lineas.add(linea)
            }
            DeviceSession(espiado)

            val limite = System.currentTimeMillis() + 4000
            while (lineas.isEmpty() && System.currentTimeMillis() < limite) Thread.sleep(50)

            assertTrue(lineas.isNotEmpty(),
                       "el terminal no vio nada del arranque sin mandar un comando")
        }

    @Test
    fun `en silencio no devuelve nada, y no se queda colgada`() =
        withSimulator("--log-size", "20") { t ->
            val s = DeviceSession(t)
            s.drainBanner()
            val t0 = System.currentTimeMillis()
            assertEquals(0, s.leerFlujo(150).size, "devolvio datos sin que llegara nada")
            val tardo = System.currentTimeMillis() - t0
            assertTrue(tardo in 100..1500, "espero $tardo ms para un plazo de 150")
            s.cerrar()
        }
}
