#!/bin/sh
# Builds the SpirOS Linux root filesystem (Debian + Xfce) natively for the runner's
# architecture and packs it as a .tar.xz that the app extracts and runs with proot.
# Usage (as root): linux/build-rootfs.sh <arm64|amd64> <output.tar.xz>
set -eu
ARCH="$1"
OUT="$2"
SUITE=trixie
LLAMA_TAG=b6000
MIRROR=http://deb.debian.org/debian
ROOT=$(mktemp -d)/rootfs
HERE=$(cd "$(dirname "$0")" && pwd)

debootstrap --arch="$ARCH" --variant=minbase "$SUITE" "$ROOT" "$MIRROR"

cat > "$ROOT/etc/apt/sources.list" <<SRC
deb $MIRROR $SUITE main contrib non-free non-free-firmware
deb $MIRROR $SUITE-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security $SUITE-security main contrib non-free non-free-firmware
SRC
# Inside proot there is no init system; never try to start services while installing.
printf '#!/bin/sh\nexit 101\n' > "$ROOT/usr/sbin/policy-rc.d"
chmod +x "$ROOT/usr/sbin/policy-rc.d"

PACKAGES=$(grep -v '^#' "$HERE/packages.txt" | grep -v '^$' | tr '\n' ' ')
mount -t proc proc "$ROOT/proc"
mount --bind /dev "$ROOT/dev"
trap 'umount "$ROOT/proc" "$ROOT/dev" 2>/dev/null || true' EXIT
chroot "$ROOT" sh -c "export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends $PACKAGES
    apt-get clean
    sed -i 's/^# *\(ar_SA.UTF-8\|en_US.UTF-8\)/\1/' /etc/locale.gen && locale-gen"

# Local AI (phase L4): llama.cpp's server and CLI, built from a pinned release for a
# generic CPU of this architecture (works on older phone cores without dotprod).
chroot "$ROOT" sh -c "export DEBIAN_FRONTEND=noninteractive
    set -e
    apt-get install -y --no-install-recommends build-essential cmake
    git clone --depth 1 --branch $LLAMA_TAG https://github.com/ggml-org/llama.cpp /tmp/llama
    cmake -S /tmp/llama -B /tmp/llama/build -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
        -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TESTS=OFF
    cmake --build /tmp/llama/build -j\$(nproc) --target llama-server llama-cli
    install -m 755 /tmp/llama/build/bin/llama-server /tmp/llama/build/bin/llama-cli /usr/local/bin/
    rm -rf /tmp/llama
    apt-get purge -y build-essential cmake
    apt-get autoremove -y
    apt-get clean
    mkdir -p /root/models
    llama-server --version"
umount "$ROOT/proc" "$ROOT/dev"
trap - EXIT

cp -a "$HERE/overlay/." "$ROOT/"
# The desktop launcher lives with the app (which refreshes it before every start).
install -D -m 755 "$HERE/../app/src/main/assets/linux/spiros-desktop" "$ROOT/usr/local/bin/spiros-desktop"
# DNS for proot: Android has no /etc/resolv.conf to bind, so ship one.
printf 'nameserver 9.9.9.9\nnameserver 1.1.1.1\n' > "$ROOT/etc/resolv.conf"
echo spiros > "$ROOT/etc/hostname"
rm -rf "$ROOT/var/lib/apt/lists/"* "$ROOT/var/cache/apt/"*.bin "$ROOT/tmp/"* "$ROOT/usr/share/doc/"*
date -u +%Y-%m-%dT%H:%MZ > "$ROOT/etc/spiros-build"

# Hard links become separate files so the Android extractor only handles symlinks.
tar --numeric-owner --hard-dereference -C "$ROOT" -cf - . | xz -T0 -6 > "$OUT"
du -h "$OUT"
