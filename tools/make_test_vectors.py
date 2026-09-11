#!/usr/bin/env python3
"""Genera vectores de prueba para :core usando decode_logh.py como oraculo.

Construye logs sinteticos con varios LOG_SIGNATURE, los envuelve en el mismo Intel HEX
que emite LOGH, y guarda junto a cada captura el CSV que produce decode_logh.py. Los
tests de Kotlin comparan contra esos CSV, de modo que el decodificador nuevo se valida
contra la implementacion ya probada en campo sin depender de Python en cada ejecucion.

    python3 tools/make_test_vectors.py core/src/test/resources
"""
import os, random, struct, subprocess, sys
from datetime import datetime, timedelta

DECODER = os.path.expanduser(
    "~/Documents/sketchbook/GlacierTemp_1_cell_v02_claude/decode_logh.py")
EPOCH = datetime(2000, 1, 1)
INVALID = -32768

CHANNELS = [("Volt", 0x0001), ("Temp", 0x0002), ("RH", 0x0004), ("HAtemp", 0x0008)]
DS_BIT, DS_MASK, DS_SHIFT = 0x0010, 0x0E00, 9
ANALOG = [("A0", 0x0020), ("A1", 0x0040), ("A2", 0x0080), ("A3", 0x0100)]

def n_fields(sig):
    n = sum(1 for _, b in CHANNELS if sig & b)
    if sig & DS_BIT:
        n += ((sig & DS_MASK) >> DS_SHIFT) + 1
    n += sum(1 for _, b in ANALOG if sig & b)
    return n

def build_log(sig, n_records, seed):
    """Bytes crudos del log, con casos incomodos incluidos a proposito."""
    rnd = random.Random(seed)
    nf = n_fields(sig)
    out = bytearray()
    t0 = int((datetime(2025, 11, 24, 12, 0, 0) - EPOCH).total_seconds())
    for i in range(n_records):
        if i == 3:                      # slot nunca escrito
            out += b"\x00" * (4 + 2 * nf); continue
        if i == 7:                      # flash borrada
            out += b"\xFF" * (4 + 2 * nf); continue
        out += struct.pack("<I", t0 + i * 600)
        for f in range(nf):
            if i == 5 and f == 0:
                v = INVALID             # lectura fallida
            elif i == 9 and f == 2:
                v = -1                  # centinela antiguo de humedad
            else:
                # valores que ejercitan el redondeo en el limite (x.xx5)
                v = rnd.choice([1235, 2465, 1250, -1250, 32767, -32767,
                                rnd.randint(-30000, 30000)])
            out += struct.pack("<h", v)
    return bytes(out)

def to_ihex(data, sig):
    lines = [f"log signature: 0x{sig:04X}", f"build signature: 0x{sig:04X}"]
    base = -1
    for off in range(0, len(data), 16):
        hi = off >> 16
        if hi != base:
            base = hi
            rec = bytes([2, 0, 0, 4, (hi >> 8) & 0xFF, hi & 0xFF])
            lines.append(":" + (rec + bytes([(-sum(rec)) & 0xFF])).hex().upper())
        chunk = data[off:off + 16]
        rec = bytes([len(chunk), (off >> 8) & 0xFF, off & 0xFF, 0]) + chunk
        lines.append(":" + (rec + bytes([(-sum(rec)) & 0xFF])).hex().upper())
    lines.append(":00000001FF")
    return "\n".join(lines) + "\n"

def main():
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    cases = [("std", 0x100F, 40), ("ds1", 0x101F, 30),
             ("ds3", 0x141F, 25), ("ds8", 0x1E1F, 20),
             ("analog", 0x11FF, 20)]
    for name, sig, n in cases:
        data = build_log(sig, n, seed=hash(name) & 0xFFFF)
        cap = os.path.join(outdir, f"{name}.logh")
        open(cap, "w").write(to_ihex(data, sig))
        csv = subprocess.run([sys.executable, DECODER, cap],
                             capture_output=True, text=True, check=True).stdout
        open(os.path.join(outdir, f"{name}.csv"), "w").write(csv)
        print(f"{name}: sig 0x{sig:04X}, {n} registros, "
              f"{len(data)} B, {len(csv.splitlines())-1} filas de CSV")

if __name__ == "__main__":
    main()
