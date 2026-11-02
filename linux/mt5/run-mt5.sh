#!/bin/bash
# Runs an MT5 package (mt5-full.tar, built on x86-64) on this machine: through Box64
# on ARM64. Proof: screenshots and the list of open windows in <out>.
# Usage (inside xvfb-run): linux/mt5/run-mt5.sh <mt5-full.tar> <out dir>
set -u
PKG="$1" OUT="$2"
ROOT=/opt/spiros-mt5
sudo mkdir -p "$ROOT" && sudo chown "$(id -u):$(id -g)" "$ROOT"
tar -xf "$PKG" -C "$ROOT"
export WINEPREFIX="$ROOT/prefix" WINEDEBUG=err+all,fixme-all BOX64_NOBANNER=1
RUN=""; [ "$(uname -m)" = aarch64 ] && RUN=box64
ARCH=$(dpkg --print-architecture)
TERMINAL=$(find "$WINEPREFIX/drive_c" -iname terminal64.exe | head -n 1)
echo "terminal: $TERMINAL"
( cd "$(dirname "$TERMINAL")" && $RUN "$ROOT/wine/bin/wine" "$TERMINAL" > "$OUT/mt5-run-$ARCH.log" 2>&1 & )
for t in 60 120 180 240 300; do
    sleep 60
    import -window root "$OUT/mt5-$ARCH-$t.png" || true
    pgrep -f terminal64 >/dev/null && echo "t=$t MT5 running" || echo "t=$t MT5 not running"
done
xwininfo -root -tree | grep -E '^ +0x[0-9a-f]+ "' | grep -v '"Default IME"' | tee "$OUT/windows-$ARCH.txt" || true
$RUN "$ROOT/wine/bin/wineserver" -k || true
grep -q '"MetaTrader 5' "$OUT/windows-$ARCH.txt"
