#!/usr/bin/env python3
"""
Generate platform app icons from a single master PNG.

Usage:
    python3 packaging/make-icons.py [--png packaging/icon.png] [--outdir packaging/build/icons]

Inputs : a 1024x1024 (or larger) master PNG.
Outputs:
    - <outdir>/icon.ico   (Windows multi-size ICO: 16..256)
    - <outdir>/icon.icns  (macOS .icns, via iconutil)            [macOS only]

This script is pure-Python for the ICO (stdlib only, no Pillow). It relies on
the macOS tools `sips` and `iconutil` for the .icns, so the .icns step is only
run on macOS. The ICO is always produced.

To rebrand the app later, replace packaging/icon.png with a new master and
re-run; no packaging configuration needs editing for ordinary updates.
"""
from __future__ import annotations

import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

# ---------------------------------------------------------------------------
# Minimal PNG reader (we only need to decode our own master PNG).
# ---------------------------------------------------------------------------

def _read_png_bytes(path: Path) -> tuple[bytes, int, int, int]:
    """Return (rgba_bytes, width, height, bit_depth) for an 8-bit RGBA/RGB PNG."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"not a PNG: {path}")
    pos = 8
    width = height = bit_depth = None
    ctype = None
    idat = bytearray()
    palette = None
    trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4]); pos += 4
        typ = data[pos:pos + 4]; pos += 4
        chunk = data[pos:pos + length]; pos += length
        pos += 4  # CRC
        if typ == b"IHDR":
            width, height, bit_depth, ctype = struct.unpack(">IIBB", chunk[:10])
        elif typ == b"PLTE":
            palette = chunk
        elif typ == b"tRNS":
            trns = chunk
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
    if bit_depth != 8:
        raise SystemExit("master PNG must be 8-bit; got bit depth " + str(bit_depth))
    raw = zlib.decompress(bytes(idat))

    # Defilter.
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bpp = channels
    stride = width * bpp
    out = bytearray()
    prev_row = bytearray(stride)
    i = 0
    for _y in range(height):
        ft = raw[i]; i += 1
        row = bytearray(raw[i:i + stride]); i += stride
        if ft == 0:
            pass
        elif ft == 1:
            for x in range(stride):
                left = row[x - bpp] if x >= bpp else 0
                row[x] = (row[x] + left) & 0xFF
        elif ft == 2:
            for x in range(stride):
                row[x] = (row[x] + prev_row[x]) & 0xFF
        elif ft == 3:
            for x in range(stride):
                left = row[x - bpp] if x >= bpp else 0
                row[x] = (row[x] + ((left + prev_row[x]) >> 1)) & 0xFF
        elif ft == 4:
            for x in range(stride):
                left = row[x - bpp] if x >= bpp else 0
                up = prev_row[x]
                a = left
                b_ = up
                p = a + b_ - 0  # c placeholder
                # Paeth predictor
                c = prev_row[x - bpp] if x >= bpp else 0
                pp = a + b_ - c
                pa, pb, pc = abs(pp - a), abs(pp - b_), abs(pp - c)
                if pa <= pb and pa <= pc:
                    pr = a
                elif pb <= pc:
                    pr = b_
                else:
                    pr = c
                row[x] = (row[x] + pr) & 0xFF
        out += row
        prev_row = row

    # Expand palette/greyscale/rgb -> RGBA.
    rgba = bytearray(width * height * 4)
    for idx in range(width * height):
        if ctype == 6:
            rgba[idx * 4:idx * 4 + 4] = out[idx * 4:idx * 4 + 4]
        elif ctype == 2:
            r, g, b_ = out[idx * 3:idx * 3 + 3]
            rgba[idx * 4:idx * 4 + 4] = bytes((r, g, b_, 255))
        elif ctype == 3:
            pi = out[idx]
            r, g, b_ = palette[pi * 3:pi * 3 + 3]
            a = trns[pi] if trns and pi < len(trns) else 255
            rgba[idx * 4:idx * 4 + 4] = bytes((r, g, b_, a))
        elif ctype == 0:
            v = out[idx]
            rgba[idx * 4:idx * 4 + 4] = bytes((v, v, v, 255))
        elif ctype == 4:
            v, a = out[idx * 2:idx * 2 + 2]
            rgba[idx * 4:idx * 4 + 4] = bytes((v, v, v, a))
    return bytes(rgba), width, height, bit_depth


# ---------------------------------------------------------------------------
# PNG re-encode at a target size (box filter downscale), returning PNG bytes.
# ---------------------------------------------------------------------------

def _encode_png(rgba: bytes, w: int, h: int) -> bytes:
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw += rgba[(y * w + x) * 4:(y * w + x) * 4 + 4]
    def chunk(typ: bytes, data: bytes) -> bytes:
        c = typ + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 9)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def _scale(rgba: bytes, sw: int, sh: int, tw: int, th: int) -> bytes:
    out = bytearray(tw * th * 4)
    for oy in range(th):
        for ox in range(tw):
            sx0 = ox * sw // tw
            sy0 = oy * sh // th
            sx1 = max(sx0 + 1, (ox + 1) * sw // tw)
            sy1 = max(sy0 + 1, (oy + 1) * sh // th)
            r = g = b = a = 0
            n = 0
            for yy in range(sy0, min(sy1, sh)):
                for xx in range(sx0, min(sx1, sw)):
                    i = (yy * sw + xx) * 4
                    r += rgba[i]; g += rgba[i + 1]; b += rgba[i + 2]; a += rgba[i + 3]
                    n += 1
            o = (oy * tw + ox) * 4
            out[o:o + 4] = bytes((r // n, g // n, b // n, a // n))
    return bytes(out)


# ---------------------------------------------------------------------------
# ICO writer (PNG-embedded entries).
# ---------------------------------------------------------------------------

def write_ico(png_path: Path, out_path: Path, sizes=(16, 24, 32, 48, 64, 128, 256)):
    rgba, w, h, _ = _read_png_bytes(png_path)
    entries = []
    for s in sizes:
        scaled = _scale(rgba, w, h, s, s)
        png = _encode_png(scaled, s, s)
        entries.append((s, png))
    # ICONDIR
    buf = bytearray()
    buf += struct.pack("<HHH", 0, 1, len(entries))
    offset = 6 + len(entries) * 16
    for s, png in entries:
        w_byte = 0 if s >= 256 else s
        buf += struct.pack("<BBBBHHII", w_byte, w_byte, 0, 0, 1, 32, len(png), offset)
        offset += len(png)
    for _s, png in entries:
        buf += png
    out_path.write_bytes(buf)
    return out_path


# ---------------------------------------------------------------------------
# ICNS via iconutil (macOS only).
# ---------------------------------------------------------------------------

def write_icns(png_path: Path, out_path: Path):
    """Build a .icns using sips + iconutil. macOS only."""
    if shutil.which("iconutil") is None or shutil.which("sips") is None:
        return None
    sizes = [16, 32, 64, 128, 256, 512, 1024]
    with tempfile.TemporaryDirectory() as td:
        iconset = Path(td) / "icon.iconset"
        iconset.mkdir()
        for s in sizes:
            # iconset needs specific filenames; scale and write PNGs.
            rgba, w, h, _ = _read_png_bytes(png_path)
            scaled = _scale(rgba, w, h, s, s)
            (iconset / f"icon_{s}x{s}.png").write_bytes(_encode_png(scaled, s, s))
            # Retina variants.
            if s <= 512:
                s2 = s * 2
                scaled2 = _scale(rgba, w, h, s2, s2)
                (iconset / f"icon_{s}x{s}@2x.png").write_bytes(_encode_png(scaled2, s2, s2))
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(out_path)],
                       check=True, capture_output=True)
        return out_path


# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Generate .ico and .icns from a master PNG.")
    here = Path(__file__).resolve().parent
    ap.add_argument("--png", default=str(here / "icon.png"))
    ap.add_argument("--outdir", default=str(here / "build" / "icons"))
    args = ap.parse_args()

    png = Path(args.png)
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    ico = outdir / "icon.ico"
    write_ico(png, ico)
    print(f"wrote {ico} ({ico.stat().st_size} bytes)")

    icns = outdir / "icon.icns"
    res = write_icns(png, icns)
    if res:
        print(f"wrote {icns} ({icns.stat().st_size} bytes)")
    else:
        print("skipped icon.icns (iconutil/sips not available - not on macOS)")


if __name__ == "__main__":
    main()
