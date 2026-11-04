#!/bin/bash
# SpirOS isolated mode: a Debian 13 (trixie) arm64 system that runs as a real virtual
# machine (QEMU) on the phone, fully separate from Android and from SpirOS Linux.
# Output: debian-arm64.qcow2 (the shared, read-only base; the phone writes to its own
# LUKS-encrypted overlay on top of it), vmlinuz-arm64 and initrd-arm64.img.
# Usage (as root, on an arm64 machine): linux/isolated/build-debian.sh <out dir>
set -euo pipefail
OUT="$1"
SUITE=trixie
MIRROR=http://deb.debian.org/debian
ROOT=$(mktemp -d)/root
mkdir -p "$OUT"

debootstrap --arch=arm64 --variant=minbase \
    --include=systemd-sysv,udev,linux-image-arm64,iproute2,ca-certificates,curl,nano,less,sudo,bash-completion,procps,kmod,locales,apt-utils,iputils-ping,dbus \
    "$SUITE" "$ROOT" "$MIRROR"

cat > "$ROOT/etc/apt/sources.list" <<SRC
deb $MIRROR $SUITE main contrib non-free-firmware
deb $MIRROR $SUITE-updates main contrib non-free-firmware
deb http://security.debian.org/debian-security $SUITE-security main contrib non-free-firmware
SRC
echo spiros-isolated > "$ROOT/etc/hostname"
printf '127.0.0.1 localhost\n127.0.1.1 spiros-isolated\n' > "$ROOT/etc/hosts"
echo '/dev/vda / ext4 defaults,noatime 0 1' > "$ROOT/etc/fstab"
# Network: QEMU's user-mode network gives DHCP; its DNS forwarder is 10.0.2.3.
mkdir -p "$ROOT/etc/systemd/network"
printf '[Match]\nName=en* eth*\n\n[Network]\nDHCP=yes\n' > "$ROOT/etc/systemd/network/20-wired.network"
rm -f "$ROOT/etc/resolv.conf"; echo 'nameserver 10.0.2.3' > "$ROOT/etc/resolv.conf"
chroot "$ROOT" systemctl enable systemd-networkd
# Log straight in as root on the serial console and the screen: the disk itself is
# encrypted with a key only the unlocked SpirOS vault holds.
for unit in serial-getty@ttyAMA0 getty@tty1; do
    mkdir -p "$ROOT/etc/systemd/system/$unit.service.d"
    printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin root --noclear %%I $TERM\n' > "$ROOT/etc/systemd/system/$unit.service.d/autologin.conf"
done
chroot "$ROOT" passwd -l root
# Readiness marker for SpirOS and the CI boot test.
cat > "$ROOT/etc/systemd/system/spiros-ready.service" <<'UNIT'
[Unit]
Description=Tell SpirOS the isolated system is up
After=multi-user.target systemd-networkd.service
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'echo "SPIROS_ISOLATED_READY $(uname -r) net=$(getent hosts deb.debian.org >/dev/null && echo ok || echo none)" > /dev/console'
[Install]
WantedBy=multi-user.target
UNIT
chroot "$ROOT" systemctl enable spiros-ready.service
cat > "$ROOT/etc/motd" <<'MOTD'

  SpirOS isolated mode: Debian 13 in its own virtual machine.
  Its disk is encrypted; nothing here can reach Android or SpirOS.
  وضع العزل في SpirOS: Debian 13 في آلة افتراضية مستقلة بقرص مشفّر.

MOTD
sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/; s/^# *ar_SA.UTF-8/ar_SA.UTF-8/' "$ROOT/etc/locale.gen"; chroot "$ROOT" locale-gen >/dev/null
chroot "$ROOT" apt-get clean
rm -rf "$ROOT/var/lib/apt/lists/"* "$ROOT/usr/share/doc/"* "$ROOT/usr/share/man/"* "$ROOT/tmp/"*

cp "$(ls "$ROOT"/boot/vmlinuz-* | head -n 1)" "$OUT/vmlinuz-arm64"
cp "$(ls "$ROOT"/boot/initrd.img-* | head -n 1)" "$OUT/initrd-arm64.img"
echo "system: $(du -sm "$ROOT" | cut -f1) MB"
truncate -s 8G /tmp/debian.raw
mkfs.ext4 -q -L spiros-isolated -d "$ROOT" /tmp/debian.raw
qemu-img convert -c -O qcow2 /tmp/debian.raw "$OUT/debian-arm64.qcow2"
rm -f /tmp/debian.raw
ls -la "$OUT"
