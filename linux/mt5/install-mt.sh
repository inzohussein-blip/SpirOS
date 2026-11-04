#!/bin/bash
# Installs MetaTrader 5 and MetaTrader 4 with their official installers into a Wine
# prefix on x86-64 (the installers are unreliable under emulation), checks each one
# opens, and packs each program folder (portable mode) for the ARM64 packages.
# Usage (inside xvfb-run): linux/mt5/install-mt.sh <out dir>
set -uo pipefail
OUT="$1"
WINE_URL="${WINE_URL:-$(cat "$(dirname "$0")/wine-url.txt")}"
MT5_URL="https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe"
MT4_URL="https://download.mql5.com/cdn/web/metaquotes.software.corp/mt4/mt4setup.exe"
ROOT=/opt/spiros-mtbuild
sudo mkdir -p "$ROOT" && sudo chown "$(id -u):$(id -g)" "$ROOT"
export WINEPREFIX="$ROOT/prefix" WINEDEBUG=-all WINEARCH=win64 WINEDLLOVERRIDES="mscoree,mshtml="
mkdir -p "$ROOT/wine"
curl -fsSL "$WINE_URL" | tar -xJ -C "$ROOT/wine" --strip-components=1 || exit 1
WINE="$ROOT/wine/bin/wine" SERVER="$ROOT/wine/bin/wineserver"
"$WINE" --version
"$WINE" wineboot -u; "$SERVER" -w
for k in 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\AeDebug' 'HKLM\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\AeDebug'; do
    "$WINE" reg delete "$k" /v Debugger /f || true
done
"$SERVER" -w

# install <id> <installer url> <program exe> <folder name in the package>
install() {
    local id="$1" url="$2" exe="$3" folder="$4" term="" dir
    echo "== $id: official installer"
    curl -fsSL -o "/tmp/$id-setup.exe" "$url" || { echo "$id: download failed"; return 1; }
    ( "$WINE" "/tmp/$id-setup.exe" /auto >/dev/null 2>&1 & )
    for i in $(seq 1 120); do
        term=$(find "$WINEPREFIX/drive_c" -path '*/windows/*' -prune -o -iname "$exe" -print 2>/dev/null | head -n 1)
        [ -n "$term" ] && break
        sleep 10
    done
    [ -n "$term" ] || { echo "$id: not installed"; import -window root "$OUT/$id-install-failed.png"; "$SERVER" -k; return 1; }
    sleep 60; "$SERVER" -k || true; sleep 3
    dir=$(dirname "$term")
    echo "$id: $dir"
    echo "== $id: first start (portable)"
    ( cd "$dir" && "$WINE" "$exe" /portable >/dev/null 2>&1 & )
    sleep 150
    import -window root "$OUT/$id-amd64.png" || true
    xwininfo -root -tree | grep -E '^ +0x[0-9a-f]+ "' | grep -v '"Default IME"' | tee "$OUT/windows-$id-amd64.txt"
    "$SERVER" -k || true; sleep 3
    rm -rf "$dir/logs/"* 2>/dev/null || true
    tar --numeric-owner -C "$(dirname "$dir")" --transform "s,^$(basename "$dir"),$folder," -cf "$OUT/$id-app.tar" "$(basename "$dir")"
    du -h "$OUT/$id-app.tar"
}

install mt5 "$MT5_URL" terminal64.exe MT5 || exit 1
grep -q '"MetaTrader 5' "$OUT/windows-mt5-amd64.txt" || { echo "MT5 did not open"; exit 1; }
install mt4 "$MT4_URL" terminal.exe MT4 || echo "MT4_FAILED"
exit 0
