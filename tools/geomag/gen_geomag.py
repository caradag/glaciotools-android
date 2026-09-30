#!/usr/bin/env python3
"""Genera los coeficientes de los modelos magneticos y las tablas de prueba de :core.

Todo sale de los ficheros OFICIALES guardados en tools/geomag/data/, nunca de numeros
copiados a mano:

  igrf14coeffs.txt        IGRF-14 (IAGA, NOAA NCEI), 1900-2030
  WMM2025.COF             WMM2025 (NOAA/BGS), 2025-2030
  WMM2025_TestValues.txt  valores de prueba oficiales de WMM2025
  igrf14.f                programa oficial de sintesis de IGRF-14: es el ORACULO de las
                          pruebas de IGRF (se compila con gfortran y se le pregunta)
  aosp/GeomagneticField-*.java
                          el modelo que trae Android, tal cual esta en AOSP: sirve para
                          reconocer que modelo tiene un telefono (ver AndroidMagneticModel)

Escribe:
  core/src/commonMain/.../geomag/Igrf14Data.kt
  core/src/commonMain/.../geomag/Wmm2025Data.kt
  core/src/commonMain/.../geomag/AndroidModelProbes.kt
  core/src/commonTest/.../geomag/Wmm2025TestValues.kt
  core/src/commonTest/.../geomag/Igrf14Reference.kt

Los coeficientes van como TEXTO que se analiza al cargar, no como literales de arrays: un
inicializador con miles de Double supera el limite de 64 KB por metodo de la JVM.

Uso:  python3 tools/geomag/gen_geomag.py
"""
import math
import os
import random
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parent.parent
DATA = AQUI / 'data'
PKG = 'cl/umag/glaciertemp/core/geomag'
MAIN = RAIZ / 'core/src/commonMain/kotlin' / PKG
TEST = RAIZ / 'core/src/commonTest/kotlin' / PKG

sys.path.insert(0, str(AQUI))
import sh_eval  # noqa: E402  evaluador de referencia en Python, validado contra WMM2025

CABECERA = ('package cl.umag.glaciertemp.core.geomag\n\n'
            '// GENERADO por tools/geomag/gen_geomag.py a partir de tools/geomag/data/.\n'
            '// No editar a mano: regenerar.\n\n')
LIMITE = 60000  # bytes por constante de texto, por debajo de los 65535 de la JVM


def kt_string(nombre, texto, visibilidad='internal'):
    assert len(texto.encode()) < LIMITE, (nombre, len(texto))
    assert '"""' not in texto and '$' not in texto
    return f'{visibilidad} const val {nombre}: String = """\n{texto}"""\n'


def igrf():
    lineas = (DATA / 'igrf14coeffs.txt').read_text().splitlines()
    cab = next(l for l in lineas if l.startswith('g/h')).split()
    epocas = cab[3:-1]
    datos = [' '.join(l.split()) for l in lineas if l[:2] in ('g ', 'h ')]
    texto = 'epochs ' + ' '.join(epocas) + '\n' + '\n'.join(datos) + '\n'
    (MAIN / 'Igrf14Data.kt').write_text(
        CABECERA + '/** igrf14coeffs.txt: fila de epocas y una fila por coeficiente (g/h n m valores... SV). */\n'
        + kt_string('IGRF14_COEFFICIENTS', texto))


def wmm():
    lineas = (DATA / 'WMM2025.COF').read_text().splitlines()
    epoca = lineas[0].split()[0]
    datos = [' '.join(l.split()) for l in lineas[1:] if l.strip() and not l.startswith('9999')]
    texto = f'epoch {epoca}\n' + '\n'.join(datos) + '\n'
    (MAIN / 'Wmm2025Data.kt').write_text(
        CABECERA + '/** WMM2025.COF: "n m g h dg dh" en nT y nT/ano. */\n'
        + kt_string('WMM2025_COEFFICIENTS', texto))


def wmm_pruebas():
    filas = [' '.join(l.split()) for l in (DATA / 'WMM2025_TestValues.txt').read_text().splitlines()
             if l.strip() and not l.startswith('#')]
    (TEST / 'Wmm2025TestValues.kt').write_text(
        CABECERA + '/**\n * WMM2025_TestValues.txt oficial: ano, alt km, lat, lon, D, I, H, X, Y, Z, F,\n'
        ' * dD/dt, dI/dt, dH/dt, dX/dt, dY/dt, dZ/dt, dF/dt.\n */\n'
        + kt_string('WMM2025_TEST_VALUES', '\n'.join(filas) + '\n'))


