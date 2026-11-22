#!/usr/bin/env python3
"""生成元件相的子模型（方案 C，动态模型按坐标发射）。

从客户端 jar 的**原版**模型/贴图生成"只显示要求部位"的元件子模型：

- ``element_repeater_<1..4>tick[_on][_locked].json``
  仅中继器的**杆**（红石火把，含点亮光晕）与**红条**（顶面贴图里的红色条带）；
  原版 2px 平滑石底板、锁存条都不渲染。
- ``element_comparator[_on][_subtract].json``
  同上：三根杆 + 红条，无底板。
- ``element_observer[_<facing>][_on].json``（6 朝向 × on/off）
  16×16 正常比例、**固定在方块内**（不做抬升/旋转）；只保留侦测面"五官"和输出面
  "输出口"两块裁切区域，不显示任何一个整面。六朝向预烘焙，运行期只按 facing 选模型。
- ``dynamic.json``
  无条件 part 的空模型；旧代由自定义 UnbakedModel 接管，新代由 BlockStateModel 包装器发射几何。

杆/红条按 **1/16 像素列**切分：运行时把每个小格按它所在列的宿主顶面高度整体平移，
从而实现规划要求的"每一平方像素都搭在宿主相应像素的最高处；同一杆/红条内部允许落差"。
（不切分的话只能做到顶点插值，跨台阶时面会悬空/埋入。）

配套贴图（写到 ``assets/liquidphase/textures/block/``）：

- ``element_repeater_bar[_on].png``（2×8，原版中继器顶面红条裁切）
- ``element_comparator_bar[_on].png``（8×2，原版比较器顶面红条裁切）
- ``element_observer_front.png``（16×16，只保留五官区域，其余透明）
- ``element_observer_port[_on].png``（16×16，只保留输出口区域，其余透明）

用法: python tools/gen_element_models.py <client-jar> <out-dir>
"""
import json
import math
import struct
import sys
import zipfile
import zlib
from pathlib import Path


# ----------------------------------------------------------------------
# PNG（只需要 8-bit、非隔行；原版贴图是调色板/灰度/RGBA）
# ----------------------------------------------------------------------

def read_png(data):
    """{@return (w, h, pixels)}，pixels[y][x] = (r, g, b, a)。"""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    idat = b""
    palette = None
    trns = None
    width = height = depth = color = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or interlace != 0:
                raise ValueError("unsupported PNG (depth=%s interlace=%s)" % (depth, interlace))
        elif kind == b"PLTE":
            palette = chunk
        elif kind == b"tRNS":
            trns = chunk
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    stride = width * channels
    rows = []
    previous = bytearray(stride)
    index = 0
    for _ in range(height):
        filter_type = raw[index]
        index += 1
        row = bytearray(raw[index:index + stride])
        index += stride
        for x in range(stride):
            a = row[x - channels] if x >= channels else 0
            b = previous[x]
            c = previous[x - channels] if x >= channels else 0
            if filter_type == 1:
                row[x] = (row[x] + a) & 0xFF
            elif filter_type == 2:
                row[x] = (row[x] + b) & 0xFF
            elif filter_type == 3:
                row[x] = (row[x] + ((a + b) >> 1)) & 0xFF
            elif filter_type == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                row[x] = (row[x] + pr) & 0xFF
            elif filter_type != 0:
                raise ValueError("bad PNG filter %s" % filter_type)
        rows.append(bytes(row))
        previous = row
    pixels = []
    for y in range(height):
        line = []
        for x in range(width):
            if color == 3:
                i = rows[y][x]
                r, g, b = palette[i * 3:i * 3 + 3]
                a = trns[i] if trns is not None and i < len(trns) else 255
            elif color == 6:
                r, g, b, a = rows[y][x * 4:x * 4 + 4]
            elif color == 2:
                r, g, b = rows[y][x * 3:x * 3 + 3]
                a = 255
            elif color == 4:
                r, g, a = rows[y][x * 2:x * 2 + 2]
                b = r
                g = 0
            else:
                r = g = b = rows[y][x]
                a = 255
            line.append((r, g, b, a))
        pixels.append(line)
    return width, height, pixels


