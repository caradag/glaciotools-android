#!/usr/bin/env python3
"""Simulador de la placa GlacierTemp: habla el mismo protocolo serie que el firmware.

Permite desarrollar y probar la app sin hardware. Reproduce a proposito los
comportamientos incomodos que rompen las apps -- el retardo del bootloader, la ventana
de 30 s de modo consola, XON/XOFF y el aviso de LOW VOLTAGE -- y puede emular la
fragmentacion de BLE para que los fallos de reensamblado salgan aqui y no en el glaciar.

    python3 fake_glaciertemp.py --tcp 5555 --log-size 500
    python3 fake_glaciertemp.py --pty            # imprime la ruta del pty y espera
    python3 fake_glaciertemp.py --tcp 5555 --mtu 20 --conn-interval 30

Formato de LOGB (definido aqui, a implementar igual en el firmware):
    linea de texto  "LOGB begin sig=0x100F rec=12 from=0 to=99 blocks=5 blocksize=256"
    por cada bloque  AA 55 <u16 idx LE> <u16 len LE> <len bytes> <u16 crc LE>
    linea de texto  "LOGB end"
El CRC es CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), barato en AVR.
"""
import argparse, os, pty, random, selectors, socket, struct, sys, time, zlib
from datetime import datetime, timedelta

EPOCH = datetime(2000, 1, 1)
INVALID = -32768
BLOCK = 256
# Las mismas que el firmware: la consola va a 115200, que es el techo de los modulos
# HM-10, y el volcado binario puede pedir 230400 solo por cable.
NORMAL_BAUD = 115200
FAST_BAUD = 230400
# Version de firmware y de protocolo, en UN solo sitio. Estaban escritas dos veces y ya
# habian divergido: INFO decia fw=2.7 proto=2 y VER seguia contestando fw=2.0 proto=1.
FW_VERSION = "3.3"
PROTOCOL = 4
# Identidad del hardware, como en el firmware: tipo + revision de placa, NO la del firmware.
BOARD_TYPE = "GT"
BOARD_HW_VERSION = "001"
# Capacidad de la flash. Es variable de modulo y no constante porque las pruebas necesitan
# una memoria pequena: LOGH vuelca la flash ENTERA, y veintitres megas de Intel HEX por cada
# caso convertirian la suite en algo que nadie ejecuta.
FLASH_BYTES = 8 * 1024 * 1024


def short_id(uid: int) -> str:
    """El identificador corto: tipo, revision y los SEIS ultimos digitos hexadecimales
    del numero de serie de fabrica. Antes eran 32 bits por CRC-32."""
    return f"{BOARD_TYPE}{BOARD_HW_VERSION}-" + f"{uid:016X}"[-6:]

CHANNELS = [("Volt", 0x0001, 1000.0, 2), ("Temp", 0x0002, 100.0, 2),
            ("RH", 0x0004, 10.0, 1), ("HAtemp", 0x0008, 100.0, 2)]
DS_BIT, DS_MASK, DS_SHIFT = 0x0010, 0x0E00, 9
ANALOG = [("A0", 0x0020), ("A1", 0x0040), ("A2", 0x0080), ("A3", 0x0100)]

