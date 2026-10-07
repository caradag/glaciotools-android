# Referencia de oro de urclock (Fase 0)

Capturada el 2026-10-07 con la placa conectada por un FTDI FT232RL, usando el avrdude
8.0-arduino.1 de MiniCore y las mismas opciones que el IDE (`-D -xnometadata`, sin `-Ueeprom`).

- `showall.txt`: `avrdude -c urclock -x showall`. Bootloader **Urboot u7.7 `weu-jPrac`**, 384 B
  (3 páginas, desde `0x7E80`), vector 25 (SPM_Ready). Sin metadatos.
- `readback.hex`: la flash ENTERA de la placa (firmware 3.11), con vectores ya ajustados:
  - vector 0 = `3F CF F6 05`, un `rjmp` hacia atrás que da la vuelta hasta el bootloader,
    seguido de la segunda palabra del `jmp` original;
  - vector 25 = `0C 94 F6 05`, `jmp` al inicio del firmware.
- `original.hex`: el mismo firmware SIN ajustar (vector 0 = `0C 94 F6 05`, vector 25 =
  `0C 94 1E 06` = `__bad_interrupt`), reconstruido desde `readback.hex`. Subirlo con avrdude
  dejó la placa idéntica a `readback.hex`, comprobado releyendo.
- `write-trace.txt`: `avrdude -vvvv` de esa carga, con cada byte enviado y recibido.

Protocolo observado (urprotocol):

| Orden | Envío | Respuesta |
|---|---|---|
| sincronizar | `30 20` | `A0 77` (estos dos bytes codifican el MCU) |
| entrar a programación | `50 20` | `A0 77` |
| leer página | `03 <dir lo> <dir hi> <largo> 20` | `A0 <datos> 77` |
| escribir página | `02 <dir lo> <dir hi> 80 <128 B> 20` | `A0 77` |
| salir | `51 20` | `A0 77` |

La app tiene que producir, a partir de `original.hex`, exactamente la imagen de
`readback.hex`.
