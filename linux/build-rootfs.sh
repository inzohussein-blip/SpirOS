#!/bin/sh
# Builds a SpirOS Linux root filesystem: Ubuntu 24.04 LTS (the base of Linux Mint 22)
# with one desktop in Linux Mint style, natively for the runner's architecture, packed
# as a .tar.xz that the SpirOS Engine extracts and runs with proot.
# Usage (as root): linux/build-rootfs.sh <arm64|amd64> <xfce|mate|cinnamon> <output.tar.xz>
set -eu
ARCH="$1"
DESKTOP="$2"
OUT="$3"
SUITE=noble
LLAMA_TAG=b6000
MINT_SUITE=wilma
if [ "$ARCH" = amd64 ]; then MIRROR=http://archive.ubuntu.com/ubuntu; SECURITY=http://security.ubuntu.com/ubuntu
else MIRROR=http://ports.ubuntu.com/ubuntu-ports; SECURITY=$MIRROR; fi
ROOT=$(mktemp -d)/rootfs
HERE=$(cd "$(dirname "$0")" && pwd)
[ -f "$HERE/desktops/$DESKTOP.txt" ] || { echo "unknown desktop: $DESKTOP"; exit 1; }

debootstrap --arch="$ARCH" --variant=minbase --components=main,universe "$SUITE" "$ROOT" "$MIRROR"

cat > "$ROOT/etc/apt/sources.list" <<SRC
deb $MIRROR $SUITE main restricted universe multiverse
deb $MIRROR $SUITE-updates main restricted universe multiverse
deb $SECURITY $SUITE-security main restricted universe multiverse
SRC
# Inside proot there is no init system; never try to start services while installing.
printf '#!/bin/sh\nexit 101\n' > "$ROOT/usr/sbin/policy-rc.d"
chmod +x "$ROOT/usr/sbin/policy-rc.d"
# Ubuntu's snap versions of apps cannot run without systemd: never pull snapd in.
mkdir -p "$ROOT/etc/apt/preferences.d"
printf 'Package: snapd\nPin: release a=*\nPin-Priority: -10\n' > "$ROOT/etc/apt/preferences.d/no-snap.pref"

list() { grep -v '^#' "$1" | grep -v '^$' | tr '\n' ' '; }
PACKAGES="$(list "$HERE/packages.txt") $(list "$HERE/desktops/$DESKTOP.txt")"
mount -t proc proc "$ROOT/proc"
mount --bind /dev "$ROOT/dev"
trap 'umount "$ROOT/proc" "$ROOT/dev" 2>/dev/null || true' EXIT
chroot "$ROOT" sh -c "export DEBIAN_FRONTEND=noninteractive
    set -e
    apt-get update
    # Skip (and report) any package this release does not have instead of failing.
    WANT=''
    for p in $PACKAGES; do
        if apt-cache show \$p >/dev/null 2>&1; then WANT=\"\$WANT \$p\"; else echo \"WARNING: package \$p not found, skipped\"; fi
    done
    apt-get install -y --no-install-recommends \$WANT
    apt-get clean
    locale-gen ar_SA.UTF-8 en_US.UTF-8"

# ---- Linux Mint look: Mint-Y themes and icons from the official Mint repository ----
# (architecture-independent packages; their dependencies come from Ubuntu).
python3 - "$ROOT/tmp" "$MINT_SUITE" <<'PY' || echo "WARNING: Mint themes unavailable, using the desktop's default look"
import gzip, hashlib, sys, urllib.request
out, suite = sys.argv[1], sys.argv[2]
base = "https://packages.linuxmint.com/"
index = gzip.decompress(urllib.request.urlopen(f"{base}dists/{suite}/main/binary-amd64/Packages.gz", timeout=60).read()).decode()
best = {}
for block in index.split("\n\n"):
    f = dict(l.split(": ", 1) for l in block.splitlines() if ": " in l and not l.startswith(" "))
    if f.get("Package") in ("mint-themes", "mint-y-icons", "mint-x-icons") and f.get("Architecture") == "all":
        best[f["Package"]] = f  # later entries are newer
for name in ("mint-themes", "mint-y-icons"):
    f = best[name]
    data = urllib.request.urlopen(base + f["Filename"], timeout=120).read()
    assert hashlib.sha256(data).hexdigest() == f["SHA256"], f"checksum mismatch for {name}"
    open(f"{out}/{name}.deb", "wb").write(data)
    print("fetched", name, f["Version"])
