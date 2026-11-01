#!/usr/bin/env bash
# Runs the in-app Lab self-test (debug builds) on a connected Android device or
# emulator and prints its log. Exit code 0 only if the guest booted.
# Usage: tools/lab_device_test.sh <apk>
set -uo pipefail
APK="$1"
PKG=com.spiros.app
LOG=/data/user/0/$PKG/no_backup/lab-selftest.log

adb install -r "$APK" || exit 1
echo "device ABI: $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
adb shell am start -W -n "$PKG/.LabSelfTestActivity" || exit 1

for _ in $(seq 1 170); do
    sleep 6
    OUT=$(adb shell run-as "$PKG" cat "$LOG" 2>/dev/null | tr -d '\r')
    if echo "$OUT" | grep -q '^RESULT '; then
        echo "$OUT"
        if echo "$OUT" | grep -q '^RESULT PASS'; then exit 0; fi
        echo "== crash details (logcat)"
        adb logcat -d -b crash,main 2>/dev/null | grep -E -A40 'F DEBUG|CANNOT LINK|libqemu|Fatal signal' | head -n 120
        exit 1
    fi
done
echo "Self-test did not finish in time. Partial log:"
adb shell run-as "$PKG" cat "$LOG"
exit 1
