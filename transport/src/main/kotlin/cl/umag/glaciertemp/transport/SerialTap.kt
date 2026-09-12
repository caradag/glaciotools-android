package cl.umag.glaciertemp.transport

/**
 * Un [Transport] que deja ver TODO lo que pasa por la linea, en los dos sentidos.
 *
 * Existe porque, al repartir los comandos entre varias rutas --descarga, configuracion,
 * reloj, volcado crudo--, cada una decidia por su cuenta que enseñar en el terminal y casi
 * ninguna enseñaba nada. Con el espia en el TRANSPORTE no hay nada que recordar: por ahi
 * pasa todo lo que se envia y todo lo que llega, o no llega a la placa.
 *
 * Los bloques binarios no se vuelcan en crudo. Una descarga de cien mil registros es un mega
 * de bytes que no significan nada leidos como texto, llenarian el terminal y lo dejarian
 * inservible justo cuando hace falta. Se resumen como `[1234 bytes]`, acumulando los
 * consecutivos en una sola linea.
 *
 * CUIDADO con [BaudSwitchable]. DeviceSession decide si puede pedir el volcado rapido con
 * `transport as? BaudSwitchable`, asi que el espia tiene que conservar esa propiedad EXACTA:
 *
 *  - si no la implementa nunca, envolver el cable apaga el volcado rapido en silencio y la
 *    descarga pasa a la mitad de velocidad sin ningun error que lo explique;
 *  - si la implementa siempre, el BLE dice que sabe cambiar de velocidad cuando no puede.
 *    La placa cambiaria a 230400, el modulo se quedaria en 115200 y no llegaria nada
 *    legible. Que el BLE NO implemente esa interfaz es justamente lo que hace imposible
 *    pedirselo, y el espia no puede romperlo.
 *
 * De ahi que haya dos clases y una fabrica: [wrap] devuelve la variante que corresponde.
 *
 * HILOS. Desde que el enlace tiene un unico lector propio, `read` lo llama ESE hilo mientras
 * `write` lo llama el de la operacion en curso y `flush` el que la cierra. Los dos buffers
 * son estado mutable compartido, asi que cada uno se sincroniza sobre si mismo. Sin eso, una
 * linea que llega mientras se escribe un comando puede salir partida o perderse -- y seria
 * un fallo intermitente en la unica ventana donde uno mira cuando algo va mal.
 */
/**
 * Algo que puede avisar de que lo que viene AHORA son datos y no texto.
 *
 * Lo implementa el espia y lo usan las operaciones de volcado. No forma parte de [Transport]
 * a proposito: un transporte no tiene por que saber que es un volcado, y quien no lo
 * implemente simplemente no se entera, que es el comportamiento correcto.
 */
interface DumpAware {
    fun volcadoEmpieza()
    fun volcadoTermina()
}