PY
if ls "$ROOT"/tmp/mint-*.deb >/dev/null 2>&1; then
    chroot "$ROOT" sh -c "export DEBIAN_FRONTEND=noninteractive
        apt-get install -y --no-install-recommends /tmp/mint-themes.deb /tmp/mint-y-icons.deb && apt-get clean" \
        || echo "WARNING: Mint themes could not be installed"
fi
pick() { # first existing name among the arguments, in a directory
    dir="$1"; shift
    for n in "$@"; do [ -d "$ROOT/$dir/$n" ] && { echo "$n"; return; }; done
    echo ""
}
GTK=$(pick usr/share/themes Mint-Y-Dark-Aqua Mint-Y-Dark Mint-Y)
ICONS=$(pick usr/share/icons Mint-Y-Aqua Mint-Y)
echo "Mint look: gtk=${GTK:-default} icons=${ICONS:-default}"

# ---- desktop defaults (Mint layout and theme) ----
case "$DESKTOP" in
xfce)
    X="$ROOT/etc/xdg/xfce4/xfconf/xfce-perchannel-xml"
    mkdir -p "$X" "$ROOT/etc/xdg/xfce4/panel"
    if [ -n "$GTK" ]; then
        cat > "$X/xsettings.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="$GTK"/>
    <property name="IconThemeName" type="string" value="${ICONS:-Adwaita}"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="Noto Sans 10"/>
  </property>
</channel>
XML
    fi
    # One bottom panel like Mint Xfce: menu, window list, tray, clock, show desktop.
    cat > "$ROOT/etc/xdg/xfce4/panel/default.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=8;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="40"/>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
        <value type="int" value="5"/>
        <value type="int" value="6"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="whiskermenu"/>
    <property name="plugin-2" type="string" value="tasklist"/>
    <property name="plugin-3" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-4" type="string" value="systray"/>
    <property name="plugin-5" type="string" value="clock"/>
    <property name="plugin-6" type="string" value="showdesktop"/>
  </property>
</channel>
XML
    if [ -n "$GTK" ]; then
        cat > "$X/xfwm4.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="$GTK"/>
  </property>
</channel>
XML
    fi
    ;;
mate)
    cat > "$ROOT/usr/share/glib-2.0/schemas/99_spiros.gschema.override" <<OV
[org.mate.interface]
gtk-theme='${GTK:-Menta}'
icon-theme='${ICONS:-menta}'
[org.mate.Marco.general]
theme='${GTK:-Menta}'
compositing-manager=false
[org.mate.panel]
default-layout='linuxmint'
OV
    chroot "$ROOT" glib-compile-schemas /usr/share/glib-2.0/schemas
    ;;
cinnamon)
    cat > "$ROOT/usr/share/glib-2.0/schemas/99_spiros.gschema.override" <<OV
[org.cinnamon.desktop.interface]
gtk-theme='${GTK:-Adwaita}'
icon-theme='${ICONS:-Adwaita}'
[org.cinnamon.desktop.wm.preferences]
theme='${GTK:-Adwaita}'
[org.cinnamon.theme]
name='${GTK:-cinnamon}'
[org.cinnamon]
desktop-effects-workspace=false
OV
    chroot "$ROOT" glib-compile-schemas /usr/share/glib-2.0/schemas
    ;;
esac

# Local AI: llama.cpp's server and CLI, built from a pinned release for a generic CPU
# of this architecture (works on older phone cores without dotprod).
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
rm -f "$ROOT/etc/resolv.conf"
printf 'nameserver 9.9.9.9\nnameserver 1.1.1.1\n' > "$ROOT/etc/resolv.conf"
echo spiros > "$ROOT/etc/hostname"
echo "$DESKTOP" > "$ROOT/etc/spiros-desktop"
rm -rf "$ROOT/var/lib/apt/lists/"* "$ROOT/var/cache/apt/"*.bin "$ROOT/tmp/"* "$ROOT/usr/share/doc/"*
date -u +%Y-%m-%dT%H:%MZ > "$ROOT/etc/spiros-build"

# Hard links become separate files so the Android extractor only handles symlinks.
tar --numeric-owner --hard-dereference -C "$ROOT" -cf - . | xz -T0 -6 > "$OUT"
du -h "$OUT"
