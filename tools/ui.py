#!/usr/bin/env python3
"""Conducir la app en el emulador desde fuera, con uiautomator.

Para las pantallas que los tests de Compose no pueden esperar (las de sensores recomponen con
cada lectura y nunca quedan inactivas). Las testTag llegan como resource-id porque la raiz
activa testTagsAsResourceId.

    python3 tools/ui.py tap sn-tab-compass        # por testTag
    python3 tools/ui.py tap-text "Compass"        # por texto visible
    python3 tools/ui.py text sn-declination       # imprime el texto de ese nodo
    python3 tools/ui.py type sn-decl-date 1950-01-01
    python3 tools/ui.py scroll-to sn-decl-calc
    python3 tools/ui.py dump                      # todos los nodos con texto o tag
    python3 tools/ui.py shot fichero.png
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*a, binary=False):
    r = subprocess.run(['adb', *a], capture_output=True, check=True)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def nodos():
    for _ in range(5):
        out = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        i = out.find('<?xml')
        j = out.rfind('</hierarchy>')
        if i >= 0 and j > i:
            return list(ET.fromstring(out[i:j + len('</hierarchy>')]).iter('node'))
        time.sleep(0.5)
    raise RuntimeError('uiautomator dump fallo')


def tag(n):
    return n.get('resource-id', '').split('/')[-1]


def centro(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    return (x1 + x2) // 2, (y1 + y2) // 2


def buscar(pred, timeout=20):
    fin = time.time() + timeout
    while time.time() < fin:
        for n in nodos():
            if pred(n):
                return n
        time.sleep(0.5)
    return None


def texto_de(n):
    """El texto del nodo y de sus descendientes (Compose reparte el texto entre hijos)."""
    return ' '.join(x.get('text', '') for x in n.iter('node') if x.get('text')).strip()


def por_tag(t, timeout=20):
    n = buscar(lambda n: tag(n) == t, timeout)
    if n is None:
        sys.exit(f'no aparece el tag {t}')
    return n


def ocultar_teclado():
    """BACK solo si el teclado esta a la vista: sin el, BACK sacaria de la pantalla."""
    if 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method'):
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(0.5)


def desplazar_a(t):
    """Desliza hasta que el nodo quede visible y por encima de donde sale el teclado.

    Si la pantalla ya no se desplaza mas (el nodo esta al final del contenido), se acepta
    donde este mientras sea visible.
    """
    alto = int(adb('shell', 'wm', 'size').split()[-1].split('x')[1])
    ocultar_teclado()
    anterior = None
    for _ in range(20):
        n = buscar(lambda n: tag(n) == t, timeout=1)
        if n is not None:
            y = centro(n)[1]
            if alto * 0.12 < y < alto * 0.6:
                return
            if y == anterior and y < alto - 150:
                return
            anterior = y
            if y <= alto * 0.12:
                adb('shell', 'input', 'swipe', '540', '500', '540', '1000', '300')
                time.sleep(0.5)
                continue
        adb('shell', 'input', 'swipe', '540', '1100', '540', '500', '300')
        time.sleep(0.5)
    sys.exit(f'no se alcanza {t}')


def main():
    cmd, *args = sys.argv[1:]
    if cmd == 'tap':
        adb('shell', 'input', 'tap', *map(str, centro(por_tag(args[0]))))
    elif cmd == 'tap-text':
        n = buscar(lambda n: n.get('text') == args[0])
        if n is None:
            sys.exit(f'no aparece el texto {args[0]}')
        adb('shell', 'input', 'tap', *map(str, centro(n)))
    elif cmd == 'text':
        print(texto_de(por_tag(args[0], float(args[1]) if len(args) > 1 else 20)))
    elif cmd == 'wait-text':
        # espera a que el nodo con ese tag contenga el fragmento
        t, frag = args[0], args[1]
        n = buscar(lambda n: tag(n) == t and frag in texto_de(n), float(args[2]) if len(args) > 2 else 60)
        if n is None:
            sys.exit(f'{t} no llega a contener {frag!r}')
        print(texto_de(n))
    elif cmd == 'type':
        desplazar_a(args[0])
        n = por_tag(args[0])
        adb('shell', 'input', 'tap', *map(str, centro(n)))
        time.sleep(0.3)
        # borra lo que haya y escribe
        adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
        adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * 40))
        adb('shell', 'input', 'text', args[1].replace(' ', '%s'))
    elif cmd == 'scroll-to':
        desplazar_a(args[0])
    elif cmd == 'dump':
        for n in nodos():
            if n.get('text') or tag(n):
                print(tag(n) or '-', '|', n.get('text'))
    elif cmd == 'shot':
        open(args[0], 'wb').write(adb('exec-out', 'screencap', '-p', binary=True))
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
