#!/bin/bash
# MT5 on ARM64 with Hangover (Wine built for ARM64; x86-64 Windows code runs through
# FEX inside Wine, no Box64): MT5's protection rejects Box64.
# Usage (inside xvfb-run): linux/mt5/run-hangover.sh <mt5-full.tar> <out dir>
set -u
PKG="$1" OUT="$2"
export WINEPREFIX="$HOME/.wine-mt5" WINEDEBUG=err+all,fixme-all WINEDLLOVERRIDES="mscoree,mshtml="
wine --version
wineboot -u; wineserver -w
for k in 'HKLM\Software\Microsoft\Windows NT\CurrentVersion\AeDebug' 'HKLM\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\AeDebug'; do
    wine reg delete "$k" /v Debugger /f || true
done
wineserver -w
# MetaTrader 5 as installed on x86-64 (portable folder).
mkdir -p /tmp/pkg && tar -xf "$PKG" -C /tmp/pkg "prefix/drive_c/Program Files/MetaTrader 5"
mkdir -p "$WINEPREFIX/drive_c/MT5" && cp -a "/tmp/pkg/prefix/drive_c/Program Files/MetaTrader 5/." "$WINEPREFIX/drive_c/MT5/"
cd "$WINEPREFIX/drive_c/MT5"
( wine terminal64.exe /portable > "$OUT/mt5-hangover.log" 2>&1 & )
for t in 60 120 180 240 300 420; do
    sleep 60
    import -window root "$OUT/mt5-hangover-$t.png" || true
    pgrep -f terminal64 >/dev/null && echo "t=$t MT5 running" || echo "t=$t MT5 not running"
done
xwininfo -root -tree | grep -E '^ +0x[0-9a-f]+ "' | grep -v '"Default IME"' | tee "$OUT/windows-hangover.txt" || true
wineserver -k || true
grep -q '"MetaTrader 5' "$OUT/windows-hangover.txt"
