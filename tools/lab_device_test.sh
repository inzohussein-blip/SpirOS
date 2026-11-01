#!/usr/bin/env bash
# Runs an in-app self-test (debug builds) on a connected Android device or emulator
# and prints its log. Exit code 0 only on "RESULT PASS".
# Usage: tools/lab_device_test.sh <apk> [package] [activity] [log name] [max minutes]
#   Lab (default):   tools/lab_device_test.sh SpirOS-x86_64.apk
#   Linux desktop:   tools/lab_device_test.sh SpirOS-Linux-x86_64.apk com.spiros.app.speed .LinuxSelfTestActivity linux-selftest.log 25
set -uo pipefail
APK="$1"
PKG="${2:-com.spiros.app}"
ACTIVITY="${3:-.LabSelfTestActivity}"
LOGNAME="${4:-lab-selftest.log}"
MINUTES="${5:-17}"
LOG=/data/user/0/$PKG/no_backup/$LOGNAME

# The emulator's network sometimes comes up late; wait for it (up to 2 minutes).
for _ in $(seq 1 24); do
    adb shell ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1 && break
    sleep 5
done
adb shell ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1 || echo "WARNING: emulator has no network"

adb install -r "$APK" || exit 1
echo "device ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
adb shell am start -W -n "$PKG/com.spiros.app${ACTIVITY}" || exit 1

for _ in $(seq 1 $((MINUTES * 10))); do
    sleep 6
    OUT=$(adb shell run-as "$PKG" cat "$LOG" 2>/dev/null | tr -d '\r')
    if echo "$OUT" | grep -q '^RESULT '; then
        echo "$OUT"
        if echo "$OUT" | grep -q '^RESULT PASS'; then exit 0; fi
        echo "== crash details (logcat)"
        adb logcat -d -b crash,main 2>/dev/null | grep -E -A40 'F DEBUG|CANNOT LINK|libqemu|libproot|Fatal signal' | head -n 120
        exit 1
    fi
done
echo "Self-test did not finish in time. Partial log:"
adb shell run-as "$PKG" cat "$LOG"
exit 1