open class SerialTap(
    private val inner: Transport,
    private val sink: (String, Boolean) -> Unit,
) : Transport by inner, DumpAware {

    companion object {
        /** Cada cuantos bytes de volcado sale una linea de resumen. */
        const val AVISO_CADA = 64L * 1024

        /** Envuelve conservando si el transporte de debajo sabe cambiar de velocidad. */
        fun wrap(inner: Transport, sink: (String, Boolean) -> Unit): SerialTap =
            if (inner is BaudSwitchable) SwitchableSerialTap(inner, sink)
            else SerialTap(inner, sink)
    }


    /** Caracteres sueltos no imprimibles que no valen la pena resumir. */
    private val entrada = Buffer(fromBoard = true)
    private val salida = Buffer(fromBoard = false)

    /**
     * Profundidad del volcado, no un booleano: una descarga por radio son miles de LOGB
     * dentro de una sola operacion, y con un booleano el primero que terminara apagaria el
     * modo para todos los demas.
     */
    private val enVolcado = java.util.concurrent.atomic.AtomicInteger(0)

    override fun volcadoEmpieza() {
        if (enVolcado.getAndIncrement() == 0) {
            entrada.empezarVolcado(); salida.empezarVolcado()
        }
    }

    override fun volcadoTermina() {
        if (enVolcado.decrementAndGet() <= 0) {
            enVolcado.set(0)
            entrada.terminarVolcado(); salida.terminarVolcado()
        }
    }

    override fun write(data: ByteArray) {
        salida.absorber(data)
        inner.write(data)
    }

    override fun read(timeoutMs: Int): ByteArray {
        val d = inner.read(timeoutMs)
        if (d.isNotEmpty()) entrada.absorber(d)
        return d
    }

    /** Vacia lo que quede sin salto de linea. Se llama al terminar una operacion. */
    fun flush() {
        entrada.flushAll()
        salida.flushAll()
    }

    private inner class Buffer(val fromBoard: Boolean) {
        private val texto = StringBuilder()
        private var binarios = 0L

        /**
         * Durante un volcado el flujo no es texto con algun byte raro, sino al reves, y el
         * resumen normal se vuelve ilegible: de los 256 valores de un byte, 95 son ASCII
         * imprimible, asi que uno de cada 2,7 bytes de dato rompe el `[N bytes]` y sale
         * como un caracter suelto. Medido sobre datos reales son unos 2.585 caracteres de
         * terminal por cada 1.024 bytes descargados -- dos veces y media lo que se esta
         * bajando, en lineas de cientos de caracteres cortadas donde cayo un 0x0A.
         *
         * Aqui los bytes se PESAN en vez de leerse: una linea cada [AVISO_CADA]. Lo que
         * significa algo --que empieza, cuanto ocupa, como termino-- ya lo cuenta la
         * operacion por su cuenta, que ademas lo sabe de verdad porque ha parseado la
         * cabecera, en vez de deducirlo de los bytes que pasan.
         */
        private var volcado = false
        private var delVolcado = 0L
        private var avisado = 0L

        @Synchronized fun empezarVolcado() {
            // Lo que hubiera a medias es texto de verdad y sale ahora: despues ya no habria
            // con que distinguirlo de los datos.
            flushAll()
            volcado = true; delVolcado = 0; avisado = 0
        }

        @Synchronized fun terminarVolcado() {
            if (!volcado) return
            volcado = false
            if (delVolcado > avisado) resumir(delVolcado)
            texto.setLength(0); binarios = 0
            delVolcado = 0; avisado = 0
        }

        private fun resumir(n: Long) {
            val cuanto = if (n >= 1024) "%.1f kB".format(n / 1024.0) else "$n B"
            sink("[dump: $cuanto]", true)
        }

        /** Pesa lo que llega y avisa al cruzar cada multiplo. */
        private fun contar(data: ByteArray) {
            delVolcado += data.size
            while (delVolcado - avisado >= AVISO_CADA) {
                avisado += AVISO_CADA
                resumir(avisado)
            }
        }

        @Synchronized fun feed(data: ByteArray) {
            for (b in data) {
                val c = b.toInt() and 0xFF
                when {
                    c == 0x0A -> { cerrarBinario(); texto.append('\n') }
                    c == 0x0D -> {}                       // el retorno no aporta nada
                    c in 0x20..0x7E || c == 0x09 -> { cerrarBinario(); texto.append(c.toChar()) }
                    else -> binarios++
                }
            }
        }

        private fun cerrarBinario() {
            if (binarios > 0) {
                texto.append("[$binarios bytes]")
                binarios = 0
            }
        }

        /** Publica las lineas completas y deja el resto para el siguiente trozo. */
        @Synchronized fun flushLines() {
            var i = texto.indexOf("\n")
            while (i >= 0) {
                emitir(texto.substring(0, i))
                texto.delete(0, i + 1)
                i = texto.indexOf("\n")
            }
            // Un resumen binario muy largo no espera al salto de linea: durante un volcado
            // no llega ninguno hasta el final, y el terminal se quedaria mudo varios minutos.
            if (binarios >= 4096) {
                cerrarBinario()
                emitir(texto.toString())
                texto.setLength(0)
            }
        }

        @Synchronized fun flushAll() {
            cerrarBinario()
            if (texto.isNotEmpty()) {
                emitir(texto.toString())
                texto.setLength(0)
            }
        }

        /** Alimentar y publicar, en UNA sola seccion critica. */
        @Synchronized fun absorber(data: ByteArray) {
            if (volcado) {
                // Lo que sale hacia la placa durante un volcado son las peticiones troceadas
                // y los XOFF/XON del control de flujo: miles de lineas que repiten lo mismo.
                // No se pesan siquiera, porque su tamano no le dice nada a nadie.
                if (fromBoard) contar(data)
                return
            }
            feed(data)
            flushLines()
        }

        private fun emitir(linea: String) {
            val t = linea.trimEnd()
            if (t.isEmpty()) return
            sink(if (fromBoard) t else "> $t", fromBoard)
        }
    }
}

/** El espia sobre un transporte que SI sabe cambiar de velocidad, como el cable. */
private class SwitchableSerialTap(
    private val switchable: Transport,
    sink: (String, Boolean) -> Unit,
) : SerialTap(switchable, sink), BaudSwitchable {
    override fun setBaudRate(baud: Int) {
        (switchable as BaudSwitchable).setBaudRate(baud)
    }
}
