#!/usr/bin/env python3
"""生成"相覆盖"blockstate JSON（规划 §3.6 渲染方案 (a)，决策点 D11）。

对每个相容方块 id：从 Minecraft 客户端 jar 提取原版 blockstate JSON，
把 variants 转换为等价的 multipart（每个变体一条 when 条件），并追加：

- 固体相覆盖层（obsidian / ice / frosted_0..3）：普通宿主用内缩 1/64 的立方体，
  整格模型宿主用 0..16 的整格模型。内缩覆盖层**不带 cullface**：它的面不在方块边界上，
  原版 cullface 会拿"邻居是否盖住整面"去判断，对台阶/楼梯这类部分覆盖的邻居会错误剔掉
  不该剔的面（0.1.9 的实机 bug）。
- 红石相外壳：16 条 ``when{phase: redstone_N}`` 的 part，各自指向按功率着色的外壳模型
  （``gen_redstone_assets.py`` 生成）。外壳是**叠加层**，宿主照常渲染。

熔岩相不覆盖——由原版流体渲染直接绘制。

整格模型宿主（树叶 / 铜格栅 / 红树根 / 白杨树叶等原版模型本身就是 0..16 完整立方体）
改用整格（0..16）覆盖模型 phase_*_full，并把原版 part 的 when 合并上
"phase": "none|lava|<redstone>"——这些相下宿主模型不渲染、只渲染整格覆盖层
（与这些相"整格实心碰撞"的设定一致）；否则内缩的覆盖层会被完整立方体
宿主挡住（树叶贴图小孔外完全看不到相）。

**整格宿主的判定不再是手写白名单**：这里直接从客户端 jar 解析每个宿主 blockstate
能引用的全部模型，按几何判定"是否恰好铺满 0..16 立方体且六个边界面都被完全覆盖"。
26.3 新增的白杨树叶就是靠这条自动判定才被正确识别的（手写清单会漏，漏了就等于
"含冰相完全看不见"）。

用法: python gen_phase_blockstates.py <client-jar> <ids-file> <out-dir>
输出: <out-dir>/assets/minecraft/blockstates/<id>.json
"""
import json
import sys
import zipfile
from pathlib import Path

# 相覆盖模型：键 = phase 取值，值 = 模型 id。熔岩相（lava）不需要覆盖层。
# 普通宿主用内缩版；整格模型宿主用整格版（phase_*_full）。
OVERLAYS = [
    ("obsidian", "liquidphase:block/phase_obsidian"),
    ("ice", "liquidphase:block/phase_ice"),
    ("frosted_0", "liquidphase:block/phase_frosted_0"),
    ("frosted_1", "liquidphase:block/phase_frosted_1"),
    ("frosted_2", "liquidphase:block/phase_frosted_2"),
    ("frosted_3", "liquidphase:block/phase_frosted_3"),
]

FULL_OVERLAYS = [
    ("obsidian", "liquidphase:block/phase_obsidian_full"),
    ("ice", "liquidphase:block/phase_ice_full"),
    ("frosted_0", "liquidphase:block/phase_frosted_0_full"),
    ("frosted_1", "liquidphase:block/phase_frosted_1_full"),
    ("frosted_2", "liquidphase:block/phase_frosted_2_full"),
    ("frosted_3", "liquidphase:block/phase_frosted_3_full"),
]

REDSTONE_POWERS = range(16)
REDSTONE_PHASES = "|".join("redstone_%d" % i for i in REDSTONE_POWERS)

# 整格宿主的原版 part 只在"宿主本身可见"的相下渲染：熔岩相仍是原版流体渲染，
# 红石相是"宿主 + 外壳叠加"（0.1.8 曾把宿主排除掉，结果树叶直接隐形）。
# 固体相（obsidian / ice / frosted_*）不在这里——那些相由整格覆盖层替换宿主外观。
#
# 注意**不能**把这 24 个取值全写进来：原版 KeyValueCondition.instantiate 发现
# "条件覆盖属性的全部取值"时会直接返回恒真谓词并打 `Condition ... is always true`
# 警告，1.21.10+ 每个方块状态各一条，加载日志直接被刷屏。
FULL_CUBE_HOST_CONDITION = {"phase": "none|lava|" + REDSTONE_PHASES}


def redstone_model(power):
    """按功率着色的外壳模型（由 tools/gen_redstone_assets.py 生成）。"""
    return "liquidphase:block/redstone_phase_%d" % power


