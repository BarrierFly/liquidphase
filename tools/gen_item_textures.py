#!/usr/bin/env python3
"""生成液体物品的 16x16 像素贴图（水滴 / 熔岩滴，原创像素画）。
用法: python gen_item_textures.py <out-dir>
"""
import struct
import sys
import zlib
from pathlib import Path

# 调色板（RGBA）
CLEAR = (0, 0, 0, 0)
WATER_DARK = (30, 78, 176, 255)
WATER_MAIN = (52, 110, 220, 255)
WATER_LIGHT = (110, 170, 245, 255)
LAVA_DARK = (140, 24, 8, 255)
LAVA_MAIN = (216, 82, 16, 255)
LAVA_LIGHT = (248, 176, 60, 255)

# 水滴轮廓（16 行 x 16 列，0=透明 1=主色 2=暗 3=高光）
DROPLET = [
    "0000000000000000",
    "0000000110000000",
    "0000001221000000",
    "0000012211000000",
    "0000012211000000",
    "0000122111000000",
    "0001221111000000",
    "0001221111100000",
    "0012211111100000",
    "0012211111110000",
    "0122111211110000",
    "0121111122111000",
    "0211111122111000",
    "0011111122110000",
    "0001111111000000",
    "0000000000000000",
]

PALETTES = {
    "liquid_water": [CLEAR, WATER_MAIN, WATER_DARK, WATER_LIGHT],
    "liquid_lava": [CLEAR, LAVA_MAIN, LAVA_DARK, LAVA_LIGHT],
}


def write_png(path: Path, rows):
    height = len(rows)
    width = len(rows[0])
    raw = b""
    for row in rows:
        raw += b"\x00" + bytes(b for px in row for b in px)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


def main():
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, palette in PALETTES.items():
        rows = [[palette[int(c)] for c in line] for line in DROPLET]
        write_png(out_dir / f"{name}.png", rows)
        print(f"wrote {out_dir / (name + '.png')}")


if __name__ == "__main__":
    main()
