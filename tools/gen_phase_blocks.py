#!/usr/bin/env python3
"""从 guardian 反编译源码的 Blocks.java 生成各版本"相容方块 ID 清单"。

## 为什么这份清单必须"完整"

运行时（`LiquidPhase.injectPhaseProperty`）给**每一个 `instanceof PhaseCapable` 的方块**
注入 `phase` 属性：`PhaseCapableMixin` 把标记接口挂在一组原版基类上，这些基类的**全部
已注册子类**都会继承到它。而本 mod 的 blockstate（相覆盖层 / 红石外壳）是按本脚本产出的
`<版本>.ids` 逐 id 生成的。

两边一旦不一致，那些"拿到了 `phase` 却不在清单里"的方块就是**含相了但界面上什么都不画**：
- 清单漏了 → 客户端仍用原版 blockstate（没有任何 `phase` 条件）→ 红石相完全不显示；
- 反过来（清单多登记）→ 给没有 `phase` 属性的方块写 `phase` 条件 → 原版解析器报
  `Unknown property 'phase'`，整份 blockstate 加载失败、该方块所有状态变 missing model。

历史教训（0.1.12 修）：
- `BarrierBlock` 在 1.19.4 / 1.20.1 的 `closure.txt` 里漏登记 → `barrier` 含红石相不显示；
- 1.21.10+ 的铜栏杆/铜锁链/铜灯笼用 `WeatheringCopperBlocks.create(...)` 注册，
  旧解析器只认 `public static final Block X = ...` → 24 个铜方块全部漏登记；
- 26.3 改用 `ColorCollection.registerBlocks` / `WeatheringCopperCollection.registerBlocks` /
  `ColorCollection.zipMap` 批量注册（含 id 常量在 `references/BlockItemIds.java`），
  旧解析器只处理了 `ColorCollection.registerBlocks` 一种 → 26.3 漏了 197 个（占约 40%）。

所以本脚本：
1. 逐条解析 `Blocks.java` 的字段初始化语句，覆盖全部注册形式；
2. 展开本地静态工厂方法（`legacyStair` / `leaves` / `candle` / `registerWall` …），
   递归展开它们调用的其它工厂；
3. 解析 id 常量（`BlockItemIds.X` / `BlockIds.X`），含 `createSimpleColored` /
   `createSimpleCopper` 这类批量常量；
4. 按"注册形式 × 颜色/氧化态"展开出真实 id；
5. 只保留类名落在 `<版本>.closure.txt`（PhaseCapableMixin 目标闭包）里的条目；
6. 用 `EXPECTED_COUNTS`（= 各版本运行时日志里
   `injected phase property into N blocks` 的实测值）硬校验，数量不符直接失败。

用法: python gen_phase_blocks.py <guardian-repo-path> <output-dir> <closure-dir>
输出: <output-dir>/<版本>.ids  每行一个方块 id（排序去重）。
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

# 运行时实际注入的方块数（各版本 runServer 日志
# `[Liquid Phase] injected phase property into N blocks`）。这是本脚本的验收口径：
# 清单必须与"真的拿到 phase 属性的方块"一一对应，多一个少一个都会造成显示问题。
# 1.19.4/1.20.1 不含 barrier：这两个版本的 BarrierBlock 没有 WATERLOGGED 属性，
# 注入 PhaseCapable 会在默认方法里抛 IllegalArgumentException（audit §4.2，0.1.13 起
# BarrierBlock 只进 1.21.1+ 的目标清单）。
EXPECTED_COUNTS = {
    "1.19.4": 308,
    "1.20.1": 310,
    "1.21.1": 337,
    "1.21.10": 410,
    "1.21.11": 410,
    "26.3": 505,
}

COLOR_NAMES = [
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
]
# WeatheringCopper* 批量注册的 8 个变体（顺序按原版 WeatheringCopperBlocks.asList）。
WEATHERING_PREFIXES = [
    "", "waxed_", "exposed_", "waxed_exposed_",
    "weathered_", "waxed_weathered_", "oxidized_", "waxed_oxidized_",
]

# `name = create("id")` / `= BlockItemId.create("id")` / `= createSimpleColored("id")` …
CONST_DEF = re.compile(
    r"(\w+)\s*=\s*(?:BlockItemId|ItemId|BlockId)?\.?(?:create\w*)\(\s*\"([a-z0-9_/]+)\"\s*\)")
STATEMENT = re.compile(r"public static final [^;=]+?\s+(\w+)\s*=\s*")
HELPER_DEF = re.compile(
    r"(?:private|public|protected)?\s*static\s+[\w<>\[\], .]+\s+(\w+)\s*\([^)]*\)\s*\{")


def git_show(repo: Path, commit: str, path: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), "show", f"{commit}:{path}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return result.stdout if result.returncode == 0 else ""


def statements(src: str):
    """产出 (字段名, 初始化语句正文)，按括号配平切到分号。"""
    for match in STATEMENT.finditer(src):
        depth = 0
        i = match.end()
        while i < len(src):
            ch = src[i]
            if ch in "({[":
                depth += 1
            elif ch in ")}]":
                depth -= 1
            elif ch == ";" and depth == 0:
                break
            i += 1
        yield match.group(1), src[match.end():i]


def helper_bodies(src: str):
    bodies = {}
    for match in HELPER_DEF.finditer(src):
        brace = match.end() - 1
        depth = 0
        for i in range(brace, len(src)):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    bodies.setdefault(match.group(1), src[brace:i + 1])
                    break
    return bodies


def split_call(text: str):
    """`head(args)` → (head, args)。"""
    paren = text.find("(")
    if paren == -1:
        return text.strip(), ""
    depth = 0
    for i in range(paren, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[:paren].strip(), text[paren + 1:i]
    return text[:paren].strip(), text[paren + 1:]


def expand_helpers(text: str, bodies, depth=0):
    """把文本里引用到的本地静态工厂方法体递归拼进来（只看类名，不做控制流分析）。"""
    if depth > 8:
        return text
    extra = ""
    for name in set(re.findall(r"(\w+)\s*\(", text)):
        body = bodies.get(name)
        if body is not None:
            extra += "\n" + body + expand_helpers(body, bodies, depth + 1)
    return text + extra


def classes_in(text: str):
    return set(re.findall(r"new\s+(\w+)\s*[<(]", text)) | set(re.findall(r"(\w+)::new", text))


def resolve_base(args: str, consts, zipped: bool = False):
    """取注册语句里的 id 字面量 / 常量，返回方块 id 基名。

    ``zipped`` 对应 ``ColorCollection.zipMap(VALUES, BlockItemIds.WOOL_STAIRS, ...)``：
    id 常量在**第二个**参数位，第一个参数是颜色集合，所以不能只看开头。
    """
    pattern = r'(?:"([a-z0-9_]+)"|(?:BlockItemIds|BlockIds)\.(\w+))'
    match = re.search(pattern, args) if zipped else re.match(r'\s*' + pattern, args)
    if not match:
        return None
    if match.group(1):
        return match.group(1)
    return consts.get(match.group(2))


def derive_ids(src: str, closure, consts):
    """返回 {方块 id: {命中的闭包类}}。"""
    bodies = helper_bodies(src)
    found = {}
    unresolved = []
    for field, stmt in statements(src):
        head, args = split_call(stmt)
        head = head.split()[-1] if head.split() else head
        text = expand_helpers(args, bodies)
        if head in bodies:
            text += "\n" + expand_helpers(bodies[head], bodies)
        hit = classes_in(text) & closure
        if not hit:
            continue
        base = resolve_base(args, consts, zipped=head.startswith("ColorCollection.zipMap"))
        if not base:
            unresolved.append((field, head))
            continue
        if head.startswith("ColorCollection"):
            ids = [f"{color}_{base}" for color in COLOR_NAMES]
        elif head.startswith("WeatheringCopper"):
            ids = [prefix + base for prefix in WEATHERING_PREFIXES]
        else:
            ids = [base]
        for block_id in ids:
            found.setdefault(block_id, set()).update(hit)
    return found, unresolved


def main():
    repo = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    closure_dir = Path(sys.argv[3])
    out_dir.mkdir(parents=True, exist_ok=True)

    failed = False
    for version, commit in COMMITS.items():
        closure = set(closure_dir.joinpath(f"{version}.closure.txt").read_text(encoding="utf-8").split())
        blocks_src = git_show(repo, commit, "src/main/java/net/minecraft/world/level/block/Blocks.java")
        if not blocks_src:
            print(f"{version}: cannot read Blocks.java at {commit}", file=sys.stderr)
            failed = True
            continue

        consts = {}
        for path in ("src/main/java/net/minecraft/references/BlockItemIds.java",
                     "src/main/java/net/minecraft/references/BlockIds.java"):
            consts.update(dict(CONST_DEF.findall(git_show(repo, commit, path))))

        found, unresolved = derive_ids(blocks_src, closure, consts)
        expected = EXPECTED_COUNTS.get(version)
        problems = []
        if unresolved:
            problems.append(f"{len(unresolved)} registration statements have no resolvable id: {unresolved[:5]}")
        if expected is not None and len(found) != expected:
            problems.append(f"derived {len(found)} ids, runtime injects {expected}")

        ids = sorted(found)
        out_dir.joinpath(f"{version}.ids").write_text("\n".join(ids) + "\n", encoding="utf-8")
        print(f"{version}: {len(ids)} ids (expected {expected})")
        for problem in problems:
            print(f"  ERROR {version}: {problem}", file=sys.stderr)
            failed = True

        # 每个闭包类至少要命中一个 id：命中 0 个说明闭包清单里多写了一个类名，
        # 或者解析器又漏了一种注册形式。
        hit_classes = set().union(*found.values()) if found else set()
        unused = sorted(closure - hit_classes)
        if unused:
            print(f"  WARNING {version}: closure classes with no registered block: {unused}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
