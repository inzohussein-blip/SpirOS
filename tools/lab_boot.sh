#!/bin/sh
# Boots the Lab's Alpine guest headless and waits for a shell or login prompt.
# Mirrors app/src/main/java/com/spiros/app/engine/LabVm.kt; keep them in sync.
#
# Usage: sh lab_boot.sh <qemu-binary> <lib-dir or ""> <work-dir> [timeout-seconds]
# <work-dir> must contain vmlinuz-virt, initramfs-virt and qemu-data/ (keymaps).
# Works with Android's toybox sh and with a desktop /bin/sh.
QEMU="$1"
LIB="$2"
WORK="$3"
LIMIT="${4:-900}"
MIRROR=http://dl-cdn.alpinelinux.org/alpine/v3.20

cd "$WORK" || exit 2
rm -f boot.log lab.vnc
[ -n "$LIB" ] && export LD_LIBRARY_PATH="$LIB"
export TMPDIR="$WORK"

"$QEMU" -L qemu-data \
    -M virt,gic-version=3 -cpu max -smp 2 -m 512 -accel tcg,thread=multi \
    -display none -nodefaults -serial stdio -monitor none \
    -kernel vmlinuz-virt -initrd initramfs-virt \
    -append "console=tty0 console=ttyAMA0 ip=dhcp alpine_repo=$MIRROR/main modloop=$MIRROR/releases/aarch64/netboot/modloop-virt" \
    -netdev user,id=net0 -device virtio-net-device,netdev=net0 -device virtio-rng-device \
    -device virtio-gpu-device -device virtio-keyboard-device -device virtio-tablet-device \
    -vnc unix:lab.vnc \
    </dev/null >boot.log 2>&1 &
PID=$!

i=0
while [ "$i" -lt "$LIMIT" ]; do
    if grep -qE 'login:|Welcome to Alpine|recovery shell|~ #' boot.log; then
        echo "BOOT_OK after ${i}s"
        kill "$PID" 2>/dev/null
        cat boot.log
        exit 0
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "QEMU_EXITED after ${i}s"
        cat boot.log
        exit 1
    fi
    sleep 5
    i=$((i + 5))
done
echo "BOOT_TIMEOUT after ${LIMIT}s"
kill "$PID" 2>/dev/null
cat boot.log
exit 1
