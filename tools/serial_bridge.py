#!/usr/bin/env python3
"""Puente entre un puerto serie real y stdin/stdout, con reset por DTR al empezar.

Para probar el cargador de firmware de la app (FirmwareUploader) contra la placa de verdad
desde el PC: la prueba RealBoardUploadTest lanza esto y habla por sus tuberias. Solo usa la
biblioteca estandar (termios/ioctl), sin pyserial.

    serial_bridge.py /dev/ttyUSB0 [baud]
"""
import fcntl, os, select, struct, sys, termios, time

port = sys.argv[1]
baud = int(sys.argv[2]) if len(sys.argv) > 2 else 115200
fd = os.open(port, os.O_RDWR | os.O_NOCTTY)
attr = termios.tcgetattr(fd)
attr[0] = 0; attr[1] = 0                                  # iflag, oflag: crudo
attr[2] = termios.CS8 | termios.CREAD | termios.CLOCAL    # cflag
attr[3] = 0                                               # lflag
speed = getattr(termios, f'B{baud}')
attr[4] = attr[5] = speed
attr[6][termios.VMIN] = 0; attr[6][termios.VTIME] = 0
termios.tcsetattr(fd, termios.TCSANOW, attr)

def lines(on):
    bits = struct.pack('I', termios.TIOCM_DTR | termios.TIOCM_RTS)
    fcntl.ioctl(fd, termios.TIOCMBIS if on else termios.TIOCMBIC, bits)

# Reset: DTR/RTS sin activar y activados, el mismo flanco que da el IDE.
lines(False); time.sleep(0.05); lines(True)
termios.tcflush(fd, termios.TCIOFLUSH)

inp = sys.stdin.buffer.raw if hasattr(sys.stdin.buffer, 'raw') else sys.stdin.buffer
out = sys.stdout.buffer
ifd = sys.stdin.fileno()
while True:
    r, _, _ = select.select([fd, ifd], [], [], 0.5)
    if fd in r:
        d = os.read(fd, 4096)
        if d:
            out.write(d); out.flush()
    if ifd in r:
        d = os.read(ifd, 4096)
        if not d:
            break
        os.write(fd, d)
os.close(fd)