def write_png(path, width, height, pixels):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw += bytes(pixels[y][x])

    def chunk(kind, payload):
        body = struct.pack(">I", len(payload)) + kind + payload
        return body + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)


def texture_pixels(zf, name):
    return read_png(zf.read("assets/minecraft/textures/block/%s.png" % name))[2]


# ----------------------------------------------------------------------
# 原版模型
# ----------------------------------------------------------------------

def load_model(zf, model_id):
    namespace, path = (model_id.split(":", 1) if ":" in model_id else ("minecraft", model_id))
    name = "assets/%s/models/%s.json" % (namespace, path)
    if name not in zf.namelist():
        raise SystemExit("missing vanilla model: " + name)
    return json.loads(zf.read(name))


def resolve(zf, model_id):
    """{@return (textures, elements)}；textures 按父链合并，elements 取第一个有 elements 的模型。"""
    model = load_model(zf, model_id)
    textures = dict(model.get("textures") or {})
    elements = model.get("elements")
    parent = model.get("parent")
    seen = set()
    while elements is None and parent and parent not in seen:
        seen.add(parent)
        model = load_model(zf, parent)
        for key, value in (model.get("textures") or {}).items():
            textures.setdefault(key, value)
        elements = model.get("elements")
        parent = model.get("parent")
    if elements is None:
        raise SystemExit("no elements in " + model_id)
    return textures, elements


# ----------------------------------------------------------------------
# 分列 / UV 细分
# ----------------------------------------------------------------------

def default_uv(frm, to, direction):
    """原版 FaceBakery.defaultFaceUV 的等价物（16 单位贴图空间）。"""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    if direction == "down":
        return (x0, 16.0 - z1, x1, 16.0 - z0)
    if direction == "up":
        return (x0, z0, x1, z1)
    if direction == "north":
        return (16.0 - x1, 16.0 - y1, 16.0 - x0, 16.0 - y0)
    if direction == "south":
        return (x0, 16.0 - y1, x1, 16.0 - y0)
    if direction == "west":
        return (z0, 16.0 - y1, z1, 16.0 - y0)
    if direction == "east":
        return (16.0 - z1, 16.0 - y1, 16.0 - z0, 16.0 - y0)
    raise ValueError(direction)


def _remap(value0, value1, orig0, orig1, uv0, uv1):
    if orig1 == orig0:
        return uv0, uv0
    t0 = (value0 - orig0) / (orig1 - orig0)
    t1 = (value1 - orig0) / (orig1 - orig0)
    return uv0 + t0 * (uv1 - uv0), uv0 + t1 * (uv1 - uv0)


def subdivide_face_uv(face, orig_from, orig_to, sub_from, sub_to, direction):
    uv = face.get("uv")
    if uv is None:
        # 没有显式 UV 时，子盒自己的默认 UV 就是原默认 UV 的子区间；保持省略即可。
        return face
    orig = default_uv(orig_from, orig_to, direction)
    sub = default_uv(sub_from, sub_to, direction)
    u0, u1 = _remap(sub[0], sub[2], orig[0], orig[2], uv[0], uv[2])
    v0, v1 = _remap(sub[1], sub[3], orig[1], orig[3], uv[1], uv[3])
    out = dict(face)
    out["uv"] = [round(u0, 6), round(v0, 6), round(u1, 6), round(v1, 6)]
    return out


def breaks(lo, hi):
    """{@return [lo, ..., hi]}：在整数边界处切开；lo==hi 时只返回一个点。"""
    if hi <= lo:
        return [lo]
    values = [lo]
    x = math.floor(lo) + 1
    while x < hi:
        values.append(float(x))
        x += 1
    values.append(hi)
    return values


