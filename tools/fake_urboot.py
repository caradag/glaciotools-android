#!/usr/bin/env python3
"""Simulador del bootloader Urboot u7.7 (urprotocol) de la GlacierTemp, por stdin/stdout.

Responde como la placa en tools/urclock-golden/write-trace.txt: cada orden es un byte, sus
parametros y el fin de orden 0x20; la respuesta es A0 [datos] 77. Una orden desconocida
seguida de 0x20 tambien recibe A0 77 (asi se sincroniza: 30 20, luego 20 20).

  01 30 -> sincronizar        50 -> entrar en programacion     51 -> salir
  03 lo hi n -> leer n bytes (n=0 es 256)
  02 lo hi n <n bytes> -> escribir una pagina

Como el bootloader de verdad: no deja escribir sobre si mismo (desde 0x7E80) ni sobre la
primera palabra del vector de reset (la 'P' de weu-jPrac). Arranca con la flash de
--image (un Intel HEX de 32 KB, p.ej. readback.hex) y al salir con 51 la vuelca en --dump.

  --fail-after N   deja de contestar tras N paginas escritas (prueba de corte)
  --corrupt PAGE   la pagina con esa direccion se guarda con un byte cambiado
"""
import argparse, sys

BOOT = 0x7E80
FLASH = 0x8000

def load_hex(path):
    img = bytearray(b'\xff' * FLASH); base = 0
    for l in open(path).read().split():
        b = bytes.fromhex(l[1:]); n, a, t = b[0], (b[1] << 8) | b[2], b[3]
        if t == 0:
            img[base + a: base + a + n] = b[4:4 + n]
        elif t == 4:
            base = ((b[4] << 8) | b[5]) << 16
    return img

def dump_hex(img, path):
    out = []
    for a in range(0, FLASH, 16):
        rec = bytes([16, a >> 8, a & 0xFF, 0]) + img[a:a + 16]
        out.append(':' + rec.hex().upper() + '%02X' % ((-sum(rec)) & 0xFF))
    out.append(':00000001FF')
    open(path, 'w').write('\n'.join(out) + '\n')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--image', required=True)
    ap.add_argument('--dump')
    ap.add_argument('--fail-after', type=int, default=-1)
    ap.add_argument('--corrupt', type=lambda s: int(s, 0), default=-1)
    a = ap.parse_args()
    img = load_hex(a.image)
    inp = sys.stdin.buffer; out = sys.stdout.buffer
    written = 0

    def rd(n):
        d = inp.read(n)
        if len(d) < n:
            raise EOFError
        return d

    def reply(data=b''):
        out.write(b'\xa0' + data + b'\x77'); out.flush()

    try:
        while True:
            c = rd(1)[0]
            if c == 0x03:
                lo, hi, n = rd(3); n = n or 256
                if rd(1)[0] != 0x20: continue
                addr = lo | (hi << 8)
                reply(bytes(img[addr:addr + n]))
            elif c == 0x02:
                lo, hi, n = rd(3); n = n or 256
                data = rd(n)
                if rd(1)[0] != 0x20: continue
                if a.fail_after >= 0 and written >= a.fail_after:
                    continue                                   # deja de contestar
                addr = lo | (hi << 8)
                d = bytearray(data)
                if addr == a.corrupt:
                    d[5] ^= 0xFF
                for i in range(n):
                    p = addr + i
                    if p >= BOOT or p < 2:                     # se protege a si mismo y al reset
                        continue
                    img[p] = d[i]
                written += 1
                reply()
            elif c == 0x51:
                if rd(1)[0] != 0x20: continue
                reply()
                if a.dump:
                    dump_hex(img, a.dump)
            else:                                              # 30, 50, 20...: solo fin de orden
                if c == 0x20:
                    if rd(1)[0] != 0x20: continue
                elif rd(1)[0] != 0x20:
                    continue
                reply()
    except EOFError:
        pass

if __name__ == '__main__':
    main()
