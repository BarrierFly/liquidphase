#!/usr/bin/env python3
"""为红石相组合方块生成 blockstate / 方块模型 / 贴图 / 语言键。

组合方块清单从 CompositeFactories 的实例表解析（map.put(Blocks.XXX, ...)），
生成 41 个单变体 blockstate（空键覆盖全部状态，power 等属性靠通配），
统一指向 liquidphase:block/redstone_phase（完整 16³ 立方体）。

贴图是内置的"疏松红石线"图案：16×16 透明底、短线按象限铺满整面，
三档红色（暗红/主红/亮红），左右上下接缝对称、跨方块拼贴连续。
不再使用原版 redstone_dust_dot.png —— 那张图只有正中一个点，
铺到整面时看起来"只覆盖一点红石大小"。

用法:
    python tools/gen_redstone_assets.py \
        src/main/java/org/liquidphase/redstone/composites/CompositeFactories.java \
        src/main/resources/assets/liquidphase \
        [客户端 jar 路径]

第三个参数仍是客户端 jar（宿主原版 blockstate 从 jar 内提取），贴图不再取 jar。
"""
import json
import re
import struct
import sys
import zipfile
import zlib
from pathlib import Path

# 16×16 疏松红石线图案：0=透明，1=暗红，2=主红，3=亮红。
# 均匀撒满整面的短碎屑（约 29% 覆盖），方向各异、跨格取模 —— 贴到任何一个面都是
# "疏松红石铺满正面"，四边可无缝拼贴（不会像 redstone_dust_dot 那样只有正中一点）。
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

PALETTE = {
    "0": (0, 0, 0, 0),
    "1": (112, 18, 12, 255),
    "2": (196, 32, 28, 255),
    "3": (255, 86, 72, 255),
}

DUST_DOT_ENTRY = "assets/minecraft/textures/block/redstone_dust_dot.png"

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

# 红石粉尘外壳 = 完整 16³ 立方体，包在宿主模型外面（不内缩）。
# 贴图是透明底 cutout：只有红石粉像素不透明并盖住宿主，透明像素后面直接透出
# 内部宿主的原版模型（multipart 的另一组 part）。
# 外壳整体外扩 0.01px（约 1/1600 格），避免与宿主共面的面片（方块底面/侧面）
# 发生深度冲突闪烁；这个量级在视觉上不可见。
SHELL_OVERHANG = 0.01
SHELL_FACES = ("down", "up", "north", "south", "west", "east")

MODEL = {
    "textures": {"particle": TEXTURE_ID, "all": TEXTURE_ID},
    "elements": [
        {
            "from": [-SHELL_OVERHANG, -SHELL_OVERHANG, -SHELL_OVERHANG],
            "to": [16 + SHELL_OVERHANG, 16 + SHELL_OVERHANG, 16 + SHELL_OVERHANG],
            "faces": {face: {"texture": "#all", "cullface": face} for face in SHELL_FACES},
        }
    ],
}

HOST_BLOCKSTATE_ENTRY = "assets/minecraft/blockstates/{host}.json"


def load_host_blockstate(jar: Path, host: str) -> dict:
    """从客户端 jar 提取宿主原版 blockstate（6 个版本内容一致，已核对）。"""
    with zipfile.ZipFile(jar) as zf:
        return json.loads(zf.read(HOST_BLOCKSTATE_ENTRY.format(host=host)).decode("utf-8"))


def host_parts(blockstate: dict):
    """宿主 blockstate → multipart part 列表（variants 逐个转 when，multipart 原样保留）。"""
    if "variants" in blockstate:
        parts = []
        for key, entry in blockstate["variants"].items():
            applies = entry if isinstance(entry, list) else [entry]
            for apply in applies:
                part = {"apply": dict(apply)}
                if key.strip():
                    when = {}
                    for piece in key.split(","):
                        prop, _, value = piece.partition("=")
                        when[prop.strip()] = value.strip()
                    part["when"] = when
                parts.append(part)
        return parts
    return [dict(part) for part in blockstate["multipart"]]


def composite_blockstate(jar: Path, host: str) -> dict:
    """组合方块 blockstate = 宿主原版几何 + 粉尘外壳（透明处露出里面的宿主材质）。

    宿主 part 保留原 when 条件（朝向/形状/连接态）；power 未出现在 when 里，
    multipart 对未列出的属性天然通配，所以 16 个功率共用同一套模型。
    """
    parts = host_parts(load_host_blockstate(jar, host))
    parts.append({"apply": {"model": MODEL_ID}})
    return {"multipart": parts}


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
    jar = Path(sys.argv[3]) if len(sys.argv) >= 4 else None
    if jar is None:
        raise SystemExit(
            "client jar path is required (host blockstates are extracted from it):\n"
            "  python tools/gen_redstone_assets.py <CompositeFactories.java> <assets/liquidphase> <minecraft-client.jar>"
        )
    hosts = collect_hosts(factories)
    if len(hosts) != 41:
        raise SystemExit(f"expected 41 hosts from {factories}, got {len(hosts)}: {hosts}")

    # 贴图：疏松红石线图案（不再从客户端 jar 提取原版 dot，避免只有正中一小块）
    for row in TEXTURE:
        if len(row) != 16:
            raise SystemExit(f"bad texture row: {row!r}")
    texture_path = out / "textures" / "block" / "redstone_phase.png"
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    write_png(texture_path, [[PALETTE[c] for c in row] for row in TEXTURE])
    print("dust source: built-in porous redstone pattern")

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
        target.write_text(json.dumps(composite_blockstate(jar, host), separators=(",", ":")) + "\n", encoding="utf-8")
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