# ---------------------------------------------------------------------------
# 整格宿主的自动判定（不再手写白名单）
# ---------------------------------------------------------------------------
# 判据：宿主 blockstate 能引用的**每一个**模型
#   ① 沿 parent 链解析出的 elements 不含 rotation；
#   ② 全部 element 的包围盒恰好是 0..16；
#   ③ 六个边界面（x/y/z = 0 与 16）都被面片段完全覆盖。
#
# 为什么要检查③：包围盒是整格**不等于**视觉上是实心立方体。原版 `mangrove_roots`
# 就是"外皮 + 内部十字"的结构，六个边界面确实被 0.002 厚的薄板铺满，所以它是整格宿主；
# 而台阶/楼梯的包围盒虽然是整格（两个长方体拼满对角），六个边界面远没有被铺满，
# 必须留在内缩路径。只按包围盒判定会把所有楼梯/台阶都误判成整格宿主。
PLANE_STEP = 0.5  # 0.5px 栅格足以区分原版模型的 0.002 级偏移


def _covers_full(rects):
    if not rects:
        return False
    n = int(round(16 / PLANE_STEP))
    grid = bytearray(n * n)
    for a0, a1, b0, b1 in rects:
        i0 = max(0, int(round(a0 / PLANE_STEP)))
        i1 = min(n, int(round(a1 / PLANE_STEP)))
        j0 = max(0, int(round(b0 / PLANE_STEP)))
        j1 = min(n, int(round(b1 / PLANE_STEP)))
        for i in range(i0, i1):
            grid[i * n + j0:i * n + j1] = b"\x01" * (j1 - j0)
    return all(grid)


def is_full_cube_geometry(elements):
    if not elements:
        return False
    lo = [99.0, 99.0, 99.0]
    hi = [-99.0, -99.0, -99.0]
    planes = {}
    for e in elements:
        if "rotation" in e:
            return False
        frm, to = e["from"], e["to"]
        for axis in range(3):
            lo[axis] = min(lo[axis], frm[axis])
            hi[axis] = max(hi[axis], to[axis])
        for axis in range(3):
            for side in (0.0, 16.0):
                if frm[axis] != side and to[axis] != side:
                    continue
                a, b = [i for i in range(3) if i != axis]
                planes.setdefault((axis, side), []).append((frm[a], to[a], frm[b], to[b]))
    if lo != [0.0, 0.0, 0.0] or hi != [16.0, 16.0, 16.0]:
        return False
    return all(_covers_full(planes.get((axis, side), []))
               for axis in range(3) for side in (0.0, 16.0))


class ModelGeometry:
    """从客户端 jar 里按需解析模型几何（只缓存"是否整格"这一结论）。"""

    def __init__(self, zf):
        self.zf = zf
        self.names = set(zf.namelist())
        self._raw = {}
        self._elements = {}
        self._full = {}

    def _model(self, model_id):
        if model_id in self._raw:
            return self._raw[model_id]
        ns, path = (model_id.split(":", 1) if ":" in model_id
                    else ("minecraft", model_id))
        name = "assets/%s/models/%s.json" % (ns, path)
        data = json.loads(self.zf.read(name)) if name in self.names else None
        self._raw[model_id] = data
        return data

    def elements(self, model_id):
        if model_id in self._elements:
            return self._elements[model_id]
        chain, seen, found = [], set(), None
        cur = model_id
        while cur and cur not in seen:
            seen.add(cur)
            data = self._model(cur)
            if data is None:
                break
            chain.append(cur)
            if "elements" in data:
                found = data["elements"]
                break
            cur = data.get("parent")
        self._elements[model_id] = found
        return found

    def is_full_cube(self, model_id):
        if model_id in self._full:
            return self._full[model_id]
        result = is_full_cube_geometry(self.elements(model_id))
        self._full[model_id] = result
        return result


def _is_combiner(when):
    return isinstance(when, dict) and len(when) == 1 and next(iter(when)) in ("AND", "OR")


def and_condition(when, extra):
    """把 when 与 extra（{属性: "a|b"}）按 AND 合并成**原版合法**的 multipart 条件。

    原版 `when` 的语法（见 KeyValueCondition / CombinedCondition）：
    - 一个 object 内的多个键 = AND；
    - 值里的 `a|b` = 同一属性的 OR；
    - 跨属性组合必须显式写成 {"AND": [...]} / {"OR": [...]}，
      且一个 object 里**不能**同时出现 AND/OR 与属性键（解码会整条失败）。

    所以**绝对不能用 "(a)&(b)" 这种拼接**：原版会把它当成一个属性取值去查，
    抛 `Unknown value ...`，整个 blockstate 文件加载失败，
    该方块所有状态都退化成 missing model（紫黑方块）。
    """
    when = dict(when) if when else {}
    extra = dict(extra) if extra else {}
    if not extra:
        return when
    if not when:
        return extra
    # 已经以 AND 开头：扁平化追加一项
    if _is_combiner(when) and "AND" in when:
        return {"AND": list(when["AND"]) + [extra]}
    # OR 或不含冲突键的普通属性表：
    if _is_combiner(when) or (set(extra) & set(when)):
        return {"AND": [when, extra]}
    merged = dict(when)
    merged.update(extra)
    return merged


