#!/usr/bin/env python3
"""Genera app/src/main/res/xml/device_filter.xml desde usb-serial-for-android.

Ese fichero es el que hace que Android ofrezca GlacioTools cuando se enchufa un cable
serie, y tiene que listar exactamente los mismos dispositivos que la libreria sabe
manejar. Escrito a mano se queda desfasado en silencio: la app aparecia en el dialogo
para un chip que no sabe abrir, o no aparecia para uno que si.

Los identificadores se leen del BYTECODE de la libreria --el metodo getSupportedDevices()
de cada driver-- porque la version 3.9.0 no publica ningun device_filter propio.

    python3 tools/gen_usb_device_filter.py

Requiere javap (viene con el JDK) y el AAR ya descargado en la cache de Gradle.
"""
import pathlib
import re
import subprocess
import sys
import zipfile

DRIVERS = ["Ch34x", "Cp21xx", "Ftdi", "Prolific", "ChromeCcd", "GsmModem"]
RAIZ = pathlib.Path(__file__).resolve().parent.parent
DESTINO = RAIZ / "app/src/main/res/xml/device_filter.xml"


def localizar_aar() -> pathlib.Path:
    cache = pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1/com.github.mik3y"
    aars = sorted(cache.rglob("usb-serial-for-android-*.aar"))
    if not aars:
        sys.exit("no encuentro el AAR en la cache de Gradle; compila una vez primero")
    return aars[-1]


def tabla_de_dispositivos(dir_clases: pathlib.Path) -> dict[int, set[int]]:
    """Fabricante -> productos, leido del bytecode de cada driver."""
    tabla: dict[int, set[int]] = {}
    for d in DRIVERS:
        cls = dir_clases / f"com/hoho/android/usbserial/driver/{d}SerialDriver.class"
        if not cls.exists():
            continue
        out = subprocess.run(["javap", "-p", "-c", "-constants", str(cls)],
                             capture_output=True, text=True).stdout
        i = out.find("getSupportedDevices();")
        if i < 0:
            continue
        cuerpo = out[i:]
        fin = cuerpo.find("areturn")
        cuerpo = cuerpo[:fin] if fin > 0 else cuerpo

        vendor, productos, pendiente = None, [], None
        for linea in cuerpo.splitlines():
            m = re.search(r"(sipush|bipush|ldc)\s+(?:#\d+\s+//\s+int\s+)?(\d+)", linea)
            if m:
                pendiente = int(m.group(2))
            if "Integer.valueOf" in linea and pendiente is not None:
                vendor, productos = pendiente, []
            elif "iastore" in linea and pendiente is not None:
                productos.append(pendiente)
            elif "Map.put" in linea and vendor is not None:
                tabla.setdefault(vendor, set()).update(productos)
                vendor, productos = None, []
    return tabla


def main() -> int:
    aar = localizar_aar()
    tmp = RAIZ / "build/usb-filter-tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as z:
        z.extract("classes.jar", tmp)
    with zipfile.ZipFile(tmp / "classes.jar") as z:
        z.extractall(tmp / "cls")

    tabla = tabla_de_dispositivos(tmp / "cls")
    if not tabla:
        sys.exit("no pude leer ningun identificador; cambio el bytecode de la libreria?")

    lineas = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- GENERADO por tools/gen_usb_device_filter.py. No editar a mano.",
        f"     Fuente: {aar.name}",
        f"     {len(tabla)} fabricantes, {sum(len(p) for p in tabla.values())} productos.",
        "",
        "     Esta lista es la que hace que Android ofrezca GlacioTools al enchufar un",
        "     cable serie, y ademas CONCEDE el permiso USB para ese aparato: elegir la app",
        "     en ese dialogo ahorra el paso de autorizar el adaptador a mano. -->",
        "<resources>",
    ]
    for v in sorted(tabla):
        for p in sorted(tabla[v]):
            lineas.append(f'    <usb-device vendor-id="{v}" product-id="{p}" />'
                          f'   <!-- 0x{v:04X}:0x{p:04X} -->')
    lineas += [
        "",
        "    <!-- CDC-ACM se reconoce por CLASE y no por identificador: es un estandar, no",
        "         un chip concreto, y CdcAcmSerialDriver lo prueba igual. La clase 2 a nivel",
        "         de DISPOSITIVO son los de comunicaciones, que es justo lo que se busca. -->",
        '    <usb-device class="2" />',
        "</resources>",
    ]
    DESTINO.parent.mkdir(parents=True, exist_ok=True)
    DESTINO.write_text("\n".join(lineas) + "\n", encoding="utf-8")
    print(f"{DESTINO.relative_to(RAIZ)}: {sum(len(p) for p in tabla.values())} productos "
          f"de {len(tabla)} fabricantes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
