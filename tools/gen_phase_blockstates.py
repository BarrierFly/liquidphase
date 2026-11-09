#!/usr/bin/env python3
"""生成"相覆盖"blockstate JSON（规划 §3.6 渲染方案 (a)，决策点 D11）。

对每个相容方块 id：从 Minecraft 客户端 jar 提取原版 blockstate JSON，
把 variants 转换为等价的 multipart（每个变体一条 when 条件），并追加
phase=obsidian / ice / frosted_0..3 的覆盖层模型（整体内缩 1/64 + cullface，
贴实心邻居的面被剔除；熔岩相不覆盖——由原版流体渲染直接绘制）。

整格模型宿主（树叶 / 铜格栅 / 红树根等原版模型本身就是 0..16 完整立方体）
改用整格（0..16）覆盖模型 phase_*_full，并把原版 part 的 when 合并上
"phase": "none|lava"——这些相下宿主模型不渲染、只渲染整格覆盖层
（与这些相"整格实心碰撞"的设定一致）；否则内缩的覆盖层会被完整立方体
宿主挡住（树叶贴图小孔外完全看不到相）。phase=none/lava 时宿主照常渲染。

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

# 整格模型宿主：原版模型是完整 0..16 立方体（cutout 贴图），内缩覆盖层会
# 藏在宿主后面。按 ids 文件自然过滤版本差异（不存在的 id 不会出现在清单里）。
FULL_CUBE_HOSTS = frozenset({
    "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves",
    "acacia_leaves", "dark_oak_leaves", "mangrove_leaves",
    "azalea_leaves", "flowering_azalea_leaves", "cherry_leaves",
    "pale_oak_leaves", "mangrove_roots",
    "copper_grate", "exposed_copper_grate", "weathered_copper_grate",
    "oxidized_copper_grate", "waxed_copper_grate", "waxed_exposed_copper_grate",
    "waxed_weathered_copper_grate", "waxed_oxidized_copper_grate",
})

# 整格宿主的原版 part 只在 phase=none|lava|<redstone_*> 时渲染（multipart when 的值支持
# "|" OR，原版 redstone_wire.json 即此用法）；黑曜石/冰/霜冰相只渲染整格覆盖层。
#
# ⚠️ 红石相必须留在整格宿主的渲染条件里：否则 `phase=redstone_*` 时宿主 part 不匹配、
# 又没有外壳 part，整格什么都渲染不出来——树叶/铜格栅/红树根这类"透明贴图整格宿主"
# 含红石相时直接变成隐形方块（0.1.8 的实机 bug）。
REDSTONE_PHASES = "|".join("redstone_%d" % i for i in range(16))
PHASE_HOST_CONDITION = {"phase": "none|lava|" + REDSTONE_PHASES}

# ---------------------------------------------------------------------------
# 红石相外壳（§3.4）：红石相是宿主上的 phase 取值（redstone_0 … redstone_15），
# 不是独立方块。对"红石相宿主清单"里的方块额外追加：
#   - 宿主 part 的 when 补上 phase 全集（= 任何相都照常渲染）
#   - 16 条 when{phase: redstone_N} 的 part，全部指向同一个外壳模型（叠加在宿主之上）
# 外壳模型/贴图由 tools/gen_redstone_assets.py 生成。
# ---------------------------------------------------------------------------
REDSTONE_MODEL_ID = "liquidphase:block/redstone_phase"
NON_REDSTONE_PHASES = "none|lava|obsidian|ice|frosted_0|frosted_1|frosted_2|frosted_3"
# 全部 24 个 phase 取值：宿主模型在**任何**相下都要渲染（红石相只是往上叠粉尘外壳）
ALL_PHASES = NON_REDSTONE_PHASES + "|" + "|".join("redstone_%d" % i for i in range(16))


def load_redstone_hosts(path):
    if path is None or not Path(path).exists():
        return set()
    text = Path(path).read_text(encoding="utf-8")
    return {line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")}


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


def add_redstone_overlay(parts):
    """红石相 = **宿主模型照常渲染 + 粉尘外壳叠加**（对齐旧版组合方块的行为）。

    关键：**不能**把宿主 part 限制成 `phase != redstone_*`。
    那样一来含红石相的状态下宿主根本不渲染，只剩一个整格外壳 →
    楼梯/台阶的形状消失、整格变成不透明粉尘面（实机表现就是"表面被不透明渲染"）。

    宿主 part 只在它**已有** phase 条件时（整格宿主的 none|lava 限制）原样保留；
    纯属性条件（facing/half/shape…）补上 `phase` 的全集，等于"任何相都渲染"。
    """
    result = []
    for part in parts:
        when = part.get("when") or {}
        if "phase" in condition_properties(when):
            result.append({"when": when, "apply": part["apply"]})
        else:
            result.append({
                "when": and_condition(when, {"phase": ALL_PHASES}),
                "apply": part["apply"],
            })
    for power in range(16):
        result.append({
            "when": {"phase": "redstone_%d" % power},
            "apply": {"model": REDSTONE_MODEL_ID},
        })
    return result


def condition_properties(when):
    """返回条件里出现过的所有属性名（穿透 AND/OR）。"""
    found = set()
    if not isinstance(when, dict):
        return found
    for key, value in when.items():
        if key in ("AND", "OR"):
            for sub in (value if isinstance(value, list) else []):
                found |= condition_properties(sub)
        else:
            found.add(key)
    return found


def variant_entry_to_apply(entry):
    """variants 条目 → multipart 的 apply 值（丢弃 weight，multipart 不支持）。"""
    if isinstance(entry, list):
        return [variant_entry_to_apply(e) for e in entry]
    apply = {"model": entry["model"]}
    for key in ("x", "y", "z", "uvlock"):
        if key in entry:
            apply[key] = entry[key]
    return apply


def variant_key_to_when(key: str):
    """"facing=north,waterlogged=true" → {"facing": "north", ...}；空键 → None（无条件）。"""
    key = key.strip()
    if not key:
        return None
    when = {}
    for part in key.split(","):
        part = part.strip()
        if not part:
            continue
        prop, _, value = part.partition("=")
        when[prop.strip()] = value.strip()
    return when or None


def merge_phase_condition(when):
    """整格宿主的原版 part 限制到 phase=none|lava（见 PHASE_HOST_CONDITION）。

    同样必须走 and_condition：原版 part 的 when 可能是 {"AND": [...]} 形式，
    直接塞一个 phase 键会和 AND 混在同一个 object 里，解码整条失败。
    """
    return and_condition(when, PHASE_HOST_CONDITION)


def convert(state_json: dict, full_cube: bool, redstone_host: bool = False) -> dict:
    overlays = FULL_OVERLAYS if full_cube else OVERLAYS
    parts = []
    if "variants" in state_json:
        for key, entry in state_json["variants"].items():
            part = {}
            when = variant_key_to_when(key)
            if full_cube:
                when = merge_phase_condition(when)
            if when is not None:
                part["when"] = when
            part["apply"] = variant_entry_to_apply(entry)
            parts.append(part)
    elif "multipart" in state_json:
        for src in state_json["multipart"]:
            part = dict(src)
            if full_cube:
                part["when"] = merge_phase_condition(part.get("when"))
            parts.append(part)
    else:
        raise ValueError("unknown blockstate layout")
    for value, model in overlays:
        parts.append({"when": {"phase": value}, "apply": {"model": model}})
    # 红石外壳对**全部**宿主都生成（不只 41 个楼梯/台阶/墙/栅栏）。
    # 原因：任何相容宿主都能被写入 phase=redstone_*，如果只给 41 个生成外壳，
    # 其余宿主（玻璃板、铁栏杆、梯子、锁链、树叶……这些"透明方块"最明显）在红石相下
    # 要么什么都不渲染、要么看不到红石——0.1.8 的"部分透明方块所含红石相还是看不到"。
    #
    # 整格宿主（树叶/铜格栅/红树根）也要外壳：它们的原版 part 已放开到全部相，
    # 但"相"本身需要一层粉尘才能看见。
    parts = add_redstone_overlay(parts)
    return {"multipart": parts}


# ---------------------------------------------------------------------------
# 产出前自检：按原版 multipart 解析语义校验 when 条件。
# 原版会把 when 的每个值按 "|" 拆成 term，逐项去 StateDefinition 的属性取值为里查；
# 查不到就抛 Unknown value / Unknown property，整份 blockstate 加载失败。
# ---------------------------------------------------------------------------
PHASE_VALUES = frozenset(
    ["none", "lava", "obsidian", "ice", "frosted_0", "frosted_1", "frosted_2", "frosted_3"]
    + ["redstone_%d" % i for i in range(16)]
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
            problems.append(f"{where}: condition is not an object")
            return
        if any(k in ("AND", "OR") for k in cond):
            if len(cond) != 1:
                # 原版 CombinedCondition 解码要求恰好一个 AND/OR 键，混入属性键整条失败。
                problems.append(f"{where}: AND/OR mixed with property keys {sorted(cond)}")
                return
            entries = next(iter(cond.values()))
            if not isinstance(entries, list) or not entries:
                problems.append(f"{where}: empty combiner")
                return
            for i, sub in enumerate(entries):
                walk(sub, f"{where}/{i}")
            return
        for key, value in cond.items():
            valid = PHASE_VALUES if key == "phase" else known.get(key)
            if valid is None:
                problems.append(f"{where}: unknown property '{key}'")
                continue
            if not isinstance(value, str):
                problems.append(f"{where}: non-string value for '{key}'")
                continue
            for term in value.split("|"):
                if term.lstrip("!") not in valid:
                    problems.append(f"{where}: unknown value '{term}' for '{key}'")
                    break

    for i, part in enumerate(state_json.get("multipart") or []):
        walk(part.get("when") or {}, f"part{i}")
    return problems


def main():
    jar = Path(sys.argv[1])
    ids_file = Path(sys.argv[2])
    out_dir = Path(sys.argv[3])
    redstone_hosts_file = sys.argv[4] if len(sys.argv) >= 5 else None
    ids = [line.strip() for line in ids_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    redstone_hosts = load_redstone_hosts(redstone_hosts_file)

    converted = 0
    full_cube = 0
    redstone = 0
    missing = []
    problems = []
    known = {}
    with zipfile.ZipFile(jar) as zf:
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
            path = f"assets/minecraft/blockstates/{block_id}.json"
            if path not in names:
                missing.append(block_id)
                continue
            state = json.loads(zf.read(path).decode("utf-8"))
            is_full_cube = block_id in FULL_CUBE_HOSTS
            is_redstone = block_id in redstone_hosts
            result = convert(state, is_full_cube, is_redstone)
            bad = validate_result(result, known)
            if bad:
                problems.append((block_id, bad[:3]))
            target = out_dir / "assets" / "minecraft" / "blockstates" / f"{block_id}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(result, separators=(",", ":"), sort_keys=False), encoding="utf-8")
            converted += 1
            if is_full_cube:
                full_cube += 1
            if is_redstone:
                redstone += 1

    if missing:
        print(f"WARNING: {len(missing)} ids missing blockstates in jar: {missing[:10]}{'...' if len(missing) > 10 else ''}")
    print(f"converted {converted}/{len(ids)} blockstates ({full_cube} full-cube hosts, "
          f"{redstone} redstone hosts) -> {out_dir}")
    if problems:
        # 生成出来的条件如果原版解析器读不了，整份 blockstate 会导致该方块所有状态变 missing model。
        # 这里硬失败，避免把坏资源打进 jar（历史 bug：0.1.5 的 "(a)&(b)" 伪语法）。
        print(f"ERROR: {len(problems)} blockstates have invalid multipart conditions:", file=sys.stderr)
        for block_id, sample in problems[:20]:
            print(f"  {block_id}: {sample}", file=sys.stderr)
        return 1
    print("validated: all multipart conditions parse under vanilla semantics")
    return 0


if __name__ == "__main__":
    sys.exit(main())