def variant_entry_to_apply(entry):
    """variants 条目 → multipart 的 apply 值（丢弃 weight，multipart 不支持）。"""
    if isinstance(entry, list):
        return [variant_entry_to_apply(e) for e in entry]
    apply = {"model": entry["model"]}
    for key in ("x", "y", "z", "uvlock"):
        if key in entry:
            apply[key] = entry[key]
    return apply


def variant_key_to_when(key):
    """"facing=north,waterlogged=true" → {"facing": "north", ...}；空键 → None（无条件）。"""
    key = key.strip()
    if not key:
        return None
    when = {}
    for piece in key.split(","):
        piece = piece.strip()
        if not piece:
            continue
        prop, _, value = piece.partition("=")
        when[prop.strip()] = value.strip()
    return when or None


def host_parts(state_json):
    """宿主 blockstate → multipart part 列表（variants 逐条转 when，multipart 原样保留）。"""
    parts = []
    if "variants" in state_json:
        for key, entry in state_json["variants"].items():
            part = {}
            when = variant_key_to_when(key)
            if when is not None:
                part["when"] = when
            part["apply"] = variant_entry_to_apply(entry)
            parts.append(part)
    elif "multipart" in state_json:
        for src in state_json["multipart"]:
            parts.append({k: v for k, v in src.items()})
    else:
        raise ValueError("unknown blockstate layout")
    return parts


def referenced_models(parts):
    models = set()

    def walk(apply):
        if isinstance(apply, list):
            for sub in apply:
                walk(sub)
        else:
            models.add(apply["model"])

    for part in parts:
        walk(part["apply"])
    return models


def convert(state_json, geometry):
    """宿主原版 part + 相覆盖层 + 红石外壳。

    **宿主 part 不带 phase 条件**（普通宿主）：覆盖层与外壳都是叠加层，宿主照常渲染。
    这样既不会出现"含相时宿主消失"，也不会给客户端塞一条恒真条件
    （`KeyValueCondition.instantiate` 会为它打 `Condition ... is always true` 警告，
    1.21.10+ 每个方块状态各一条，加载日志直接被刷屏）。

    整格宿主例外：宿主 part 要限制在"宿主可见"的相（none|lava|redstone_*），
    固体相下换成整格相材质。
    """
    parts = host_parts(state_json)
    full_cube = bool(parts) and all(geometry.is_full_cube(m)
                                    for m in referenced_models(parts))
    overlays = FULL_OVERLAYS if full_cube else OVERLAYS

    if full_cube:
        for part in parts:
            part["when"] = and_condition(part.get("when"), FULL_CUBE_HOST_CONDITION)

    for value, model in overlays:
        parts.append({"when": {"phase": value}, "apply": {"model": model}})

    # 红石外壳对**全部**宿主生成（不只 41 个楼梯/台阶/墙/栅栏）。
    # 任何相容宿主都能被写入 phase=redstone_*；只给一部分生成时，其余宿主
    # （玻璃板、铁栏杆、梯子、锁链、树叶……这些"透明方块"最明显）含相了却看不到红石。
    # 16 条 part 各自指向按功率着色的模型（0 档暗、15 档亮，对齐原版红石线）。
    for power in REDSTONE_POWERS:
        parts.append({
            "when": {"phase": "redstone_%d" % power},
            "apply": {"model": redstone_model(power)},
        })
    return {"multipart": parts}, full_cube


# ---------------------------------------------------------------------------
# 产出前自检：按原版 multipart 解析语义校验 when 条件。
# 原版会把 when 的每个值按 "|" 拆成 term，逐项去 StateDefinition 的属性取值为里查；
# 查不到就抛 Unknown value / Unknown property，整份 blockstate 加载失败。
# ---------------------------------------------------------------------------
PHASE_VALUES = frozenset(
    ["none", "lava", "obsidian", "ice", "frosted_0", "frosted_1", "frosted_2", "frosted_3"]
    + ["redstone_%d" % i for i in REDSTONE_POWERS]
)


