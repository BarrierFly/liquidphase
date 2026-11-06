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

# 整格宿主的原版 part 只在 phase=none|lava 时渲染（multipart when 的值支持
# "|" OR，原版 redstone_wire.json 即此用法）；黑曜石/冰/霜冰相只渲染整格覆盖层。
PHASE_HOST_CONDITION = {"phase": "none|lava"}


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
    """原 part 的 when 合并上 phase 条件；when 里若带嵌套 OR/AND 键（原版写法），
    顶层同时存在属性键与 OR/AND 键时按 AND 语义合并，与原版解析一致。"""
    merged = dict(when) if when else {}
    merged.update(PHASE_HOST_CONDITION)
    return merged


def convert(state_json: dict, full_cube: bool) -> dict:
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
    return {"multipart": parts}


def main():
    jar = Path(sys.argv[1])
    ids_file = Path(sys.argv[2])
    out_dir = Path(sys.argv[3])
    ids = [line.strip() for line in ids_file.read_text(encoding="utf-8").splitlines() if line.strip()]

    converted = 0
    full_cube = 0
    missing = []
    with zipfile.ZipFile(jar) as zf:
        names = set(zf.namelist())
        for block_id in ids:
            path = f"assets/minecraft/blockstates/{block_id}.json"
            if path not in names:
                missing.append(block_id)
                continue
            state = json.loads(zf.read(path).decode("utf-8"))
            is_full_cube = block_id in FULL_CUBE_HOSTS
            result = convert(state, is_full_cube)
            target = out_dir / "assets" / "minecraft" / "blockstates" / f"{block_id}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(result, separators=(",", ":"), sort_keys=False), encoding="utf-8")
            converted += 1
            if is_full_cube:
                full_cube += 1

    if missing:
        print(f"WARNING: {len(missing)} ids missing blockstates in jar: {missing[:10]}{'...' if len(missing) > 10 else ''}")
    print(f"converted {converted}/{len(ids)} blockstates ({full_cube} full-cube hosts) -> {out_dir}")


if __name__ == "__main__":
    main()