def split_element(element):
    """把一个盒元素沿 X/Z 切成 1/16 像素列；保留面与 UV/texture/shade 等属性。"""
    if "rotation" in element:
        raise SystemExit("rotated element is not supported: %s" % element.get("name"))
    frm = [float(v) for v in element["from"]]
    to = [float(v) for v in element["to"]]
    for axis in range(3):
        if frm[axis] > to[axis]:
            frm[axis], to[axis] = to[axis], frm[axis]
    xs = breaks(frm[0], to[0])
    zs = breaks(frm[2], to[2])
    extra = {k: v for k, v in element.items() if k not in ("from", "to", "faces")}
    cells = []
    for i in range(max(1, len(xs) - 1)):
        x0 = xs[i]
        x1 = xs[i + 1] if len(xs) > 1 else xs[i]
        for j in range(max(1, len(zs) - 1)):
            z0 = zs[j]
            z1 = zs[j + 1] if len(zs) > 1 else zs[j]
            sub_from = [x0, frm[1], z0]
            sub_to = [x1, to[1], z1]
            faces = {}
            for direction, face in (element.get("faces") or {}).items():
                faces[direction] = subdivide_face_uv(
                    face, frm, to, sub_from, sub_to, direction)
            cell = dict(extra)
            cell["from"] = sub_from
            cell["to"] = sub_to
            cell["faces"] = faces
            cells.append(cell)
    return cells


# ----------------------------------------------------------------------
# 红条：从原版顶面贴图里按"红色主导"识别，裁成小贴图，并生成逐像素平面
# ----------------------------------------------------------------------

BAR_BOUNDS = {}


def red_bar_coords(pixels):
    coords = set()
    for y, line in enumerate(pixels):
        for x, (r, g, b, a) in enumerate(line):
            if a > 0 and r >= 40 and g <= 80 and b <= 80 and r > 2 * g and r > 2 * b:
                coords.add((x, y))
    return coords


def make_bar_assets(zf, out_dir, source, out_name):
    """生成红条裁切贴图，缓存 (width, height, x0, z0) 供逐像素平面使用。"""
    pixels = texture_pixels(zf, source)
    coords = red_bar_coords(pixels)
    if not coords:
        raise SystemExit("no red bar found in textures/block/%s.png" % source)
    x0 = min(x for x, _ in coords)
    x1 = max(x for x, _ in coords)
    z0 = min(y for _, y in coords)
    z1 = max(y for _, y in coords)
    width = x1 - x0 + 1
    height = z1 - z0 + 1
    cropped = []
    for y in range(z0, z1 + 1):
        line = []
        for x in range(x0, x1 + 1):
            if (x, y) not in coords:
                # 红条区域必须是实心矩形，否则逐像素平面会漏像素。
                raise SystemExit("red bar in %s is not a solid rectangle at (%d,%d)"
                                 % (source, x, y))
            r, g, b, _ = pixels[y][x]
            line.append((r, g, b, 255))
        cropped.append(line)
    write_png(out_dir / "assets" / "liquidphase" / "textures" / "block" / (out_name + ".png"),
              width, height, cropped)
    BAR_BOUNDS[out_name] = (width, height, x0, z0)


def bar_cells(texture_name):
    """{@return 红条的逐像素平面}；y=0.01 避免与宿主顶面共面 z-fighting。"""
    width, height, x0, z0 = BAR_BOUNDS[texture_name]
    elements = []
    for j in range(height):
        for i in range(width):
            u0 = i / width * 16.0
            u1 = (i + 1) / width * 16.0
            v0 = j / height * 16.0
            v1 = (j + 1) / height * 16.0
            elements.append({
                "from": [float(x0 + i), 0.01, float(z0 + j)],
                "to": [float(x0 + i + 1), 0.01, float(z0 + j + 1)],
                "faces": {"up": {
                    "uv": [round(u0, 6), round(v0, 6), round(u1, 6), round(v1, 6)],
                    "texture": "#bar",
                }},
            })
    return elements


# ----------------------------------------------------------------------
# 元件模型
# ----------------------------------------------------------------------