def igrf_referencia(n=300):
    """Pregunta al programa oficial de IGRF-14 por puntos y fechas al azar."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        f = (DATA / 'igrf14.f').read_text().splitlines(keepends=True)
        k = next(i for i, l in enumerate(f) if l.lstrip().lower().startswith('subroutine igrf14syn'))
        (tmp / 'syn.f').write_text(''.join(f[k:]))
        exe = tmp / 'drv'
        subprocess.run(['gfortran', '-O2', '-std=legacy', '-o', str(exe),
                        str(AQUI / 'igrf_driver.f'), str(tmp / 'syn.f')], check=True)
        rnd = random.Random(20260930)
        entrada = []
        for _ in range(n):
            ano = rnd.uniform(1900.0, 2029.999)
            lat = rnd.uniform(-89.0, 89.0)
            lon = rnd.uniform(-180.0, 180.0)
            alt = rnd.choice([0.0, 0.0, rnd.uniform(0, 10)])
            entrada.append(f'{ano:.6f} {alt:.6f} {90 - lat:.6f} {lon:.6f}')
        # puntos que interesan de verdad: Patagonia a lo largo del siglo
        for ano in (1900.0, 1950.0, 1990.5, 2020.0, 2025.0, 2026.75, 2029.9):
            entrada.append(f'{ano:.6f} 0.0 {90 + 53.16:.6f} -70.91')
        out = subprocess.run([str(exe)], input='\n'.join(entrada) + '\n', text=True,
                             capture_output=True, check=True).stdout
    filas = [' '.join(l.split()) for l in out.splitlines() if l.strip()]
    (TEST / 'Igrf14Reference.kt').write_text(
        CABECERA + '/**\n * Salida del programa OFICIAL igrf14.f (IGRF14SYN): ano, alt km, lat, lon,\n'
        ' * X, Y, Z, F (nT) y sus derivadas en el tiempo (nT/ano).\n */\n'
        + kt_string('IGRF14_REFERENCE', '\n'.join(filas) + '\n'))


def android_sondas():
    """Declinacion que da cada version de Android en unos puntos elegidos a proposito.

    Se reproduce el calculo de AOSP: coeficientes de su fichero y anos transcurridos como
    (t - BASE_TIME) / 365 dias, con su BASE_TIME. Se eligen puntos donde los dos modelos
    difieren mucho, para que la identificacion no dependa de decimas.
    """
    candidatos = {
        'WMM2015': (sh_eval.aosp(str(DATA / 'aosp/GeomagneticField-android11.java')),
                    # new GregorianCalendar(2015, 1, 1): el mes 1 es FEBRERO, en hora local
                    datetime(2015, 2, 1, tzinfo=timezone.utc).timestamp()),
        'WMM2020': (sh_eval.aosp(str(DATA / 'aosp/GeomagneticField-main.java')),
                    datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp()),
    }
    fecha = datetime(2022, 7, 1, tzinfo=timezone.utc).timestamp()

    def decl(nombre, lat, lon):
        (ep, g, h, dg, dh), base = candidatos[nombre]
        dt = (fecha - base) / (365 * 86400)
        gg = {k: g[k] + dg[k] * dt for k in g}
        hh = {k: h[k] + dh[k] * dt for k in h}
        return sh_eval.decl(*sh_eval.field(gg, hh, lat, lon, 0.0, 12))

    rejilla = [(la, lo) for la in range(-75, 76, 15) for lo in range(-180, 180, 30)]
    dif = sorted(rejilla, key=lambda p: -abs(decl('WMM2015', *p) - decl('WMM2020', *p)))
    sondas = dif[:4]
    lineas = []
    for lat, lon in sondas:
        lineas.append(f'{lat} {lon} {decl("WMM2015", lat, lon):.4f} {decl("WMM2020", lat, lon):.4f}')
    (MAIN / 'AndroidModelProbes.kt').write_text(
        CABECERA + '/** Instante de las sondas: 2022-07-01T00:00Z. */\n'
        f'internal const val ANDROID_PROBE_EPOCH_MILLIS: Long = {int(fecha * 1000)}L\n\n'
        '/** lat lon D(WMM2015 de AOSP) D(WMM2020 de AOSP), altura 0. */\n'
        + kt_string('ANDROID_PROBES', '\n'.join(lineas) + '\n'))


def main():
    MAIN.mkdir(parents=True, exist_ok=True)
    TEST.mkdir(parents=True, exist_ok=True)
    igrf(); wmm(); wmm_pruebas(); igrf_referencia(); android_sondas()
    print('generados en', MAIN.relative_to(RAIZ), 'y', TEST.relative_to(RAIZ))


if __name__ == '__main__':
    main()
