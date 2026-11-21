#!/usr/bin/env python3
"""生成元件相的子模型（方案 C，动态模型按坐标发射）。

从客户端 jar 的**原版**模型复制几何与贴图，产出：

- ``element_repeater_<1..4>tick[_on][_locked].json``   （中继器，4 延迟 × 供电 × 锁存）
- ``element_comparator[_on][_subtract].json``           （比较器）
- ``element_observer[_on].json``                        （侦测器，8×8×8 小立方体）
- ``dynamic.json``                                      （无条件 part 的空模型；旧代由自定义
                                                        UnbakedModel 接管，新代由 BlockStateModel
                                                        包装器发射几何）

为什么复制而不是直接引用 ``minecraft:block/repeater_1tick``：
动态模型要按坐标发射子模型，跨 6 个版本解析"原版模型 id → baked part"的 API 有两代
（``ModelBaker#bake`` / ``Variant#bake``），而引用**我们自己的**模型 id 可以把依赖声明、
烘焙和版本分支全部收敛到一处；几何与贴图仍与原版逐 element 一致。

用法: python tools/gen_element_models.py <client-jar> <out-dir>
"""
import json
import sys
import zipfile
from pathlib import Path


def load_model(zf, model_id):
    ns, path = (model_id.split(":", 1) if ":" in model_id else ("minecraft", model_id))
    name = "assets/%s/models/%s.json" % (ns, path)
    if name not in zf.namelist():
        raise SystemExit("missing vanilla model: " + name)
    return json.loads(zf.read(name))


def resolve_textures(model):
    textures = dict(model.get("textures") or {})
    parent = model.get("parent")
    seen = set()
    while parent and "elements" not in model and parent not in seen:
        seen.add(parent)
        model = load_model(zf_global, parent)
        for key, value in (model.get("textures") or {}).items():
            textures.setdefault(key, value)
        if "elements" in model:
            break
    return textures


def copy_model(zf, vanilla_id, out_id, rewrite=None):
    model = load_model(zf, vanilla_id)
    textures = dict(model.get("textures") or {})
    parent = model.get("parent")
    seen = set()
    elements = model.get("elements")
    while elements is None and parent and parent not in seen:
        seen.add(parent)
        model = load_model(zf, parent)
        for key, value in (model.get("textures") or {}).items():
            textures.setdefault(key, value)
        elements = model.get("elements")
    if elements is None:
        raise SystemExit("no elements in %s" % vanilla_id)
    out = {"textures": textures, "elements": elements}
    if rewrite:
        rewrite(out)
    target = out_dir / "assets" / "liquidphase" / "models" / "block" / (out_id + ".json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, separators=(",", ":"), sort_keys=False), encoding="utf-8")
    return out_id


def observer_model(powered):
    textures = {
        "particle": "minecraft:block/observer_front",
        "front": "minecraft:block/observer_front",
        "back": "minecraft:block/observer_back_on" if powered else "minecraft:block/observer_back",
        "side": "minecraft:block/observer_side",
        "top": "minecraft:block/observer_top",
    }
    # 8×8×8，坐在宿主表面上方（y 从 0 开始，动态模型再按列抬升）。
    # 模型朝向约定：观察面（五官）= north，输出口 = south。
    faces = {
        "north": {"uv": [0, 0, 16, 16], "texture": "#front"},
        "south": {"uv": [0, 0, 16, 16], "texture": "#back"},
        "west": {"uv": [0, 0, 16, 16], "texture": "#side"},
        "east": {"uv": [0, 0, 16, 16], "texture": "#side"},
        "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
        "down": {"uv": [0, 0, 16, 16], "texture": "#top"},
    }
    elements = [{"from": [4, 0, 4], "to": [12, 8, 12], "faces": faces}]
    out_id = "element_observer_on" if powered else "element_observer"
    target = out_dir / "assets" / "liquidphase" / "models" / "block" / (out_id + ".json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"textures": textures, "elements": elements},
                                 separators=(",", ":"), sort_keys=False), encoding="utf-8")
    return out_id


zf_global = None
out_dir = None


def main():
    global zf_global, out_dir
    jar = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    written = []
    with zipfile.ZipFile(jar) as zf:
        zf_global = zf
        # 中继器：4 延迟 × (off/on) × (unlocked/locked)
        for delay in range(1, 5):
            for powered in (False, True):
                for locked in (False, True):
                    vanilla = "minecraft:block/repeater_%dtick%s%s" % (
                        delay, "_on" if powered else "", "_locked" if locked else "")
                    out_id = "element_repeater_%dtick%s%s" % (
                        delay, "_on" if powered else "", "_locked" if locked else "")
                    written.append(copy_model(zf, vanilla, out_id))
        # 比较器
        for powered in (False, True):
            for subtract in (False, True):
                vanilla = "minecraft:block/comparator%s%s" % (
                    "_on" if powered else "", "_subtract" if subtract else "")
                out_id = "element_comparator%s%s" % (
                    "_on" if powered else "", "_subtract" if subtract else "")
                written.append(copy_model(zf, vanilla, out_id))
        # 侦测器
        written.append(observer_model(False))
        written.append(observer_model(True))
        # 空模型：只作为 blockstate 里的无条件 part，旧代被自定义 UnbakedModel 接管
        dynamic = out_dir / "assets" / "liquidphase" / "models" / "block" / "dynamic.json"
        dynamic.parent.mkdir(parents=True, exist_ok=True)
        dynamic.write_text("{\"textures\":{}}", encoding="utf-8")
        written.append("dynamic")

    expected = 16 + 4 + 2 + 1
    print("wrote %d element models -> %s" % (len(written), out_dir))
    if len(written) != expected:
        print("ERROR: expected %d models, wrote %d" % (expected, len(written)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