def keep_rod_element(element):
    faces = element.get("faces") or {}
    textures = {face.get("texture") for face in faces.values()}
    if "#slab" in textures or "#top" in textures:
        return False
    if "#lock" in textures:
        return False
    return bool(textures)


def translate_down(element, pixels=2.0):
    out = dict(element)
    out["from"] = [element["from"][0], max(0.0, element["from"][1] - pixels), element["from"][2]]
    out["to"] = [element["to"][0], max(0.0, element["to"][1] - pixels), element["to"][2]]
    if out["from"][1] > out["to"][1]:
        out["from"][1], out["to"][1] = out["to"][1], out["from"][1]
    return out


def element_model(zf, vanilla_id, out_id, bar_texture, out_dir):
    textures, elements = resolve(zf, vanilla_id)
    kept = [translate_down(element) for element in elements if keep_rod_element(element)]
    if not kept:
        raise SystemExit("no rod elements left in " + vanilla_id)
    split = []
    for element in kept:
        split.extend(split_element(element))
    # 模型贴图：只保留杆需要的原版贴图，加上红条贴图。
    model_textures = {}
    for key in ("particle", "unlit", "lit", "torch"):
        if key in textures:
            model_textures[key] = textures[key]
    model_textures["bar"] = "liquidphase:block/" + bar_texture
    out = {
        "ambientocclusion": False,
        "textures": model_textures,
        "elements": split + bar_cells(bar_texture),
    }
    target = out_dir / "assets" / "liquidphase" / "models" / "block" / (out_id + ".json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, separators=(",", ":"), sort_keys=False), encoding="utf-8")
    return out_id


# 侦测器按观察面预烘焙：6 个朝向各一份，运行期不再旋转。
# facing = 观察面；正面（五官）朝 facing，背面（输出口）朝 facing.getOpposite()。
OBSERVER_FACES = {
    "north": ("north", "south"),
    "south": ("south", "north"),
    "east": ("east", "west"),
    "west": ("west", "east"),
    "up": ("up", "down"),
    "down": ("down", "up"),
}
OPPOSITE = {
    "north": "south", "south": "north", "east": "west", "west": "east",
    "up": "down", "down": "up",
}


def _plane_for(face):
    """{@return 在该方块边界外侧 0.01 的 16×16 平面元素（只发 face 一个面）}"""
    if face == "north":
        frm, to = [0, 0, -0.01], [16, 16, -0.01]
    elif face == "south":
        frm, to = [0, 0, 16.01], [16, 16, 16.01]
    elif face == "west":
        frm, to = [-0.01, 0, 0], [-0.01, 16, 16]
    elif face == "east":
        frm, to = [16.01, 0, 0], [16.01, 16, 16]
    elif face == "up":
        frm, to = [0, 16.01, 0], [16, 16.01, 16]
    elif face == "down":
        frm, to = [0, -0.01, 0], [16, -0.01, 16]
    else:
        raise ValueError(face)
    return {"from": frm, "to": to,
            "faces": {face: {"uv": [0, 0, 16, 16], "texture": "#front"}}}


def observer_model(out_dir, facing, powered):
    front = "element_observer_front"
    back = "element_observer_port" + ("_on" if powered else "")
    front_face, back_face = OBSERVER_FACES[facing]
    front_element = _plane_for(front_face)
    back_element = _plane_for(back_face)
    back_element["faces"][back_face]["texture"] = "#back"
    out = {
        "ambientocclusion": False,
        "textures": {
            "particle": "liquidphase:block/" + front,
            "front": "liquidphase:block/" + front,
            "back": "liquidphase:block/" + back,
        },
        # 16×16 正常比例、位置固定（y=0..16），运行时不做任何抬升/旋转。
        # 只发侦测面/输出面两个平面；贴图 alpha 再裁掉非五官/非输出口区域。
        # 平面放在方块边界外侧 0.01：既有宿主（下半砖/楼梯）的侧脸不会把它挡住，
        # 也避免与宿主边界上的面共面 z-fighting——这就是"显示在宿主相应表面的上层"。
        "elements": [front_element, back_element],
    }
    suffix = "" if facing == "north" else "_" + facing
    out_id = "element_observer" + suffix + ("_on" if powered else "")
    target = out_dir / "assets" / "liquidphase" / "models" / "block" / (out_id + ".json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, separators=(",", ":"), sort_keys=False), encoding="utf-8")
    return out_id


def make_observer_cutout(zf, out_dir, source, out_name, rect):
    pixels = texture_pixels(zf, source)
    x0, y0, x1, y1 = rect
    out = []
    for y in range(16):
        line = []
        for x in range(16):
            r, g, b, a = pixels[y][x]
            if x0 <= x <= x1 and y0 <= y <= y1:
                line.append((r, g, b, a))
            else:
                line.append((0, 0, 0, 0))
        out.append(line)
    write_png(out_dir / "assets" / "liquidphase" / "textures" / "block" / (out_name + ".png"),
              16, 16, out)


def main():
    if len(sys.argv) != 3:
        print("usage: python tools/gen_element_models.py <client-jar> <out-dir>", file=sys.stderr)
        return 2
    jar = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    written = []
    with zipfile.ZipFile(jar) as zf:
        # 红条裁切贴图（原版顶面贴图里红色主导的像素；中继器 2×8、比较器 8×2）
        make_bar_assets(zf, out_dir, "repeater", "element_repeater_bar")
        make_bar_assets(zf, out_dir, "repeater_on", "element_repeater_bar_on")
        make_bar_assets(zf, out_dir, "comparator", "element_comparator_bar")
        make_bar_assets(zf, out_dir, "comparator_on", "element_comparator_bar_on")

        # 中继器：4 延迟 × (off/on) × (unlocked/locked)。锁存条不渲染，locked 只是逻辑状态。
        for delay in range(1, 5):
            for powered in (False, True):
                for locked in (False, True):
                    vanilla = "minecraft:block/repeater_%dtick%s%s" % (
                        delay, "_on" if powered else "", "_locked" if locked else "")
                    out_id = "element_repeater_%dtick%s%s" % (
                        delay, "_on" if powered else "", "_locked" if locked else "")
                    bar = "element_repeater_bar_on" if powered else "element_repeater_bar"
                    written.append(element_model(zf, vanilla, out_id, bar, out_dir))
        # 比较器
        for powered in (False, True):
            for subtract in (False, True):
                vanilla = "minecraft:block/comparator%s%s" % (
                    "_on" if powered else "", "_subtract" if subtract else "")
                out_id = "element_comparator%s%s" % (
                    "_on" if powered else "", "_subtract" if subtract else "")
                bar = "element_comparator_bar_on" if powered else "element_comparator_bar"
                written.append(element_model(zf, vanilla, out_id, bar, out_dir))

        # 侦测器：五官（正面中央面板）/ 输出口（背面中央端口）。
        # 区域取自原版贴图：正面 y=6..14、x=1..14；背面 y=5..10、x=5..10。
        make_observer_cutout(zf, out_dir, "observer_front", "element_observer_front",
                             (1, 6, 14, 14))
        make_observer_cutout(zf, out_dir, "observer_back", "element_observer_port",
                             (5, 5, 10, 10))
        make_observer_cutout(zf, out_dir, "observer_back_on", "element_observer_port_on",
                             (5, 5, 10, 10))
        for facing in OBSERVER_FACES:
            for powered in (False, True):
                written.append(observer_model(out_dir, facing, powered))

        dynamic = out_dir / "assets" / "liquidphase" / "models" / "block" / "dynamic.json"
        dynamic.parent.mkdir(parents=True, exist_ok=True)
        dynamic.write_text("{\"textures\":{}}", encoding="utf-8")
        written.append("dynamic")

    expected = 16 + 4 + 6 * 2 + 1
    print("wrote %d element models + 7 textures -> %s" % (len(written), out_dir))
    if len(written) != expected:
        print("ERROR: expected %d models, wrote %d" % (expected, len(written)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
