#!/usr/bin/env python3
"""
Bundle a prebuilt QEMU for Android into the APK (track B1).

QEMU and its libraries come from the Termux package repository (GPL / LGPL,
built with the Android NDK against bionic). This script:
  1. resolves qemu-system-aarch64-headless and its dependencies,
  2. downloads the .deb files and checks their SHA-256 from the signed index,
  3. extracts the executable and keeps only the shared libraries it really
     loads (transitive DT_NEEDED closure; fails if any is missing),
  4. renames everything to lib*.so (only such files are installed from an APK)
     and rewrites SONAME / DT_NEEDED *in place* so they find each other. The new
     names are always shorter, so no byte of the ELF layout moves. (patchelf was
     used before; it relayouts files and Android's loader then crashed QEMU with
     SIGSEGV before main.)
  5. writes them to app/src/main/jniLibs/arm64-v8a/.

At runtime SpirOS executes libqemu-system-aarch64.so from nativeLibraryDir
(the only app location Android 10+ allows executing from).

Requires: python3 only.
"""
import argparse
import hashlib
import io
import os
import re
import shutil
import struct
import sys
import tarfile
import tempfile
import urllib.request

# Termux's libslirp (QEMU's user-mode network) reads its upstream DNS servers from
# Termux's prefix, which does not exist in SpirOS. Point it, in place and with a
# path of equal or shorter length, at a file SpirOS writes before starting QEMU
# (LabVm.writeResolvConf). Without this the guest has no DNS at all.
TERMUX_RESOLV = b"/data/data/com.termux/files/usr/etc/resolv.conf"
SPIROS_RESOLV = b"/data/data/com.spiros.app/files/resolv.conf"

# Official repository first, then public mirrors (same signed index and SHA-256 sums).
MIRRORS = [
    "https://packages.termux.dev/apt/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://mirror.mwt.me/termux/main",
]
USER_AGENT = "SpirOS-build/1.0 (+https://github.com/inzohussein-blip/SpirOS)"
ROOT_PACKAGE = "qemu-system-aarch64-headless"
PREFIX = "data/data/com.termux/files/usr/"
EXECUTABLE = "bin/qemu-system-aarch64"
# Data QEMU reads at runtime (passed with -L). The VNC display refuses to start
# without a keyboard layout, so the keymaps are required.
DATA_DIRS = ("share/qemu/keymaps/",)
OUTPUT_EXECUTABLE = "libqemu-system-aarch64.so"
# Provided by Android itself; never bundled.
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def pick_mirror(arch: str):
    for mirror in MIRRORS:
        try:
            text = fetch(f"{mirror}/dists/stable/main/binary-{arch}/Packages").decode()
            return mirror, text
        except Exception as e:  # try the next mirror
            print(f"  ! {mirror}: {e}")
    sys.exit("No Termux mirror reachable")


def parse_index(text: str) -> dict:
    packages = {}
    for block in text.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if line and not line.startswith(" ") and ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if "Package" in fields:
            packages[fields["Package"]] = fields
    return packages


def dependencies(fields: dict) -> list:
    names = []
    for item in fields.get("Depends", "").split(","):
        item = item.strip()
        if item:
            names.append(re.split(r"[ (|]", item.split("|")[0].strip())[0])
    return names


def resolve(index: dict, root: str) -> list:
    seen, order, stack = set(), [], [root]
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        seen.add(name)
        if name not in index:
            print(f"  ! {name}: not in index (virtual or optional), skipped")
            continue
        order.append(name)
        stack.extend(dependencies(index[name]))
    return order


def deb_members(data: bytes):
    """Minimal ar(1) reader for .deb files."""
    assert data[:8] == b"!<arch>\n", "not a .deb"
    pos = 8
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        yield name, data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size % 2)


def extract(deb: bytes, dest: str):
    """Extract only regular files we need (libraries and the QEMU binary); never links."""
    wanted = (PREFIX + "lib/", PREFIX + EXECUTABLE) + tuple(PREFIX + d for d in DATA_DIRS)
    for name, payload in deb_members(deb):
        if not name.startswith("data.tar"):
            continue
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as tar:
            for member in tar:
                path = member.name.lstrip("./")
                if not member.isfile() or not path.startswith(wanted) or ".." in path.split("/"):
                    continue
                target = os.path.join(dest, path)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with tar.extractfile(member) as src, open(target, "wb") as out:
                    shutil.copyfileobj(src, out)
        return
    raise RuntimeError("data.tar not found in .deb")


def is_elf(path: str) -> bool:
    with open(path, "rb") as f:
        return f.read(4) == b"\x7fELF"


DT_NEEDED, DT_SONAME, DT_STRTAB, DT_STRSZ = 1, 14, 5, 10


