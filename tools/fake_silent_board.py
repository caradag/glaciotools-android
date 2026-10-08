#!/usr/bin/env python3
"""Una placa que NO habla como logger, por TCP: imita el firmware de diagnostico.

Saca su banner al conectar, ignora INFO y CONT? (contesta "Unknown command") y a HELP le
responde con una lista. Sirve para probar el modo "conexion serie" de la app: el enlace se
abre, nadie contesta INFO, y el terminal tiene que seguir funcionando.

    fake_silent_board.py --tcp 5599
"""
import argparse, socket

ap = argparse.ArgumentParser(); ap.add_argument('--tcp', type=int, required=True)
a = ap.parse_args()
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('0.0.0.0', a.tcp)); srv.listen(1)
print(f'listening on {a.tcp}', flush=True)
while True:
    c, _ = srv.accept()
    c.sendall(b'\r\nGlacierTemp DIAGNOSTICS 1.6  -- SENSORS tests\r\n> ')
    buf = b''
    try:
        while True:
            d = c.recv(256)
            if not d:
                break
            buf += d
            while b'\n' in buf:
                line, buf = buf.split(b'\n', 1)
                cmd = line.strip().upper()
                if not cmd:
                    continue
                if cmd == b'HELP':
                    c.sendall(b'\r\nCommands (any case; [x] optional):\r\n ALL  every test\r\n HDC  HDC1080\r\n> ')
                else:
                    c.sendall(b'\r\nUnknown command (or not in this build). Type HELP.\r\n> ')
    except OSError:
        pass
    c.close()
