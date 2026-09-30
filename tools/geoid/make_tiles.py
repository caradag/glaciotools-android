#!/usr/bin/env python3
"""Grillas de geoide para GlacioTools, a partir de los PGM de GeographicLib.

  python3 tools/geoid/make_tiles.py global <egm96-15.pgm> <salida.gtg>
      La grilla entera en una sola "tesela" (la de EGM96 va dentro de la app).

  python3 tools/geoid/make_tiles.py tiles <egm2008-1.pgm> <carpeta> <modelo> [--only S60W080,...]
      Teselas de 10x10 grados, comprimidas (.gtg.gz), y manifest.json con tamanos y SHA-256.

  python3 tools/geoid/make_tiles.py reference <GeoidEval> <dir-geoides> <nombre> <n> <salida.csv> [bbox]
      Puntos al azar evaluados por GeoidEval (el oraculo): lat,lon,N. bbox = S,W,N,E.

Formato .gtg (big-endian), el que lee GeoidGrid.parse en :core:
  "GTGEOID1", u8 largo + nombre del modelo, i32 width, height, x0, y0, xs, ys,
  f64 offset, scale, y xs*ys u16 (fila 0 al norte, columna 0 en 0 E, como el PGM).

Los indices son los de la grilla GLOBAL; una tesela es una ventana con un borde de dos
muestras para que la interpolacion cubica (12 puntos) no necesite a la vecina.
"""
import gzip
import hashlib
import json
import math
import random
import struct
import subprocess
import sys
from pathlib import Path

BORDE = 2


def leer_pgm(ruta):
    with open(ruta, 'rb') as f:
        assert f.readline().strip() == b'P5'
        offset = scale = None
        linea = f.readline()
        while linea.startswith(b'#'):
            p = linea.split()
            if len(p) >= 3 and p[1] == b'Offset':
                offset = float(p[2])
            if len(p) >= 3 and p[1] == b'Scale':
                scale = float(p[2])
            linea = f.readline()
        w, h = map(int, linea.split())
        assert int(f.readline()) == 65535
        datos = f.read()
    assert len(datos) == w * h * 2, (len(datos), w, h)
    return w, h, offset, scale, datos


def escribir_gtg(modelo, w, h, x0, y0, xs, ys, offset, scale, filas):
    cab = b'GTGEOID1' + bytes([len(modelo)]) + modelo.encode('ascii')
    cab += struct.pack('>6i2d', w, h, x0, y0, xs, ys, offset, scale)
    return cab + b''.join(filas)


def ventana(datos, w, h, x0, y0, xs, ys):
    """Filas [y0, y0+ys) y columnas [x0, x0+xs) con x modulo w (puede cruzar 0 E)."""
    filas = []
    for y in range(y0, y0 + ys):
        base = y * w * 2
        fila = datos[base:base + w * 2]
        a = (x0 % w)
        if a + xs <= w:
            filas.append(fila[a * 2:(a + xs) * 2])
        else:
            filas.append(fila[a * 2:] + fila[:(a + xs - w) * 2])
    return filas


def global_(pgm, salida):
    w, h, off, sc, datos = leer_pgm(pgm)
    modelo = Path(pgm).stem.split('-')[0]
    b = escribir_gtg(modelo, w, h, 0, 0, w, h, off, sc, ventana(datos, w, h, 0, 0, w, h))
    Path(salida).write_bytes(b)
    print(salida, len(b), 'bytes')


def nombre_tesela(modelo, res, sur, oeste):
    return f"{modelo}-{res}-{'S' if sur < 0 else 'N'}{abs(sur):02d}{'W' if oeste < 0 else 'E'}{abs(oeste):03d}.gtg.gz"


def tiles(pgm, carpeta, modelo, solo=None):
    w, h, off, sc, datos = leer_pgm(pgm)
    res_min = round(360 * 60 / w)
    res = f'{res_min}m'
    pormin = w / 360.0  # muestras por grado
    carpeta = Path(carpeta)
    carpeta.mkdir(parents=True, exist_ok=True)
    manifiesto = {'model': modelo, 'resolution_arcmin': res_min, 'tile_degrees': 10,
                  'source': Path(pgm).name, 'tiles': []}
    for sur in range(-90, 90, 10):
        for oeste in range(-180, 180, 10):
            clave = f"{'S' if sur < 0 else 'N'}{abs(sur):02d}{'W' if oeste < 0 else 'E'}{abs(oeste):03d}"
            if solo and clave not in solo:
                continue
            x_ini = round(((oeste % 360)) * pormin) - BORDE
            xs = round(10 * pormin) + 2 * BORDE + 1
            y_ini = max(0, round((90 - (sur + 10)) * pormin) - BORDE)
            y_fin = min(h - 1, round((90 - sur) * pormin) + BORDE + 1)
            ys = y_fin - y_ini + 1
            crudo = escribir_gtg(modelo, w, h, x_ini % w, y_ini, xs, ys, off, sc,
                                 ventana(datos, w, h, x_ini, y_ini, xs, ys))
            gz = gzip.compress(crudo, compresslevel=9, mtime=0)
            nombre = nombre_tesela(modelo, res, sur, oeste)
            (carpeta / nombre).write_bytes(gz)
            manifiesto['tiles'].append({'name': nombre, 'south': sur, 'west': oeste,
                                        'bytes': len(crudo), 'gz_bytes': len(gz),
                                        'sha256': hashlib.sha256(gz).hexdigest()})
    (carpeta / f'{modelo}-{res}-manifest.json').write_text(json.dumps(manifiesto, indent=1))
    tot = sum(t['gz_bytes'] for t in manifiesto['tiles'])
    print(f"{len(manifiesto['tiles'])} teselas, {tot / 1e6:.1f} MB comprimidas en {carpeta}")


def reference(geoideval, dirg, nombre, n, salida, bbox=None):
    rnd = random.Random(20260930)
    pts = []
    if bbox:
        s, o, nn, e = map(float, bbox.split(','))
    for _ in range(n):
        if bbox:
            pts.append((rnd.uniform(s, nn), rnd.uniform(o, e)))
        else:
            # uniforme en la esfera, mas polos, antimeridiano y bordes de tesela
            pts.append((math.degrees(math.asin(rnd.uniform(-1, 1))), rnd.uniform(-180, 180)))
    if not bbox:
        pts += [(90, 0), (-90, 17), (89.99, 123), (-89.99, -45), (0, 180), (0, -180),
                (12.3, 179.999), (-12.3, -179.999), (-50.0, -70.0), (-49.99, -80.0)]
    entrada = '\n'.join(f'{la:.8f} {lo:.8f}' for la, lo in pts) + '\n'
    out = subprocess.run([geoideval, '-d', dirg, '-n', nombre], input=entrada, text=True,
                         capture_output=True, check=True).stdout.split()
    assert len(out) == len(pts)
    with open(salida, 'w') as f:
        f.write('lat,lon,N\n')
        for (la, lo), v in zip(pts, out):
            f.write(f'{la:.8f},{lo:.8f},{v}\n')
    print(salida, len(pts), 'puntos')


if __name__ == '__main__':
    c, *a = sys.argv[1:]
    if c == 'global':
        global_(*a)
    elif c == 'tiles':
        solo = None
        if '--only' in a:
            i = a.index('--only'); solo = set(a[i + 1].split(',')); a = a[:i]
        tiles(*a, solo=solo)
    elif c == 'reference':
        reference(a[0], a[1], a[2], int(a[3]), a[4], a[5] if len(a) > 5 else None)
    else:
        sys.exit(__doc__)
