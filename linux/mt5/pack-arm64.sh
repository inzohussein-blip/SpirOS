#!/bin/bash
# "MetaTrader 5 full" for ARM64 phones: Hangover (Wine built for ARM64, running x86-64
# Windows code through FEX; MT5's protection rejects Box64) unpacked into
# /opt/spiros-mt5/hangover, a Windows prefix, and MT5 (installed on x86-64) as a
# portable folder. Runs the packed copy exactly as SpirOS will, then packs it.
# Usage (inside xvfb-run): linux/mt5/pack-arm64.sh <hangover .deb dir> <mt5-full.tar> <out dir>
set -euo pipefail
DEBS="$1" PKG="$2" OUT="$3"
ROOT=/opt/spiros-mt5
sudo mkdir -p "$ROOT" && sudo chown "$(id -u):$(id -g)" "$ROOT"
mkdir -p "$ROOT/hangover"
for d in "$DEBS"/*.deb; do dpkg-deb -x "$d" "$ROOT/hangover"; done
WINE="$ROOT/hangover/usr/bin/wine"
[ -x "$WINE" ] || WINE=$(find "$ROOT/hangover" -path '*bin/wine' -type f | head -n 1)
echo "wine: $WINE"; "$WINE" --version
SERVER="$(dirname "$WINE")/wineserver"
export WINEPREFIX="$ROOT/prefix" WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
"$WINE" wineboot -u; "$SERVER" -w
for k in 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\AeDebug' 'HKLM\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\AeDebug'; do
    "$WINE" reg delete "$k" /v Debugger /f || true
done
"$SERVER" -w
mkdir -p /tmp/pkg && tar -xf "$PKG" -C /tmp/pkg "prefix/drive_c/Program Files/MetaTrader 5"
mkdir -p "$WINEPREFIX/drive_c/MT5" && cp -a "/tmp/pkg/prefix/drive_c/Program Files/MetaTrader 5/." "$WINEPREFIX/drive_c/MT5/"

echo "== Run the packed copy"
cd "$WINEPREFIX/drive_c/MT5"
( WINEDEBUG=err+all,fixme-all "$WINE" terminal64.exe /portable > "$OUT/mt5-arm64.log" 2>&1 & )
for t in 60 120 180 240; do
    sleep 60
    import -window root "$OUT/mt5-arm64-$t.png" || true
done
xwininfo -root -tree | grep -E '^ +0x[0-9a-f]+ "' | grep -v '"Default IME"' | tee "$OUT/windows-arm64.txt" || true
"$SERVER" -k || true; sleep 3
grep -q '"MetaTrader 5' "$OUT/windows-arm64.txt"

echo "== Pack"
cd /
rm -rf "$WINEPREFIX/drive_c/users/${USER:-root}/Temp/"* "$WINEPREFIX/drive_c/MT5/logs/"* 2>/dev/null || true
tar --numeric-owner -C "$ROOT" -cf - hangover prefix | xz -T0 -6 > "$OUT/mt5-full-arm64.tar.xz"
du -h "$OUT/mt5-full-arm64.tar.xz"
