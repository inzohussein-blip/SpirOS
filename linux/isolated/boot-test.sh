#!/bin/bash
# Boots the image exactly like SpirOS does: a LUKS-encrypted qcow2 overlay on top
# of the base, opened with a secret, direct kernel boot, user-mode network.
# Usage: linux/isolated/boot-test.sh <dir with the images>
set -uo pipefail
D="$1"
cd "$D"
head -c 32 /dev/urandom | base64 > key.txt
qemu-img create -q -f qcow2 -b debian-arm64.qcow2 -F qcow2 \
    --object secret,id=sec0,file=key.txt -o encrypt.format=luks,encrypt.key-secret=sec0 disk.qcow2 8G
qemu-img info disk.qcow2 | grep -i -E "encrypt|backing" || true
timeout 1500 qemu-system-aarch64 -M virt -cpu max -smp 4 -m 2048 -nographic -no-reboot \
    -accel tcg,thread=multi \
    -object secret,id=sec0,file=key.txt \
    -blockdev "driver=qcow2,node-name=d0,file.driver=file,file.filename=disk.qcow2,encrypt.format=luks,encrypt.key-secret=sec0" \
    -device virtio-blk-device,drive=d0 \
    -netdev user,id=n0 -device virtio-net-device,netdev=n0 -device virtio-rng-device \
    -kernel vmlinuz-arm64 -initrd initrd-arm64.img -append "root=/dev/vda rw console=ttyAMA0 quiet" \
    > boot.log 2>&1 &
PID=$!
for i in $(seq 1 300); do
    grep -q SPIROS_ISOLATED_READY boot.log && break
    kill -0 $PID 2>/dev/null || break
    sleep 5
done
kill $PID 2>/dev/null
tail -n 40 boot.log
grep SPIROS_ISOLATED_READY boot.log && echo "boot: $((i * 5)) s"
rm -f disk.qcow2 key.txt
grep -q SPIROS_ISOLATED_READY boot.log
