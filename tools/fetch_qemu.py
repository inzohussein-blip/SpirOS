#!/usr/bin/env python3
"""
Bundle a prebuilt QEMU for Android into the APK (track B1).

QEMU and its libraries come from the Termux package repository (GPL / LGPL,
built with the Android NDK against bionic). This script:
  1. resolves qemu-system-aarch64-headless and its dependencies,
  2. downloads the .deb files and checks their SHA-256 from the signed index,
  3. extracts the executable and shared libraries,
  4. renames everything to lib*.so (only such files are installed from an APK)
     and rewrites SONAME / DT_NEEDED with patchelf so they find each other,
  5. writes them to app/src/main/jniLibs/arm64-v8a/.

At runtime SpirOS executes libqemu-system-aarch64.so from nativeLibraryDir
(the only app location Android 10+ allows executing from).

Requires: python3, patchelf.
"""
import argparse
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

REPO = "https://packages-cf.termux.dev/apt/termux-main"
ROOT_PACKAGE = "qemu-system-aarch64-headless"
PREFIX = "data/data/com.termux/files/usr/"
EXECUTABLE = "bin/qemu-system-aarch64"
OUTPUT_EXECUTABLE = "libqemu-system-aarch64.so"
# Provided by Android itself; never bundled.
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


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
    wanted = (PREFIX + "lib/", PREFIX + EXECUTABLE)
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


def patchelf(*args) -> str:
    return subprocess.run(["patchelf", *args], check=True, capture_output=True, text=True).stdout.strip()


def apk_name(soname: str) -> str:
    """libfoo.so.1.2 -> libfoo_1_2.so ; libfoo-2.0.so stays."""
    if re.fullmatch(r"lib[\w.+-]*\.so", soname):
        return soname
    base, _, version = soname.partition(".so")
    return f"{base}{version.replace('.', '_')}.so"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arch", default="aarch64")
    parser.add_argument("--out", default="app/src/main/jniLibs/arm64-v8a")
    args = parser.parse_args()

    print(f"Index: {REPO} ({args.arch})")
    index = parse_index(fetch(f"{REPO}/dists/stable/main/binary-{args.arch}/Packages").decode())
    packages = resolve(index, ROOT_PACKAGE)
    print(f"Packages ({len(packages)}): {' '.join(packages)}")

    with tempfile.TemporaryDirectory() as work:
        root = os.path.join(work, "root")
        for name in packages:
            fields = index[name]
            deb = fetch(f"{REPO}/{fields['Filename']}")
            digest = hashlib.sha256(deb).hexdigest()
            if digest != fields["SHA256"]:
                sys.exit(f"SHA-256 mismatch for {name}")
            extract(deb, root)
            print(f"  + {name} {fields['Version']}")

        usr = os.path.join(root, PREFIX)
        libdir = os.path.join(usr, "lib")

        # Collect real ELF shared libraries keyed by SONAME.
        libs = {}
        for entry in sorted(os.listdir(libdir)):
            path = os.path.join(libdir, entry)
            if os.path.islink(path) or not os.path.isfile(path) or ".so" not in entry or not is_elf(path):
                continue
            soname = patchelf("--print-soname", path) or entry
            libs.setdefault(soname, path)

        rename = {soname: apk_name(soname) for soname in libs}

        if os.path.isdir(args.out):
            shutil.rmtree(args.out)
        os.makedirs(args.out)

        def install(src: str, dest_name: str, soname: str | None):
            dest = os.path.join(args.out, dest_name)
            shutil.copyfile(src, dest)
            os.chmod(dest, 0o755)
            patchelf("--remove-rpath", dest)
            if soname is not None:
                patchelf("--set-soname", dest_name, dest)
            for needed in patchelf("--print-needed", dest).split():
                if needed in rename and rename[needed] != needed:
                    patchelf("--replace-needed", needed, rename[needed], dest)
                elif needed not in rename and needed not in SYSTEM_LIBS:
                    print(f"  ! {dest_name} needs {needed}, which is not bundled")

        for soname, path in libs.items():
            if soname in SYSTEM_LIBS:
                continue
            install(path, rename[soname], soname)
        install(os.path.join(usr, EXECUTABLE), OUTPUT_EXECUTABLE, None)

    total = sum(os.path.getsize(os.path.join(args.out, f)) for f in os.listdir(args.out))
    print(f"Wrote {len(os.listdir(args.out))} files, {total / 1e6:.1f} MB to {args.out}")


if __name__ == "__main__":
    main()
