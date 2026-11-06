#!/usr/bin/env python3
"""为红石相组合方块生成 blockstate / 方块模型 / 贴图 / 语言键。

组合方块清单从 CompositeFactories 的实例表解析（map.put(Blocks.XXX, ...)），
生成 41 个单变体 blockstate（空键覆盖全部状态，power 等属性靠通配），
统一指向 liquidphase:block/redstone_phase（完整 16³ 立方体）。

贴图基于原版 redstone_dust_dot.png（红石线中心点图案）预染：原版 dust
贴图是灰白像素、由游戏按 RedStoneWireBlock.COLORS[power] 实时染色，这里
按 power=15 的颜色 RGB(255, 51, 0) 逐像素乘色（保留 alpha 通道），观感与
满功率红石线一致。贴图保持 16×16、透明底（cutout 渲染层的前提）。

用法:
    python tools/gen_redstone_assets.py \
        src/main/java/org/liquidphase/redstone/composites/CompositeFactories.java \
        src/main/resources/assets/liquidphase \
        [客户端 jar 路径]

第三个参数可选：给出客户端 jar 时从 jar 内提取 redstone_dust_dot.png 并
染色（提取 + 染色逻辑内置，1.19.4～26.3 的 jar 均适用，旧版为 RGBA、
新版为灰度+alpha 两种 PNG 格式都支持）；省略时使用内置的 1.21.11 原版
dot 图案数据（各版本原版 dot 贴图像素完全一致）。两种路径产物一致，脚本可
重复重跑。
"""
import json
import re
import struct
import sys
import zipfile
import zlib
from pathlib import Path

# 满功率红石染色：RedStoneWireBlock.COLORS[15]
#   f = 15/15 = 1.0; g = f*0.6 + 0.4 = 1.0; h = clamp(f²*0.7 - 0.5) = 0.2; j = clamp(f²*0.6 - 0.7) = 0.0
#   colorFromFloat(1, g, h, j)，as8BitChannel(x) = floor(x*255) → RGB(255, 51, 0)
POWER_15_TINT = (255, 51, 0)

# 原版 redstone_dust_dot.png（1.21.11 客户端 jar，各版本像素一致）的灰度+alpha
# 数据：16 行 × 16 像素，每像素 4 个十六进制字符（灰度 2 位 + alpha 2 位）。
# 原版贴图即灰白像素（alpha 只有 0/255，灰度 0 的像素 alpha 同为 0），
# 由游戏按 power 染色。
# ASCII 预览（#=254 b=235 a=217，.=透明）：
#   ................
#   ................
#   ................
#   ................
#   ................
#   ......#.#a......
#   .....a#ba##.....
#   ......bb.bb#....
#   .....a#b#b#.....
#   .....a#b.a#.....
#   .......a#.b.....
#   .........#......
#   ................
#   ................
#   ................
#   ................
VANILLA_DUST_DOT = [
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "000000000000000000000000feff0000feffd9ff000000000000000000000000",
    "00000000000000000000d9fffeffd9ffebfffefffeff00000000000000000000",
    "000000000000000000000000ebffebff0000ebffebfffeff0000000000000000",
    "00000000000000000000feffd9ffebfffeffebfffeff00000000000000000000",
    "00000000000000000000feffd9fffeff0000d9fffeff00000000000000000000",
    "0000000000000000000000000000d9fffeff0000feff00000000000000000000",
    "000000000000000000000000000000000000feff000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0000000000000000000000000000000000000000000000000000000000000000",
]

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


def read_dust_dot(png: bytes):
    """解析原版 redstone_dust_dot.png → 16 行 × 16 像素的 (灰度, alpha)。

    支持 8-bit 灰度+alpha（coltype 4，1.21.10+）与 RGBA（coltype 6，
    1.19.4～1.21.1，此时断言 R=G=B 即灰度）。含全部 5 种 PNG 行滤波的还原。
    """
    if png[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("dust texture is not a PNG file")
    pos, ihdr, idat = 8, None, b""
    while pos < len(png):
        length = struct.unpack(">I", png[pos:pos + 4])[0]
        tag = png[pos + 4:pos + 8]
        chunk = png[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", chunk)
        elif tag == b"IDAT":
            idat += chunk
        pos += 12 + length
    width, height, bit_depth, color_type, _, _, interlace = ihdr
    if (width, height, bit_depth, interlace) != (16, 16, 8, 0):
        raise SystemExit(f"unexpected dust texture format: {ihdr}")
    channels = {4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise SystemExit(f"unsupported PNG color type {color_type} for dust texture")

    stride = width * channels
    raw = zlib.decompress(idat)
    prev = bytearray(stride)
    rows = []
    for y in range(height):
        filter_type = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            left = line[x - channels] if x >= channels else 0
            up = prev[x]
            upleft = prev[x - channels] if x >= channels else 0
            if filter_type == 0:
                pass
            elif filter_type == 1:
                line[x] = (line[x] + left) & 255
            elif filter_type == 2:
                line[x] = (line[x] + up) & 255
            elif filter_type == 3:
                line[x] = (line[x] + (left + up) // 2) & 255
            elif filter_type == 4:
                p = left + up - upleft
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - upleft)
                near = left if (pa <= pb and pa <= pc) else (up if pb <= pc else upleft)
                line[x] = (line[x] + near) & 255
            else:
                raise SystemExit(f"unsupported PNG filter type {filter_type}")
        prev = line
        row = []
        for x in range(width):
            px = line[x * channels:(x + 1) * channels]
            if color_type == 4:
                row.append((px[0], px[1]))
            else:
                r, g, b, a = px
                if a != 0 and not (r == g == b):
                    raise SystemExit(f"dust texture pixel is not grayscale: {(r, g, b, a)}")
                row.append((r, a))
        rows.append(row)
    return rows


def embedded_dust_dot():
    """内置的 VANILLA_DUST_DOT 十六进制数据 → 与提取结果同构的行。"""
    rows = []
    for line in VANILLA_DUST_DOT:
        if len(line) != 64:
            raise SystemExit(f"bad embedded dust dot row: {line!r}")
        rows.append([(int(line[i:i + 2], 16), int(line[i + 2:i + 4], 16)) for i in range(0, 64, 4)])
    return rows


def tint_dust_pixel(gray: int, alpha: int):
    """灰度像素 × 满功率红石色，保留 alpha；透明像素保持全透明。"""
    if alpha == 0:
        return (0, 0, 0, 0)
    tr, tg, tb = POWER_15_TINT
    return (round(gray * tr / 255), round(gray * tg / 255), round(gray * tb / 255), alpha)


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

    # 贴图：从客户端 jar 提取原版 dot 贴图（jar 已必填）
    with zipfile.ZipFile(jar) as zf:
        png = zf.read(DUST_DOT_ENTRY)
    dust_rows = read_dust_dot(png)
    source = f"extracted {DUST_DOT_ENTRY} from {jar}"
    if len(dust_rows) != 16 or any(len(row) != 16 for row in dust_rows):
        raise SystemExit("dust texture is not 16x16")

    texture_path = out / "textures" / "block" / "redstone_phase.png"
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    write_png(texture_path, [[tint_dust_pixel(g, a) for g, a in row] for row in dust_rows])
    print(f"dust source: {source}")

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
