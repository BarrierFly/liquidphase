#!/usr/bin/env python3
"""为红石相组合方块生成 blockstate / 方块模型 / 贴图 / 语言键。

组合方块清单从 CompositeFactories 的实例表解析（map.put(Blocks.XXX, ...)），
生成 41 个单变体 blockstate（空键覆盖全部状态，power 等属性靠通配），
统一指向 liquidphase:block/redstone_phase（完整 16³ 立方体）。

用法:
    python tools/gen_redstone_assets.py \
        src/main/java/org/liquidphase/redstone/composites/CompositeFactories.java \
        src/main/resources/assets/liquidphase
"""
import json
import re
import struct
import sys
import zlib
from pathlib import Path

# 16×16 透明底 + 疏松红石线；0=透明，1=暗红，2=主红，3=亮红
TEXTURE = [
    "0000000000000000",
    "0022200000022200",
    "0020200000020200",
    "0002000000002000",
    "0000000000000000",
    "0000310000000000",
    "0000130000110000",
    "0000000000330000",
    "0000000000000000",
    "0030000000000000",
    "0010000222000000",
    "0000000020000000",
    "0000000000000000",
    "0002220000002220",
    "0002020000002020",
    "0000000000000000",
]

PALETTE = {
    "0": (0, 0, 0, 0),
    "1": (112, 18, 12, 255),
    "2": (196, 32, 28, 255),
    "3": (255, 86, 72, 255),
}

# 中文名按原版译名（红石相前缀在生成时统一添加）
ZH_NAMES = {
    "oak_stairs": "橡木楼梯",
    "spruce_stairs": "云杉楼梯",
    "birch_stairs": "白桦楼梯",
    "jungle_stairs": "丛林楼梯",
    "acacia_stairs": "金合欢楼梯",
    "dark_oak_stairs": "深色橡木楼梯",
    "cobblestone_stairs": "圆石楼梯",
    "stone_stairs": "石头楼梯",
    "brick_stairs": "砖块楼梯",
    "stone_brick_stairs": "石砖楼梯",
    "sandstone_stairs": "砂岩楼梯",
    "quartz_stairs": "石英楼梯",
    "purpur_stairs": "紫珀楼梯",
    "oak_slab": "橡木台阶",
    "spruce_slab": "云杉台阶",
    "birch_slab": "白桦台阶",
    "jungle_slab": "丛林台阶",
    "acacia_slab": "金合欢台阶",
    "dark_oak_slab": "深色橡木台阶",
    "stone_slab": "石头台阶",
    "cobblestone_slab": "圆石台阶",
    "smooth_stone_slab": "平滑石头台阶",
    "stone_brick_slab": "石砖台阶",
    "brick_slab": "砖块台阶",
    "sandstone_slab": "砂岩台阶",
    "quartz_slab": "石英台阶",
    "purpur_slab": "紫珀台阶",
    "cobblestone_wall": "圆石墙",
    "cobbled_deepslate_wall": "深板岩圆石墙",
    "mossy_cobblestone_wall": "苔石墙",
    "stone_brick_wall": "石砖墙",
    "brick_wall": "砖块墙",
    "sandstone_wall": "砂岩墙",
    "prismarine_wall": "海晶石墙",
    "oak_fence": "橡木栅栏",
    "spruce_fence": "云杉栅栏",
    "birch_fence": "白桦栅栏",
    "jungle_fence": "丛林栅栏",
    "acacia_fence": "金合欢栅栏",
    "dark_oak_fence": "深色橡木栅栏",
    "nether_brick_fence": "下界砖栅栏",
}

MODEL_ID = "liquidphase:block/redstone_phase"
TEXTURE_ID = "liquidphase:block/redstone_phase"

BLOCKSTATE = {"variants": {"": {"model": MODEL_ID}}}

MODEL = {
    "textures": {"particle": TEXTURE_ID, "all": TEXTURE_ID},
    "elements": [
        {
            "from": [0, 0, 0],
            "to": [16, 16, 16],
            "faces": {
                "down": {"texture": "#all", "cullface": "down"},
                "up": {"texture": "#all", "cullface": "up"},
                "north": {"texture": "#all", "cullface": "north"},
                "south": {"texture": "#all", "cullface": "south"},
                "west": {"texture": "#all", "cullface": "west"},
                "east": {"texture": "#all", "cullface": "east"},
            },
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


def collect_hosts(factories: Path):
    text = factories.read_text(encoding="utf-8")
    ids = re.findall(r"map\.put\(Blocks\.([A-Z0-9_]+),", text)
    hosts = []
    for upper in ids:
        host = upper.lower()
        if host not in hosts:
            hosts.append(host)
    return hosts


def write_lang(path: Path, keys) -> None:
    data = {}
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
    data.update(keys)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    factories = Path(sys.argv[1])
    out = Path(sys.argv[2])
    hosts = collect_hosts(factories)
    if len(hosts) != 41:
        raise SystemExit(f"expected 41 hosts from {factories}, got {len(hosts)}: {hosts}")

    for row in TEXTURE:
        assert len(row) == 16, f"bad texture row: {row!r}"

    # 贴图
    texture_path = out / "textures" / "block" / "redstone_phase.png"
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    write_png(texture_path, [[PALETTE[c] for c in row] for row in TEXTURE])

    # 方块模型
    model_path = out / "models" / "block" / "redstone_phase.json"
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps(MODEL, indent=2, sort_keys=False) + "\n", encoding="utf-8")

    # 41 个 blockstate
    blockstates_dir = out / "blockstates"
    blockstates_dir.mkdir(parents=True, exist_ok=True)
    en = {}
    zh = {}
    for host in hosts:
        target = blockstates_dir / f"redstone_{host}.json"
        target.write_text(json.dumps(BLOCKSTATE, separators=(",", ":")) + "\n", encoding="utf-8")
        key = f"block.liquidphase.redstone_{host}"
        en[key] = "Redstone-Phase " + host.replace("_", " ").title()
        zh[key] = "红石相" + ZH_NAMES.get(host, host.replace("_", " "))

    write_lang(out / "lang" / "en_us.json", en)
    write_lang(out / "lang" / "zh_cn.json", zh)

    print(f"hosts: {len(hosts)}")
    print(f"texture: {texture_path}")
    print(f"model: {model_path}")
    print(f"blockstates: {len(list(blockstates_dir.glob('redstone_*.json')))} files -> {blockstates_dir}")
    print(f"lang keys: {len(en)} (en_us + zh_cn)")


if __name__ == "__main__":
    main()