DEFAULTS = {"INT": 600, "LVM": 4, "TZN": -3, "ADJ": 7, "MSW": 0}
# Los rotulos EXACTOS que el initializer graba en la EEPROM y que displayVars() imprime.
# Antes eran inventados y ademas el simulador respondia con el CODIGO ("INT: 600") en vez de
# con el rotulo: por eso la app parecia leer bien la configuracion contra el simulador y
# devolvia "?" contra la placa. Un simulador que no reproduce el formato real solo sirve para
# aprobar codigo roto.
LABELS = {"INT": "Interval between measurements (sec)",
          "LVM": "Low voltage interval multiplier",
          "TZN": "Time Zone (hours)",
          "ADJ": "GPS clock adjustments frequency (days)",
          "MSW": "Satellite messages frequency (days)"}


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def memory_lifetime(board, link):
    """Las mismas dos lineas que imprime printMemoryLifetime() en el firmware.

    Se reproduce la ARITMETICA del AVR, incluida la descomposicion en minutos y resto: si
    el simulador calculara con precision infinita, un dia de diferencia en el borde pasaria
    por fallo de la placa cuando es la aproximacion acordada.
    """
    max_records = FLASH_BYTES // board.rec
    free = max(0, max_records - board.count)
    interval = board.vars["INT"]
    days = free * (interval // 60) // 1440 + free * (interval % 60) // 86400
    link.line(f"Memory: {free} free records, {days} days at {interval} s")
    if free == 0:
        link.line("Memory is FULL")
    elif days > 18250:
        link.line("Full in more than 50 years")
    else:
        link.line("Full on: " + (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S"))


def fields(sig):
    out = [(n, s, d) for n, b, s, d in CHANNELS if sig & b]
    if sig & DS_BIT:
        out += [(f"DS{i}", 100.0, 2) for i in range(((sig & DS_MASK) >> DS_SHIFT) + 1)]
    out += [(n, 1000.0, 3) for n, b in ANALOG if sig & b]
    return out


class Board:
    def __init__(self, sig, n_records, seed, uid, drain=None):
        self.drain = drain          # (mv_inicial, mv_final) para el canal Volt
        self.sig = sig
        self.fields = fields(sig)
        self.rec = 4 + 2 * len(self.fields)
        self.uid = uid
        self.vars = dict(DEFAULTS)
        self.data = self._build(n_records, seed)
        self.count = n_records

    def _build(self, n, seed):
        rnd = random.Random(seed)
        out = bytearray()
        t0 = int((datetime(2025, 11, 24, 12, 0, 0) - EPOCH).total_seconds())
        for i in range(n):
            out += struct.pack("<I", t0 + i * self.vars["INT"])
            for f, (name, scale, _dec) in enumerate(self.fields):
                if self.drain and name == "Volt":
                    # Descarga lineal con algo de ruido termico, como en campo.
                    a, b = self.drain
                    mv = a + (b - a) * (i / max(n - 1, 1)) + rnd.gauss(0, 8)
                    v = int(round(mv))          # el canal Volt va en mV (escala /1000)
                elif i % 97 == 5 and f == 0:
                    v = INVALID
                else:
                    v = rnd.randint(-30000, 30000)
                out += struct.pack("<h", v)
        return bytes(out)

    def record(self, i):
        return self.data[i * self.rec:(i + 1) * self.rec]

    def csv_rows(self, a, b):
        yield "Time," + ",".join(n for n, _, _ in self.fields)
        for i in range(a, min(b + 1, self.count)):
            r = self.record(i)
            t = int.from_bytes(r[0:4], "little")
            cols = []
            for k, (name, scale, dec) in enumerate(self.fields):
                v = int.from_bytes(r[4 + 2 * k:6 + 2 * k], "little", signed=True)
                cols.append("NaN" if v == INVALID or (name == "RH" and v < 0)
                            else f"{v/scale:.{dec}f}")
            yield f"{EPOCH + timedelta(seconds=t):%Y-%m-%d %H:%M:%S}," + ",".join(cols)


class Link:
    """Escribe respetando el MTU y el intervalo de conexion, para emular BLE.

    Con --module-buffer N se emula ademas el fallo que de verdad rompe las descargas largas:
    un puente serie-BLE recibe de la placa a 115200 (11,5 kB/s) y solo mueve 1-5 kB/s por
    radio, asi que si nadie frena a la placa su buffer interno se desborda y DESCARTA bytes
    en silencio. Con 20 registros no se nota; con 3.000 se pierde casi todo.
    """
    def __init__(self, write, mtu, interval_ms, module_buffer=0, drain_bps=0):
        self._write, self.mtu, self.interval = write, mtu, interval_ms / 1000.0
        self.module_buffer = module_buffer
        self.drain_bps = drain_bps
        self._pending = 0            # bytes en el buffer del modulo
        self._last = time.monotonic()
        self.dropped = 0
        self.paused = False          # el receptor pidio XOFF

    def _drain(self):
        """Descuenta lo que la radio pudo emitir desde la ultima escritura."""
        now = time.monotonic()
        if self.drain_bps > 0:
            self._pending = max(0.0, self._pending - (now - self._last) * self.drain_bps)
        self._last = now

    def _accept(self, chunk: bytes) -> bytes:
        """Lo que cabe en el buffer del modulo. El resto se pierde, sin aviso."""
        if self.module_buffer <= 0:
            return chunk
        self._drain()
        room = self.module_buffer - self._pending
        if room <= 0:
            self.dropped += len(chunk)
            return b""
        if len(chunk) <= room:
            self._pending += len(chunk)
            return chunk
        self.dropped += len(chunk) - int(room)
        self._pending = self.module_buffer
        return chunk[:int(room)]

    def send(self, data: bytes):
        if self.mtu <= 0:
            self._write(self._accept(data)); return
        for i in range(0, len(data), self.mtu):
            self._write(self._accept(data[i:i + self.mtu]))
            if self.interval:
                time.sleep(self.interval)

    def line(self, s=""):
        self.send((s + "\r\n").encode())


def handle(cmd, board, link, args):
    c = cmd.strip()
    if not c:
        return
    up = c.upper()

    if up == "H":
        link.line("GlacierTemp commands:")
        for k, v in LABELS.items():
            link.line(f"  {k}[=n]  {v}")
        link.line("  M     Take a measurement")
        link.line("  I     Info      TIME  Show/set clock    RC   Reset counter")
        link.line("  LOG / LOGC / LOGH / LOGB[=a,b]   Dump the log")
        link.line("  ID    Board id  VER   Firmware version")
        return

    if up == "I":
        link.line(f"GlacierTemp 1-cell rev02")
        link.line(f"Board id: {board.uid:016X}")
        link.line(f"Records: {board.count}")
        link.line(f"Log signature: 0x{board.sig:04X}")
        link.line(f"Bytes per sample: {board.rec}")
        for k in DEFAULTS:
            link.line(f"{LABELS[k]}: {board.vars[k]}")
        return

    # F4: cabecera de metadatos legible por maquina, en una sola linea de campos fijos.
    if up == "INFO":
        link.line(f"INFO fw={FW_VERSION} proto={PROTOCOL} id={board.uid:016X} "
                  f"sig=0x{board.sig:04X} "
                  f"rec={board.rec} count={board.count} flash={FLASH_BYTES} "
                  f"sid={short_id(board.uid)} "
                  f"baud={NORMAL_BAUD} fastbaud={FAST_BAUD}")
        return

    if up == "CALC":
        memory_lifetime(board, link); return
    if up == "ID":
        # La MISMA linea que el arranque, como printBoardIdStandalone(): quien teclea ID
        # quiere el corto sin perder el completo.
        link.line(f"Board ID: {short_id(board.uid)}  (full {board.uid:016X})")
        return
    if up == "VER":
        link.line(f"fw={FW_VERSION} proto={PROTOCOL}"); return
    if up == "M":
        link.line("Measuring..."); link.line("OK"); return
    if up == "RC":
        # El texto EXACTO de resetCount(): "Memory reset", no "Counter reset". Un simulador
        # que contesta algo parecido pero distinto solo sirve para aprobar codigo roto.
        board.count = 0
        link.line("Memory reset")
        return
    if up.startswith("TIME"):
        # Como printRTCTime(): con la etiqueta "Time:" delante.
        # --clock-offset desplaza el reloj de la placa, para probar el aviso de desfase.
        t = datetime.now() + timedelta(seconds=getattr(args, "clock_offset", 0))
        link.line("Time: " + t.strftime("%Y-%m-%d %H:%M:%S")); return

    if up.startswith("LOGC") or up.startswith("LOG") and not up.startswith("LOGB") \
            and not up.startswith("LOGH"):
        for row in board.csv_rows(0, board.count - 1):
            link.line(row)
        return

    if up.startswith("LOGH"):
        # Vuelca la memoria ENTERA, no los registros que el contador dice que hay. Es la via
        # de recuperacion para cuando el firmware no puede leer su log, y entonces el
        # contador es justamente el dato del que no hay que fiarse. LOGH=n sigue acotandolo.
        pedido, fast = 0, 0
        if "=" in c:
            partes = c.split("=", 1)[1].split(",")
            try:
                pedido = int(partes[0])
                if len(partes) > 1:
                    fast = int(partes[1])
            except (ValueError, IndexError):
                pedido, fast = 0, 0
        n = pedido if 0 < pedido <= FLASH_BYTES else FLASH_BYTES
        # Lo que no se ha escrito nunca lee 0xFF, como una flash de verdad.
        data = board.data[:n] + b"\xFF" * max(0, n - len(board.data))

        # La misma cabecera que displayHistoryHex(): la app localiza el Intel HEX entre el
        # primer ':' y :00000001FF, pero un simulador que no reproduce el preambulo deja sin
        # probar el codigo que lo salta.
        link.line("LOGH raw memory dump")
        link.line(f"samples:{board.count}")
        link.line(f"bytes:{n} of {FLASH_BYTES}")
        link.line(f"this build record size:{board.rec}")
        link.line(f"log signature:0x{board.sig:04X}")
        link.line(f"this build signature:0x{board.sig:04X}")
        if fast:
            link.line(f"fast:{fast}")
        link.line("Intel HEX follows. Keep from the first ':' to :00000001FF")
        base = -1
        for off in range(0, len(data), 16):
            hi = off >> 16
            if hi != base:
                base = hi
                r = bytes([2, 0, 0, 4, (hi >> 8) & 0xFF, hi & 0xFF])
                link.line(":" + (r + bytes([(-sum(r)) & 0xFF])).hex().upper())
            ch = data[off:off + 16]
            r = bytes([len(ch), (off >> 8) & 0xFF, off & 0xFF, 0]) + ch
            link.line(":" + (r + bytes([(-sum(r)) & 0xFF])).hex().upper())
        link.line(":00000001FF")
        return

    if up.startswith("LOGB"):
        a, b, fast = 0, board.count - 1, 0
        if "=" in c:
            # Tercer valor opcional: la velocidad a la que viajan los bloques. Aqui no hay
            # UART de verdad, pero se acepta y se refleja en la cabecera para que la app
            # recorra exactamente el mismo camino que contra la placa.
            parts = c.split("=", 1)[1].split(",")
            try:
                a, b = int(parts[0]), int(parts[1])
                if len(parts) > 2:
                    fast = int(parts[2])
            except (ValueError, IndexError):
                link.line("Sintax: LOGB=from,to[,baud]"); return
        a = max(0, a); b = min(b, board.count - 1)
        if a > b:
            link.line("LOGB empty"); return
        payload = board.data[a * board.rec:(b + 1) * board.rec]
        nblocks = (len(payload) + BLOCK - 1) // BLOCK
        head = (f"LOGB begin sig=0x{board.sig:04X} rec={board.rec} "
                f"from={a} to={b} blocks={nblocks} blocksize={BLOCK}")
        if fast:
            head += f" fast={fast}"
        link.line(head)
        for idx in range(nblocks):
            chunk = payload[idx * BLOCK:(idx + 1) * BLOCK]
            frame = b"\xAA\x55" + struct.pack("<HH", idx, len(chunk)) + chunk
            frame += struct.pack("<H", crc16(chunk))
            link.send(frame)
        link.line("LOGB end")
        return

    if len(c) >= 3 and c[:3].upper() in DEFAULTS:
        key = c[:3].upper()
        if len(c) > 3 and c[3] == "=":
            try:
                board.vars[key] = int(float(c[4:]))
            except ValueError:
                link.line("Invalid value"); return
        # Como displayVars(): el ROTULO, no el codigo del comando.
        link.line(f"{LABELS[key]}: {board.vars[key]}")
        # Al cambiar el intervalo, la placa responde ademas con lo que ese numero decide de
        # verdad: cuanto dura la memoria y cuando se llena.
        if key == "INT" and len(c) > 3 and c[3] == "=":
            link.line("Next Wakeup: " + datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
            memory_lifetime(board, link)
        return

    link.line("Unknown command. H for help.")


def serve(readline, write, args, board):
    link = Link(write, args.mtu, args.conn_interval,
                getattr(args, "module_buffer", 0), getattr(args, "drain_bps", 0.0))
    if args.boot_delay:
        time.sleep(args.boot_delay)          # espera del bootloader
    link.line()
    link.line("GlacierTemp 1-cell rev02")
    if args.low_voltage:
        link.line("LOW VOLTAGE")
    link.line(f"Waiting commands for {args.console_window} s")
    while True:
        line = readline()
        if line is None:
            return
        handle(line, board, link, args)


def main():
    # La declaracion va ANTES de cualquier uso del nombre en la funcion: Python rechaza un
    # `global` que aparezca despues de leer la variable, aunque sea para un valor por defecto.
    global FLASH_BYTES
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--tcp", type=int, metavar="PORT")
    g.add_argument("--pty", action="store_true")
    g.add_argument("--stdio", action="store_true",
                   help="habla por stdin/stdout; util para tests sin red")
    ap.add_argument("--signature", default="0x100F")
    ap.add_argument("--log-size", type=int, default=200)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--uid", default="E5A1B2C3D4E5F607")
    ap.add_argument("--flash-size", type=int, default=FLASH_BYTES,
                    help="capacidad de la flash en bytes; las pruebas usan una pequena "
                         "porque LOGH vuelca la memoria entera")
    ap.add_argument("--mtu", type=int, default=0, help="0 = sin fragmentar")
    ap.add_argument("--conn-interval", type=float, default=0.0, metavar="MS")
    ap.add_argument("--boot-delay", type=float, default=0.0)
    ap.add_argument("--console-window", type=int, default=30)
    ap.add_argument("--low-voltage", action="store_true")
    ap.add_argument("--clock-offset", type=int, default=0, metavar="SEC",
                    help="desfase del reloj de la placa, para probar el aviso")
    ap.add_argument("--module-buffer", type=int, default=0, metavar="BYTES",
                    help="emula el buffer de un puente BLE: lo que no cabe se DESCARTA")
    ap.add_argument("--drain-bps", type=float, default=0.0, metavar="B/S",
                    help="velocidad a la que la radio vacia ese buffer")
    ap.add_argument("--battery-drain", metavar="MV_INI:MV_FIN",
                    help="hace que el canal Volt decaiga, p.ej. 1500:1400")
    a = ap.parse_args()

    # La capacidad es global porque la usan INFO, el aviso de memoria y LOGH.
    FLASH_BYTES = a.flash_size

    drain = None
    if a.battery_drain:
        x, y = a.battery_drain.split(":")
        drain = (float(x), float(y))
    board = Board(int(a.signature, 16), a.log_size, a.seed, int(a.uid, 16), drain)

    if a.stdio:
        inp = sys.stdin.buffer
        out = sys.stdout.buffer
        buf = bytearray()
        def readline():
            nonlocal buf
            while b"\n" not in buf:
                chunk = inp.read1(4096) if hasattr(inp, "read1") else inp.read(4096)
                if not chunk:
                    return None
                buf += chunk
            i = buf.index(b"\n")
            line, buf = buf[:i], buf[i + 1:]
            return line.decode(errors="replace")
        def write(d):
            out.write(d); out.flush()
        serve(readline, write, a, board)
        return

    if a.pty:
        master, slave = pty.openpty()
        print(os.ttyname(slave), flush=True)
        buf = bytearray()
        def readline():
            nonlocal buf
            while b"\n" not in buf:
                chunk = os.read(master, 4096)
                if not chunk:
                    return None
                buf += chunk
            i = buf.index(b"\n")
            out, buf = buf[:i], buf[i + 1:]
            return out.decode(errors="replace")
        serve(readline, lambda d: os.write(master, d), a, board)
    else:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", a.tcp)); srv.listen(1)
        print(f"listening on {a.tcp}", flush=True)
        while True:
            conn, _ = srv.accept()
            buf = bytearray()
            def readline():
                nonlocal buf
                while b"\n" not in buf:
                    chunk = conn.recv(4096)
                    if not chunk:
                        return None
                    buf += chunk
                i = buf.index(b"\n")
                out, buf = buf[:i], buf[i + 1:]
                return out.decode(errors="replace")
            try:
                serve(readline, conn.sendall, a, Board(int(a.signature, 16),
                      a.log_size, a.seed, int(a.uid, 16), drain))
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                conn.close()


if __name__ == "__main__":
    main()
