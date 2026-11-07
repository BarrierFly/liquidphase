#!/usr/bin/env python3
"""为红石相生成 blockstate / 方块模型 / 贴图 / 语言键。

红石相 = 宿主方块上的 phase 取值（redstone_0 … redstone_15），不再是独立方块。
本脚本从客户端 jar 提取宿主原版 blockstate，生成
"宿主原版 multipart part + 一层 16³ 红石粉尘外壳" 的 multipart：

- 宿主 part 保留原 when（朝向/形状/连接态），并合并 phase 条件
  `phase = none|lava|obsidian|ice|frosted_0..3`（其它相由各自的覆盖层负责）。
- 追加 16 个 `when: {phase: redstone_N}` 的 part，全部指向同一个外壳模型
  （功率只影响信号，不影响外观）。
- 外壳贴图 = 内置的疏松红石碎屑图案（16×16 透明底，铺满整面）。
- 外壳整体外扩 0.01px，避免与宿主共面的面片深度冲突。

用法:
    python tools/gen_redstone_assets.py <CompositeFactories 或宿主清单> <assets/liquidphase> <客户端 jar>

第 1 个参数现在只用来做"41 个宿主"的数量校验（红石相不再有自己的方块类），
可以传 tools/phase-blocks/<版本>.redstone-hosts 这样的纯 id 清单。
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

PALETTE = {
    "0": (0, 0, 0, 0),
    "1": (112, 18, 12, 255),
    "2": (196, 32, 28, 255),
    "3": (255, 86, 72, 255),
}

# 中文名按原版译名
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

# 宿主 part 只在"不渲染红石外壳"的相下渲染。
NON_REDSTONE_PHASES = "none|lava|obsidian|ice|frosted_0|frosted_1|frosted_2|frosted_3"


def load_host_blockstate(jar: Path, host: str) -> dict:
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


def with_phase(when, condition):
    merged = dict(when) if when else {}
    merged["phase"] = condition
    return merged


def composite_blockstate(jar: Path, host: str) -> dict:
    """宿主原版几何 + 红石外壳（透明处露出里面的宿主材质）。

    宿主 part 保留原 when，并限制在"非红石相"的 phase 下；
    再追加 16 条 phase=redstone_N 的 part 指向同一个外壳模型。
    """
    parts = []
    for part in host_parts(load_host_blockstate(jar, host)):
        new_part = {
            "apply": part["apply"],
            "when": with_phase(part.get("when"), NON_REDSTONE_PHASES),
        }
        parts.append(new_part)
    for power in range(16):
        parts.append({
            "when": {"phase": "redstone_%d" % power},
            "apply": {"model": MODEL_ID},
        })
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


def collect_hosts(source: Path):
    """从 CompositeFactories.java 或纯 id 清单里收集 41 个宿主 id。"""
    text = source.read_text(encoding="utf-8")
    ids = re.findall(r"map\.put\(Blocks\.([A-Z0-9_]+),", text)
    if not ids:
        ids = [line.strip().upper() for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]
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
    source = Path(sys.argv[1])
    out = Path(sys.argv[2])
    jar = Path(sys.argv[3]) if len(sys.argv) >= 4 else None
    if jar is None:
        raise SystemExit(
            "client jar path is required (host blockstates are extracted from it):\n"
            "  python tools/gen_redstone_assets.py <host-list> <assets/liquidphase> <minecraft-client.jar>"
        )
    hosts = collect_hosts(source)
    if len(hosts) != 41:
        raise SystemExit(f"expected 41 hosts from {source}, got {len(hosts)}: {hosts}")

    for row in TEXTURE:
        if len(row) != 16:
            raise SystemExit(f"bad texture row: {row!r}")
    texture_path = out / "textures" / "block" / "redstone_phase.png"
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    write_png(texture_path, [[PALETTE[c] for c in row] for row in TEXTURE])

    model_path = out / "models" / "block" / "redstone_phase.json"
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps(MODEL, indent=2, sort_keys=False) + "\n", encoding="utf-8")

    blockstates_dir = out / "blockstates"
    blockstates_dir.mkdir(parents=True, exist_ok=True)
    en = {}
    zh = {}
    for host in hosts:
        target = blockstates_dir / f"{host}.json"
        target.write_text(json.dumps(composite_blockstate(jar, host), separators=(",", ":")) + "\n", encoding="utf-8")
        key = f"block.liquidphase.redstone_{host}"
        en[key] = "Redstone-Phase " + host.replace("_", " ").title()
        zh[key] = "红石相" + ZH_NAMES.get(host, host.replace("_", " "))

    write_lang(out / "lang" / "en_us.json", en)
    write_lang(out / "lang" / "zh_cn.json", zh)

    print(f"hosts: {len(hosts)}")
    print(f"texture: {texture_path}")
    print(f"model: {model_path}")
    print(f"blockstates: {len(list(blockstates_dir.glob('*.json')))} files -> {blockstates_dir}")
    print(f"lang keys: {len(en)} (en_us + zh_cn)")


if __name__ == "__main__":
    main()