def _dynamic(data: bytes):
    """Yields (tag, file_offset_of_string) for DT_NEEDED/DT_SONAME of a 64-bit LE ELF."""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF")
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    loads, dynamic = [], None
    for i in range(phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from("<IIQQQQQQ", data, phoff + i * phentsize)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return []

    def to_offset(vaddr):
        for va, off, size in loads:
            if va <= vaddr < va + size:
                return off + vaddr - va
        raise ValueError("address not in a PT_LOAD segment")

    strtab, refs = None, []
    for j in range(dynamic[1] // 16):
        tag, value = struct.unpack_from("<qQ", data, dynamic[0] + j * 16)
        if tag == 0:
            break
        if tag == DT_STRTAB:
            strtab = value
        elif tag in (DT_NEEDED, DT_SONAME):
            refs.append((tag, value))
    base = to_offset(strtab)
    return [(tag, base + value) for tag, value in refs]


def _string_at(data: bytes, offset: int) -> str:
    return data[offset:data.index(b"\0", offset)].decode()


def elf_needed(path: str) -> list:
    data = open(path, "rb").read()
    return [_string_at(data, off) for tag, off in _dynamic(data) if tag == DT_NEEDED]


def elf_soname(path: str):
    data = open(path, "rb").read()
    names = [_string_at(data, off) for tag, off in _dynamic(data) if tag == DT_SONAME]
    return names[0] if names else None


def redirect_resolv_conf(path: str) -> int:
    data = open(path, "rb").read()
    count = data.count(TERMUX_RESOLV + b"\0")
    if count:
        replacement = SPIROS_RESOLV + b"\0" * (len(TERMUX_RESOLV) - len(SPIROS_RESOLV) + 1)
        data = data.replace(TERMUX_RESOLV + b"\0", replacement)
        open(path, "wb").write(data)
    return count


def elf_rename(path: str, rename: dict):
    """Rewrites DT_NEEDED / DT_SONAME strings in place (new names are never longer)."""
    data = bytearray(open(path, "rb").read())
    for _, off in _dynamic(data):
        old = _string_at(data, off)
        new = rename.get(old)
        if new is None or new == old:
            continue
        if len(new) > len(old):
            raise ValueError(f"{new} is longer than {old}")
        data[off:off + len(old)] = new.encode() + b"\0" * (len(old) - len(new))
    open(path, "wb").write(data)


def apk_name(soname: str) -> str:
    """libfoo.so.1.2 -> libfoo12.so (always shorter, so it can be patched in place)."""
    if re.fullmatch(r"lib[\w.+-]*\.so", soname):
        return soname
    base, _, version = soname.partition(".so")
    return f"{base}{version.replace('.', '')}.so"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arch", default="aarch64")
    parser.add_argument("--out", default="app/src/main/jniLibs/arm64-v8a")
    parser.add_argument("--data-out", help="also copy QEMU data files (keymaps) here, e.g. app/src/main/assets/qemu")
    args = parser.parse_args()

    repo, text = pick_mirror(args.arch)
    print(f"Index: {repo} ({args.arch})")
    index = parse_index(text)
    packages = resolve(index, ROOT_PACKAGE)
    print(f"Packages ({len(packages)}): {' '.join(packages)}")

    with tempfile.TemporaryDirectory() as work:
        root = os.path.join(work, "root")
        for name in packages:
            fields = index[name]
            deb = fetch(f"{repo}/{fields['Filename']}")
            digest = hashlib.sha256(deb).hexdigest()
            if digest != fields["SHA256"]:
                sys.exit(f"SHA-256 mismatch for {name}")
            extract(deb, root)
            print(f"  + {name} {fields['Version']}")

        usr = os.path.join(root, PREFIX)
        libdir = os.path.join(usr, "lib")

        # Collect real ELF shared libraries (lib/ and its subdirectories) keyed by SONAME.
        libs = {}
        for dirpath, _, files in os.walk(libdir):
            for entry in sorted(files):
                path = os.path.join(dirpath, entry)
                if os.path.islink(path) or ".so" not in entry or not is_elf(path):
                    continue
                soname = elf_soname(path) or entry
                libs.setdefault(soname, path)

        # Bundle only what QEMU actually loads: the DT_NEEDED closure of the executable.
        executable = os.path.join(usr, EXECUTABLE)
        closure, missing, queue = set(), set(), [executable]
        while queue:
            for needed in elf_needed(queue.pop()):
                if needed in SYSTEM_LIBS or needed in closure:
                    continue
                if needed not in libs:
                    missing.add(needed)
                    continue
                closure.add(needed)
                queue.append(libs[needed])
        if missing:
            sys.exit(f"Unresolved libraries needed by QEMU: {sorted(missing)}")

        rename = {soname: apk_name(soname) for soname in closure}

        if os.path.isdir(args.out):
            shutil.rmtree(args.out)
        os.makedirs(args.out)

        def install(src: str, dest_name: str):
            # Only DT_NEEDED/DT_SONAME strings change; DT_RUNPATH (Termux's prefix) is
            # left alone: it does not exist on the phone and LD_LIBRARY_PATH wins.
            dest = os.path.join(args.out, dest_name)
            shutil.copyfile(src, dest)
            os.chmod(dest, 0o755)
            elf_rename(dest, rename)
            return redirect_resolv_conf(dest)

        redirected = 0
        for soname in sorted(closure):
            redirected += install(libs[soname], rename[soname])
            print(f"  = {rename[soname]}")
        redirected += install(executable, OUTPUT_EXECUTABLE)
        print(f"resolv.conf path redirected in {redirected} place(s)")
        if redirected == 0:
            print("  ! WARNING: Termux resolv.conf path not found; the guest may have no DNS")

        if args.data_out:
            if os.path.isdir(args.data_out):
                shutil.rmtree(args.data_out)
            for d in DATA_DIRS:
                src = os.path.join(usr, d)
                if not os.path.isdir(src):
                    sys.exit(f"QEMU data directory missing from packages: {d}")
                shutil.copytree(src, os.path.join(args.data_out, os.path.basename(d.rstrip("/"))))
            count = sum(len(files) for _, _, files in os.walk(args.data_out))
            print(f"Copied {count} data files to {args.data_out}")

    total = sum(os.path.getsize(os.path.join(args.out, f)) for f in os.listdir(args.out))
    print(f"Wrote {len(os.listdir(args.out))} files, {total / 1e6:.1f} MB to {args.out}")


if __name__ == "__main__":
    main()
