#!/bin/bash
# Recorrido de punta a punta en el emulador contra el simulador de la placa.
#
#   tools/e2e.sh            usa el emulador que ya este corriendo
#   tools/e2e.sh --boot     arranca el emulador headless si no hay ninguno
#
# Deja las capturas en build/screenshots/.
set -u
cd "$(dirname "$0")/.."
source ~/.glaciertemp-app-env.sh

PORT=5599          # 5555 lo usa adb para el propio emulador
SIM_LOG=$(mktemp)
SHOTS=build/screenshots
mkdir -p "$SHOTS"

cleanup() {
  [ -n "${SIM_PID:-}" ] && kill "$SIM_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

if [ "${1:-}" = "--boot" ] && ! adb devices | grep -q emulator; then
  echo "== arrancando el emulador =="
  nohup emulator -avd Medium_Phone -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 3
  done
fi
adb devices | grep -q device$ || { echo "no hay dispositivo; use --boot"; exit 1; }

echo "== simulador de la placa en el puerto $PORT =="
if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  echo "  ya hay algo escuchando en $PORT: se reutiliza"
else
  python3 -u tools/fake_glaciertemp.py --tcp "$PORT" --log-size 240 --mtu 20 --clock-offset 4023 \
      > "$SIM_LOG" 2>&1 &
  SIM_PID=$!
  for _ in $(seq 1 20); do grep -q "listening on" "$SIM_LOG" && break; sleep 0.5; done
  grep -q "listening on" "$SIM_LOG" || {
    echo "  el simulador no arranco:"; cat "$SIM_LOG"; exit 1; }
  echo "  arrancado (pid $SIM_PID)"
fi

echo "== test instrumentado =="
./gradlew :app:connectedDebugAndroidTest --console=plain
RC=$?

echo "== captura final =="
adb exec-out screencap -p > "$SHOTS/final.png" 2>/dev/null && \
  echo "  $SHOTS/final.png"

[ $RC -eq 0 ] && echo "== OK ==" || echo "== FALLO (rc=$RC) =="
exit $RC
