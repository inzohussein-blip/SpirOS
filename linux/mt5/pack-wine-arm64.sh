#!/bin/bash
# ARM64 packages for SpirOS Linux, built inside Ubuntu 26.04 like SpirOS Linux:
#   wine-base-arm64.tar.xz  Hangover (Wine for ARM64; x86 code through FEX) + a
#                           Windows prefix, unpacked by SpirOS into /opt/spiros-wine
#   mt5-arm64.tar.xz        MetaTrader 5 (portable folder MT5/, into drive_c)
#   mt4-arm64.tar.xz        MetaTrader 4 (portable folder MT4/, 32-bit), if it runs
# Each program runs from the packed layout and must open its main window first.
# Usage (inside xvfb-run): linux/mt5/pack-wine-arm64.sh <hangover .deb dir> <dir with mt5-app.tar / mt4-app.tar> <out dir>
set -uo pipefail
DEBS="$1" APPS="$2" OUT="$3"
ROOT=/opt/spiros-wine
sudo mkdir -p "$ROOT" && sudo chown "$(id -u):$(id -g)" "$ROOT"
mkdir -p "$ROOT/hangover"
for d in "$DEBS"/*.deb; do dpkg-deb -x "$d" "$ROOT/hangover"; done
WINE=$(find "$ROOT/hangover" -path '*bin/wine' -type f | head -n 1)
SERVER="$(dirname "$WINE")/wineserver"
echo "wine: $WINE"; "$WINE" --version || exit 1
export WINEPREFIX="$ROOT/prefix" WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
"$WINE" wineboot -u; "$SERVER" -w
for k in 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\AeDebug' 'HKLM\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\AeDebug'; do
    "$WINE" reg delete "$k" /v Debugger /f || true
done
"$SERVER" -w
rm -rf "$WINEPREFIX/drive_c/users/${USER:-root}/Temp/"* 2>/dev/null || true
tar --numeric-owner -C "$ROOT" -cf - hangover prefix | xz -T0 -6 > "$OUT/wine-base-arm64.tar.xz" || exit 1
du -h "$OUT/wine-base-arm64.tar.xz"

# opens <id> <folder> <exe> <env>: runs it and tells whether its main window opened.
opens() {
    local id="$1" folder="$2" exe="$3" env="$4"
    ( cd "$WINEPREFIX/drive_c/$folder" && env $env WINEDEBUG=err+all,fixme-all "$WINE" "$exe" /portable > "$OUT/$id-arm64.log" 2>&1 & )
    sleep 200
    import -window root "$OUT/$id-arm64.png" || true
    xwininfo -root -tree | grep -E '^ +0x[0-9a-f]+ "' | grep -v '"Default IME"' | tee "$OUT/windows-$id-arm64.txt"
    "$SERVER" -k || true; sleep 3
    # A real main window of the terminal (not an error box): wide, owned by its exe.
    awk -v e="\"$exe\"" 'index($0, e) { split($0, a, /[ x+]+/); for (i in a) if (a[i] ~ /^[0-9]+$/ && a[i]+0 >= 800) { ok=1 } } END { exit !ok }' "$OUT/windows-$id-arm64.txt"
}

for id in mt5 mt4; do
    [ -f "$APPS/$id-app.tar" ] || { echo "$id: no x86-64 install"; continue; }
    folder=$(echo "$id" | tr a-z A-Z); exe=terminal64.exe; [ "$id" = mt4 ] && exe=terminal.exe
    tar -xf "$APPS/$id-app.tar" -C "$WINEPREFIX/drive_c"
    ok=""
    # 32-bit MT4 can run through FEX or Box64 inside Hangover: keep the one that works.
    for env in "SPIROS=1" "HODLL=libwow64fex.dll" "HODLL=wowbox64.dll"; do
        [ "$id" = mt5 ] && [ "$env" != "SPIROS=1" ] && break
        if opens "$id" "$folder" "$exe" "$env"; then ok="$env"; break; fi
        echo "$id: did not open with $env"
    done
    if [ -z "$ok" ]; then echo "${id}_FAILED"; continue; fi
    echo "$id: opens ($ok)"
    echo "$ok" > "$WINEPREFIX/drive_c/$folder/spiros.env"
    rm -rf "$WINEPREFIX/drive_c/$folder/logs/"* 2>/dev/null || true
    tar --numeric-owner -C "$WINEPREFIX/drive_c" -cf - "$folder" | xz -T0 -6 > "$OUT/$id-arm64.tar.xz"
    du -h "$OUT/$id-arm64.tar.xz"
done
[ -f "$OUT/mt5-arm64.tar.xz" ] || { echo "MT5 did not open on ARM64"; exit 1; }
