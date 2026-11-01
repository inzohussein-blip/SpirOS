"""SpirOS Linux wallpaper: deep night gradient with soft violet and mint glows and a
faint dot grid. Pure standard library (no imaging packages needed at build time)."""
import math, struct, sys, zlib

W, H = 1920, 1080
out = sys.argv[1]
top, bottom = (16, 14, 34), (6, 7, 12)
glows = [((0.18, 0.22), (124, 92, 255), 0.52, 0.95), ((0.92, 0.95), (61, 220, 151), 0.34, 0.85), ((0.62, 0.05), (251, 106, 12), 0.10, 0.70)]
rows = []
for y in range(H):
    ty = y / (H - 1)
    row = bytearray(b"\x00")
    for x in range(W):
        tx = x / (W - 1)
        t = (tx * 0.35 + ty * 0.65)
        r = top[0] + (bottom[0] - top[0]) * t
        g = top[1] + (bottom[1] - top[1]) * t
        b = top[2] + (bottom[2] - top[2]) * t
        for (cx, cy), col, strength, radius in glows:
            dx, dy = (tx - cx) * W / H, ty - cy
            k = strength * math.exp(-(dx * dx + dy * dy) / (2 * radius * radius * 0.25))
            r += (col[0] - r) * k; g += (col[1] - g) * k; b += (col[2] - b) * k
        if x % 48 == 24 and y % 48 == 24:  # dot grid
            r += 18; g += 18; b += 26
        row += bytes((min(255, int(r)), min(255, int(g)), min(255, int(b))))
    rows.append(bytes(row))
raw = b"".join(rows)
def chunk(kind, data):
    c = struct.pack(">I", len(data)) + kind + data
    return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
open(out, "wb").write(png)