def harvest_condition_values(state_json, known):
    """收集原版 blockstate 里出现过的 属性名 → 取值集合（用于校验我们新加的条件）。"""
    def walk(cond):
        if not isinstance(cond, dict):
            return
        for key, value in cond.items():
            if key in ("AND", "OR"):
                for sub in (value if isinstance(value, list) else []):
                    walk(sub)
                continue
            if isinstance(value, str):
                known.setdefault(key, set()).update(t.lstrip("!") for t in value.split("|"))

    for key in (state_json.get("variants") or {}):
        for piece in key.split(","):
            if "=" in piece:
                k, v = piece.split("=", 1)
                known.setdefault(k.strip(), set()).add(v.strip())
    for part in (state_json.get("multipart") or []):
        walk(part.get("when"))


def validate_result(state_json, known):
    """返回非法条件列表；空列表表示这份 blockstate 能被原版解析器接受。"""
    problems = []

    def walk(cond, where):
        if not isinstance(cond, dict):
            problems.append("%s: condition is not an object" % where)
            return
        if any(k in ("AND", "OR") for k in cond):
            if len(cond) != 1:
                # 原版 CombinedCondition 解码要求恰好一个 AND/OR 键，混入属性键整条失败。
                problems.append("%s: AND/OR mixed with property keys %s" % (where, sorted(cond)))
                return
            entries = next(iter(cond.values()))
            if not isinstance(entries, list) or not entries:
                problems.append("%s: empty combiner" % where)
                return
            for i, sub in enumerate(entries):
                walk(sub, "%s/%d" % (where, i))
            return
        for key, value in cond.items():
            valid = PHASE_VALUES if key == "phase" else known.get(key)
            if valid is None:
                problems.append("%s: unknown property '%s'" % (where, key))
                continue
            if not isinstance(value, str):
                problems.append("%s: non-string value for '%s'" % (where, key))
                continue
            for term in value.split("|"):
                if term.lstrip("!") not in valid:
                    problems.append("%s: unknown value '%s' for '%s'" % (where, term, key))
                    break

    for i, part in enumerate(state_json.get("multipart") or []):
        walk(part.get("when") or {}, "part%d" % i)
    return problems


def main():
    jar = Path(sys.argv[1])
    ids_file = Path(sys.argv[2])
    out_dir = Path(sys.argv[3])
    ids = [line.strip() for line in ids_file.read_text(encoding="utf-8").splitlines() if line.strip()]

    converted = 0
    full_cube_hosts = []
    missing = []
    problems = []
    known = {}
    with zipfile.ZipFile(jar) as zf:
        geometry = ModelGeometry(zf)
        names = set(zf.namelist())
        # 先在整包里收集"属性名 → 合法取值"，用于产出前自检（原版解析器就是按这个口径查的）。
        for name in names:
            if not (name.startswith("assets/minecraft/blockstates/") and name.endswith(".json")):
                continue
            try:
                state = json.loads(zf.read(name).decode("utf-8"))
            except Exception:
                continue
            harvest_condition_values(state, known)

        for block_id in ids:
            path = "assets/minecraft/blockstates/%s.json" % block_id
            if path not in names:
                missing.append(block_id)
                continue
            state = json.loads(zf.read(path).decode("utf-8"))
            result, full_cube = convert(state, geometry)
            bad = validate_result(result, known)
            if bad:
                problems.append((block_id, bad[:3]))
            target = out_dir / "assets" / "minecraft" / "blockstates" / ("%s.json" % block_id)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(result, separators=(",", ":"), sort_keys=False), encoding="utf-8")
            converted += 1
            if full_cube:
                full_cube_hosts.append(block_id)

    if missing:
        print("WARNING: %d ids missing blockstates in jar: %s%s"
              % (len(missing), missing[:10], "..." if len(missing) > 10 else ""))
    print("converted %d/%d blockstates (%d full-cube hosts) -> %s"
          % (converted, len(ids), len(full_cube_hosts), out_dir))
    print("full-cube hosts: %s" % ", ".join(full_cube_hosts))
    if problems:
        # 生成出来的条件如果原版解析器读不了，整份 blockstate 会导致该方块所有状态变 missing model。
        # 这里硬失败，避免把坏资源打进 jar（历史 bug：0.1.5 的 "(a)&(b)" 伪语法）。
        print("ERROR: %d blockstates have invalid multipart conditions:" % len(problems), file=sys.stderr)
        for block_id, sample in problems[:20]:
            print("  %s: %s" % (block_id, sample), file=sys.stderr)
        return 1
    print("validated: all multipart conditions parse under vanilla semantics")
    return 0


if __name__ == "__main__":
    sys.exit(main())
