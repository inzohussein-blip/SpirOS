#!/bin/bash
# Builds the "MetaTrader 5 full" package for SpirOS Linux on an ARM64 Linux machine:
# Wine (x86-64, WoW64) run through Box64, a Windows prefix with MetaTrader 5 already
# installed by its official installer, then packed as mt5-full.tar.xz. The same
# package runs on ARM phones (through Box64) and x86-64 phones (Wine directly).
# Proof: a screenshot of MT5 running here is saved as mt5-arm64.png.
# Usage (inside xvfb-run): linux/mt5/build-mt5.sh <output dir>
set -euo pipefail
OUT="$1"
WINE_URL="https://github.com/Kron4ek/Wine-Builds/releases/download/10.0/wine-10.0-amd64-wow64.tar.xz"
MT5_URL="https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe"
ROOT=/opt/spiros-mt5
sudo mkdir -p "$ROOT" && sudo chown "$(id -u):$(id -g)" "$ROOT"
export WINEPREFIX="$ROOT/prefix" WINEDEBUG=-all WINEARCH=win64 WINEDLLOVERRIDES="mscoree,mshtml="
export BOX64_LOG=0 BOX64_NOBANNER=1
RUN=""
[ "$(uname -m)" = aarch64 ] && RUN=box64

echo "== Wine"
mkdir -p "$ROOT/wine"
curl -fsSL "$WINE_URL" | tar -xJ -C "$ROOT/wine" --strip-components=1
WINE="$ROOT/wine/bin/wine"
$RUN "$WINE" --version

echo "== Windows prefix"
$RUN "$WINE" wineboot -u
$RUN "$ROOT/wine/bin/wineserver" -w || true

echo "== MetaTrader 5 (official installer, silent)"
curl -fsSL -o /tmp/mt5setup.exe "$MT5_URL"
( $RUN "$WINE" /tmp/mt5setup.exe /auto & )
for i in $(seq 1 180); do
    TERMINAL=$(find "$WINEPREFIX/drive_c" -iname terminal64.exe 2>/dev/null | head -n 1 || true)
    [ -n "$TERMINAL" ] && break
    sleep 10
done
[ -n "${TERMINAL:-}" ] || { echo "MT5 was not installed"; exit 1; }
echo "installed: $TERMINAL"
sleep 60 # let the installer finish and start the terminal once

echo "== Run MT5 and take a screenshot"
$RUN "$ROOT/wine/bin/wineserver" -k || true
sleep 3
ARCH=$(dpkg --print-architecture)
( cd "$(dirname "$TERMINAL")" && WINEDEBUG=err+all,fixme-all BOX64_LOG=1 $RUN "$WINE" "$TERMINAL" > "$OUT/mt5-run-$ARCH.log" 2>&1 & )
for t in 30 60 90 120 180; do
    sleep 30
    import -window root "$OUT/mt5-$ARCH-$t.png" || true
    pgrep -fa terminal64 >/dev/null && echo "t=$t MT5 running" || echo "t=$t MT5 not running"
done
pgrep -fa terminal64 && echo "MT5_RUNNING" || echo "MT5_NOT_RUNNING"
echo "== MT5 log (last lines)"; tail -n 80 "$OUT/mt5-run-$ARCH.log" || true
$RUN "$ROOT/wine/bin/wineserver" -k || true
sleep 5

echo "== Pack"
rm -rf "$WINEPREFIX/drive_c/users/$USER/Temp/"* 2>/dev/null || true
tar --numeric-owner -C "$ROOT" -cf - wine prefix | xz -T0 -6 > "$OUT/mt5-full.tar.xz"
du -h "$OUT/mt5-full.tar.xz"
