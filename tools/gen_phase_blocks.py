#!/usr/bin/env python3
"""从 guardian 反编译源码的 Blocks.java 生成各版本"相容方块 ID 清单"。

用法: python gen_phase_blocks.py <guardian-repo-path> <output-dir> <closure-dir>
  closure-dir 内含 <版本>.txt（PhaseCapable 闭包类清单，每行一个类名）。
输出: <output-dir>/<版本>.ids  每行一个方块 id（排序去重）。

再生成时机：目标 MC 版本变化或 mixin 目标清单变化时重跑（见 AGENTS.md）。
"""
import re
import subprocess
import sys
from pathlib import Path

COMMITS = {
    "1.19.4": "37641fc907",
    "1.20.1": "41c32f0060",
    "1.21.1": "d47305d7cf",
    "1.21.10": "5d7d486906",
    "1.21.11": "192e7132b4",
    "26.3": "d5822ca2a1",
}

NEW_CLASS = re.compile(r"new\s+(\w+)\s*\(")
METHOD_REF = re.compile(r"(\w+)::new")
STRING = re.compile(r'"([a-z0-9_/\.]+)"')
CONST_REF = re.compile(r"\b(?:BlockItemIds|ItemIds)\.(\w+)\b")
CONST_DEF = re.compile(r"(\w+)\s*=\s*(?:BlockItemId|ItemId)\.create\(\s*\"([a-z0-9_/\.]+)\"")
COLORED_DEF = re.compile(r"(\w+)\s*=\s*createSimpleColored\(\s*\"([a-z0-9_/]+)\"\s*\)")
COLOR_NAMES = [
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
]
HELPER_DEF = re.compile(r"static\s+\w+\s+(\w+)\s*\(")


def git_show(repo: Path, commit: str, path: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), "show", f"{commit}:{path}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
    ).stdout


def balanced(src: str, start: int) -> str:
    """返回从 start（指向 '('）开始的括号配平片段。"""
    depth = 0
    for i in range(start, len(src)):
        c = src[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return src[start:start + 400]


def extract_class(expr: str, helpers: dict):
    m = NEW_CLASS.search(expr)
    if m:
        return m.group(1)
    m = METHOD_REF.search(expr)
    if m:
        return m.group(1)
    for m in re.finditer(r"(\w+)\s*\(", expr):
        if m.group(1) in helpers:
            return helpers[m.group(1)]
    return None


def main():
    repo = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    closure_dir = Path(sys.argv[3])
    out_dir.mkdir(parents=True, exist_ok=True)

    for version, commit in COMMITS.items():
        closure = set(closure_dir.joinpath(f"{version}.txt").read_text().split())
        blocks_src = git_show(repo, commit, "src/main/java/net/minecraft/world/level/block/Blocks.java")
        id_map = {}
        try:
            ids_src = git_show(repo, commit, "src/main/java/net/minecraft/references/BlockItemIds.java")
            for const, ident in CONST_DEF.findall(ids_src):
                id_map[const] = ident
        except subprocess.CalledProcessError:
            pass

        # helper 方法（registerLegacyStair / candle / legacyStair 等）→ 其方法体里的方块类
        helpers = {}
        for hm in HELPER_DEF.finditer(blocks_src):
            name = hm.group(1)
            if name == "register":
                continue
            brace = blocks_src.find("{", hm.end())
            if brace == -1:
                continue
            depth = 0
            end = brace
            for i in range(brace, len(blocks_src)):
                if blocks_src[i] == "{":
                    depth += 1
                elif blocks_src[i] == "}":
                    depth -= 1
                    if depth == 0:
                        end = i
                        break
            cls = extract_class(blocks_src[brace:end], {})
            if cls:
                helpers[name] = cls

        ids = set()
        colored_map = {}
        try:
            ids_src2 = git_show(repo, commit, "src/main/java/net/minecraft/references/BlockItemIds.java")
            for const, base in COLORED_DEF.findall(ids_src2):
                colored_map[const] = base
        except subprocess.CalledProcessError:
            pass

        for sm in re.finditer(r"public static final Block\s+\w+\s*=\s*(\w+)\s*\(", blocks_src):
            call_name = sm.group(1)
            call = blocks_src.index("(", sm.end() - 1)
            expr = balanced(blocks_src, call)
            sstr = STRING.search(expr)
            sid = sstr.group(1) if sstr else None
            if not sid:
                cm = CONST_REF.search(expr)
                if cm and cm.group(1) in id_map:
                    sid = id_map[cm.group(1)]
            if not sid:
                cm2 = CONST_REF.search(expr)
                if cm2 and cm2.group(1) in colored_map:
                    sid = colored_map[cm2.group(1)]
            if not sid:
                continue
            if call_name in helpers:
                cls = helpers[call_name]
            else:
                cls = extract_class(expr, helpers)
            if cls and cls in closure:
                ids.add(sid)
        # 批量集合：以 CONST 引用的 ColorCollection 语句展开为 16 色
        for sm in re.finditer(r"public static final \w+<\w+>\s+\w+\s*=\s*ColorCollection\.registerBlocks\(\s*BlockItemIds\.(\w+)", blocks_src):
            const = sm.group(1)
            if const not in colored_map:
                continue
            base = colored_map[const]
            tail = blocks_src[sm.end():sm.end() + 400]
            nm = NEW_CLASS.search(tail)
            cls = nm.group(1) if nm else None
            if cls and cls in closure:
                for color in COLOR_NAMES:
                    ids.add(f"{color}_{base}")

        out = out_dir / f"{version}.ids"
        out.write_text("\n".join(sorted(ids)) + "\n", encoding="utf-8")
        print(f"{version}: {len(ids)} ids (helpers: {sorted(helpers)})")


if __name__ == "__main__":
    main()
