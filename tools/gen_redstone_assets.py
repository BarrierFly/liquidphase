#!/usr/bin/env python3
"""生成红石相外壳的贴图与模型（16 个功率档位各一份）。

红石相 = 宿主方块上的 ``phase = redstone_0 … redstone_15``，不再注册任何方块；
外壳由 ``gen_phase_blockstates.py`` 以 multipart part 的形式叠加到每个宿主之上。

**外壳按功率着色**：原版红石线用 ``RedStoneWireBlock.COLORS`` 把功率映射成
"暗红 → 亮红"，0 档几乎不亮。这里沿用同一条亮度曲线（``f = p/15``，
``g = f*0.6 + (f>0 ? 0.4 : 0.3)``），把内置的三档红石碎屑调色板整体按 ``g`` 缩放，
所以 ``redstone_0`` 看起来是熄灭的暗红，``redstone_15`` 才是亮红。

为什么不用 ``tintindex`` + 客户端 BlockColors：tint 是按"模型的贴图索引"生效的，
而宿主自己的模型大量使用 ``tintindex 0``（树叶的植被着色就是 0）。外壳如果也用 0，
树叶的绿色会给粉尘染色；用 1 又要求每个宿主都有第二个 tint source，
1.19.4–1.21.1 的 ``BlockColor`` 接口（单 source，按 index 分派）根本表达不了这个区分。
静态生成 16 份调色板贴图在任何版本上都一致，也没有 API 差异。

用法: python tools/gen_redstone_assets.py <assets/liquidphase>
输出: <assets/liquidphase>/textures/block/redstone_phase_<p>.png
      <assets/liquidphase>/models/block/redstone_phase_<p>.json
"""
import json
import struct
import sys
import zlib
from pathlib import Path

# 16×16 疏松红石线图案：0=透明，1=暗红，2=主红，3=亮红。
# 均匀撒满整面的短碎屑（约 29% 覆盖），方向各异、跨格取模 —— 贴到任何一个面都是
# "疏松红石铺满正面"，四边可无缝拼贴。
TEXTURE = [
    "0300312320003002",
    "0002333002333003",
    "0000300000032030",
    "0002000000030300",
    "1222000000112000",
    "0000033330020000",
    "0000030000002000",
    "0012300230000300",
    "3002000000000300",
    "0000000002330203",
    "2000000000000030",
    "0300023000120000",
    "0000003000300000",
    "0300000002300000",
    "0300200000000130",
    "1200300100000321",
]

# 基准调色板（= 满功率 15 档的外观）。
PALETTE = {
    "0": (0, 0, 0, 0),
    "1": (112, 18, 12, 255),
    "2": (196, 32, 28, 255),
    "3": (255, 86, 72, 255),
}

# 外壳整体外扩 0.01px，避免与宿主共面的面片深度冲突。
SHELL_OVERHANG = 0.01
SHELL_FACES = ("down", "up", "north", "south", "west", "east")
POWER_LEVELS = 16


def power_shade(power: int) -> float:
    """原版红石线的亮度曲线（RedStoneWireBlock.COLORS 的红色分量）。"""
    f = power / 15.0
    return f * 0.6 + (0.4 if f > 0.0 else 0.3)


def tinted_palette(power: int):
    shade = power_shade(power)
    out = {}
    for key, (r, g, b, a) in PALETTE.items():
        out[key] = (min(255, round(r * shade)),
                    min(255, round(g * shade)),
                    min(255, round(b * shade)),
                    a)
    return out


def model_id(power: int) -> str:
    return "liquidphase:block/redstone_phase_%d" % power


def texture_id(power: int) -> str:
    return "liquidphase:block/redstone_phase_%d" % power


def build_model(power: int) -> dict:
    # NOTE: no cullface on the shell. The shell is 0.01 *outside* the block boundary, so
    # vanilla cullface (which compares the neighbour's face occlusion against the block
    # boundary) misfires against partial neighbours (slabs/stairs) and eats faces the
    # shell actually covers.
    return {
        "textures": {"particle": texture_id(power), "all": texture_id(power)},
        "elements": [
            {
                "from": [-SHELL_OVERHANG, -SHELL_OVERHANG, -SHELL_OVERHANG],
                "to": [16 + SHELL_OVERHANG, 16 + SHELL_OVERHANG, 16 + SHELL_OVERHANG],
                # 显式 uv=整张贴图：26.3 的 FaceBakery 按 quad UV 区域计算贴图透明度，
                # 自动 UV（=元素包围盒）会因 -0.01..16.01 的外扩几何出界 [-1,17] 而抛
                # IllegalArgumentException，导致整个 blockstate bake 失败、相全部不渲染。
                # 显式 (0,0,16,16) 命中 SpriteContents.computeTransparency 的整图快速路径。
                "faces": {face: {"texture": "#all", "uv": [0, 0, 16, 16]} for face in SHELL_FACES},
            }
        ],
    }


def write_png(path: Path, rows) -> None:
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


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit("usage: python tools/gen_redstone_assets.py <assets/liquidphase>")
    out = Path(sys.argv[1])
    for row in TEXTURE:
        if len(row) != 16:
            raise SystemExit("bad texture row: %r" % row)

    tex_dir = out / "textures" / "block"
    model_dir = out / "models" / "block"
    tex_dir.mkdir(parents=True, exist_ok=True)
    model_dir.mkdir(parents=True, exist_ok=True)

    for power in range(POWER_LEVELS):
        palette = tinted_palette(power)
        write_png(tex_dir / ("redstone_phase_%d.png" % power),
                  [[palette[c] for c in row] for row in TEXTURE])
        (model_dir / ("redstone_phase_%d.json" % power)).write_text(
            json.dumps(build_model(power), indent=2, sort_keys=False) + "\n", encoding="utf-8")

    # 老版本的单一外壳模型（0.1.10 及以前）已按功率拆分，避免残留的引用指向过时外观。
    stale_model = model_dir / "redstone_phase.json"
    if stale_model.exists():
        stale_model.unlink()
    stale_texture = tex_dir / "redstone_phase.png"
    if stale_texture.exists():
        stale_texture.unlink()

    print("textures: %d -> %s" % (POWER_LEVELS, tex_dir))
    print("models  : %d -> %s" % (POWER_LEVELS, model_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
