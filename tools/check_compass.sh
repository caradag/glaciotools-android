#!/bin/bash
# Comprobacion en el emulador de la brujula y la calculadora de declinacion.
#
# No es un test de Compose porque la pantalla de sensores recompone con cada lectura y nunca
# queda inactiva: se conduce desde fuera con tools/ui.py (uiautomator). Requiere la app
# instalada (./gradlew :app:installDebug) y un emulador arrancado.
set -eu
cd "$(dirname "$0")/.."
source ~/.glaciertemp-app-env.sh
U="python3 tools/ui.py"
PKG=cl.umag.glaciertemp
adb emu geo fix -70.91 -53.16 34 >/dev/null
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
$U tap tool-sensors
$U tap sn-tab-compass
nota=$($U wait-text sn-declination WMM2025 60)
echo "nota: $nota"
case "$nota" in *"per year"*) ;; *) echo "FALLA: falta la variacion anual"; exit 1;; esac
$U scroll-to sn-decl-calc
$U tap sn-decl-calc
$U type sn-decl-date 1950-01-01
$U type sn-decl-lat -53.16
$U type sn-decl-lon -70.91
$U scroll-to sn-decl-table
tabla=$($U text sn-decl-table)
echo "tabla: $tabla"
# IGRF-14 en Punta Arenas, 1950.0: 17,16 grados E y +1,98'/ano (programa oficial igrf14.f)
for esperado in "IGRF-14 17.2° E 2.0′ E" "outside 2025–2030" "Android ("; do
  case "$tabla" in *"$esperado"*) ;; *) echo "FALLA: falta '$esperado'"; exit 1;; esac
done
echo "OK"
