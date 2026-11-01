#!/usr/bin/env python3
"""生成"相覆盖"blockstate JSON（规划 §3.6 渲染方案 (a)，决策点 D11）。

对每个相容方块 id：从 Minecraft 客户端 jar 提取原版 blockstate JSON，
把 variants 转换为等价的 multipart（每个变体一条 when 条件），并追加
phase=obsidian / ice / frosted_0..3 的覆盖层模型（整体内缩 1/64 + cullface，
贴实心邻居的面被剔除；熔岩相不覆盖——由原版流体渲染直接绘制）。

用法: python gen_phase_blockstates.py <client-jar> <ids-file> <out-dir>
输出: <out-dir>/assets/minecraft/blockstates/<id>.json
"""
import json
import sys
import zipfile
from pathlib import Path

# 相覆盖模型：键 = phase 取值，值 = 模型 id。熔岩相（lava）不需要覆盖层。
OVERLAYS = [
    ("obsidian", "liquidphase:block/phase_obsidian"),
    ("ice", "liquidphase:block/phase_ice"),
    ("frosted_0", "liquidphase:block/phase_frosted_0"),
    ("frosted_1", "liquidphase:block/phase_frosted_1"),
    ("frosted_2", "liquidphase:block/phase_frosted_2"),
    ("frosted_3", "liquidphase:block/phase_frosted_3"),
]


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


def convert(state_json: dict) -> dict:
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
        parts.extend(state_json["multipart"])
    else:
        raise ValueError("unknown blockstate layout")
    for value, model in OVERLAYS:
        parts.append({"when": {"phase": value}, "apply": {"model": model}})
    return {"multipart": parts}


def main():
    jar = Path(sys.argv[1])
    ids_file = Path(sys.argv[2])
    out_dir = Path(sys.argv[3])
    ids = [line.strip() for line in ids_file.read_text(encoding="utf-8").splitlines() if line.strip()]

    converted = 0
    missing = []
    with zipfile.ZipFile(jar) as zf:
        names = set(zf.namelist())
        for block_id in ids:
            path = f"assets/minecraft/blockstates/{block_id}.json"
            if path not in names:
                missing.append(block_id)
                continue
            state = json.loads(zf.read(path).decode("utf-8"))
            result = convert(state)
            target = out_dir / "assets" / "minecraft" / "blockstates" / f"{block_id}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(result, separators=(",", ":"), sort_keys=False), encoding="utf-8")
            converted += 1

    if missing:
        print(f"WARNING: {len(missing)} ids missing blockstates in jar: {missing[:10]}{'...' if len(missing) > 10 else ''}")
    print(f"converted {converted}/{len(ids)} blockstates -> {out_dir}")


if __name__ == "__main__":
    main()
