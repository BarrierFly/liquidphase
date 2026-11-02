#!/usr/bin/env python3
"""生成红石相组合方块（§3.4 R1）的类与实例工厂。

Wire<Host> 类：构造器委托宿主、追加 POWER、信号委托 RedstoneBlocks。
CompositeFactories：实例级注册表——每个宿主方块的构造参数原文取自 guardian 的
Blocks.java（保持原版语义），仅把 Properties 参数替换为对宿主方块的属性拷贝，
并把 Blocks 常量限定为 Blocks.X。类不存在于某版本时用 //? if 守卫。

用法: python gen_redstone_composites.py <guardian-repo> <closure-dir> <ids-dir> <out-dir>
"""
import re
import subprocess
import sys
from pathlib import Path

VERSIONS = ["1.19.4", "1.20.1", "1.21.1", "1.21.10", "1.21.11", "26.3"]
COMMITS = {
    "1.19.4": "37641fc907",
    "1.20.1": "41c32f0060",
    "1.21.1": "d47305d7cf",
    "1.21.10": "5d7d486906",
    "1.21.11": "192e7132b4",
    "26.3": "d5822ca2a1",
}


def git_show(repo: Path, commit: str, path: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), "show", f"{commit}:{path}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
    ).stdout


def balanced(src: str, start: int) -> str:
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return src[start:start + 400]


def split_top(raw: str):
    parts, depth, cur = [], 0, []
    for ch in raw:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        parts.append(tail)
    return [p for p in parts if p]


def ctor_params(cls_src: str, name: str):
    m = re.search(r"(?:public|protected)\s+%s\s*\(" % re.escape(name), cls_src)
    if not m:
        return None
    start = m.end() - 1
    raw = balanced(cls_src, start)[1:-1]
    params = []
    for p in split_top(raw):
        parts = p.split()
        if len(parts) < 2:
            return None
        params.append(" ".join(parts[:-1]))
    return params


def extract_new_args(expr: str):
    m = re.search(r"new\s+(\w+)\s*\(", expr)
    if not m:
        return None, None
    args = balanced(expr, m.end() - 1)[1:-1]
    return m.group(1), args


def qualify(args: str, const_names: set) -> str:
    def repl_props(m):
        inner = m.group(1).strip()
        return f"properties(Blocks.{inner}, id)" if inner in const_names else m.group(0)
    out = re.sub(r"(?:BlockBehaviour\.)?Properties\.(?:ofFullCopy|copyLegacy|copy)\(\s*([A-Za-z_]\w*)\s*\)", repl_props, args)
    def repl_const(m):
        name = m.group(0)
        return f"Blocks.{name}" if name in const_names else name
    out = re.sub(r"\b[A-Z][A-Z0-9_]{2,}\b", repl_const, out)
    out = out.replace("Blocks.Blocks.", "Blocks.")
    out = re.sub(r"(\w+)\.Blocks\.(\w+)", lambda m: m.group(1) + "." + m.group(2), out)
    return out


CLASS_TEMPLATE = """{guard}
// 由 tools/gen_redstone_composites.py 生成（对照 guardian {commit}）——不要手改，重跑生成器。
package org.liquidphase.redstone.composites;

{guard_open}import java.util.function.Supplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
public class Wire{host} extends {host} implements org.liquidphase.redstone.RedstonePhaseBlock {{

    public Wire{host}({params}) {{
        super({arg_names});
    }}

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {{
        super.createBlockStateDefinition(builder);
        builder.add(org.liquidphase.redstone.RedstoneBlocks.POWER);
    }}

    @Override
    public boolean isSignalSource(net.minecraft.world.level.block.state.BlockState state) {{
        return true;
    }}

    @Override
    public int getSignal(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {{
        return org.liquidphase.redstone.RedstoneBlocks.getSignal(state, level, pos, direction);
    }}

    @Override
    public int getDirectSignal(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {{
        return org.liquidphase.redstone.RedstoneBlocks.getDirectSignal(state, level, pos, direction);
    }}
}}
"""


def main():
    repo = Path(sys.argv[1])
    closure_dir = Path(sys.argv[2])
    ids_dir = Path(sys.argv[3])
    out_dir = Path(sys.argv[4])
    out_dir.mkdir(parents=True, exist_ok=True)

    closures = {v: set(closure_dir.joinpath(f"{v}.closure.txt").read_text().split()) for v in VERSIONS}
    union = sorted(set().union(*closures.values()))

    # 1) Wire<Host> 类
    generated = 0
    host_params = {}
    for host in union:
        available = [v for v in VERSIONS if host in closures[v]]
        if not available:
            continue
        params = None
        used_commit = None
        order = [v for v in ["1.21.11", "1.21.10", "1.21.1", "1.20.1", "1.19.4", "26.3"] if v in available]
        for v in order:
            try:
                cls_src = git_show(repo, COMMITS[v], f"src/main/java/net/minecraft/world/level/block/{host}.java")
                params = ctor_params(cls_src, host)
                if params:
                    used_commit = COMMITS[v]
                    break
            except subprocess.CalledProcessError:
                continue
        if not params:
            print(f"skip {host}: no ctor")
            continue
        # 只保留构造器跨六个版本稳定的宿主：仅 Properties，或 StairBlock(BlockState, Properties)。
        SAFE = ("BlockState", "BlockBehaviour.Properties")
        if any(not (t.strip() + " ").startswith(SAFE) for t in params):
            print(f"skip {host}: unsafe ctor types {params}")
            continue
        if cls_src and __import__("re").search(r"abstract\s+class\s+%s" % host, cls_src):
            print(f"skip {host}: abstract base")
            continue
        host_params[host] = params
        arg_names = ", ".join(f"p{i}" for i in range(len(params)))
        param_decls = ", ".join(f"{t} p{i}" for i, t in enumerate(params))
        guard_open = ""
        guard = ""
        if len(available) < len(VERSIONS):
            guard = f"// 仅存在于：{', '.join(available)}\n"
            guard_open = "//? if %s {\n" % " || ".join(available)
            guard_open = guard_open + (chr(47) + chr(42) + chr(10) if "1.21.11" not in available else "")
        out = out_dir / f"Wire{host}.java"
        needed = set(re.findall(r"([A-Z]\w+)", param_decls))
        host_imports = chr(10).join(
            line for line in cls_src.splitlines()
            if line.startswith("import ")
            and line.rsplit(".", 1)[-1].rstrip(";") in needed
            and not re.match(r"import net\.minecraft\.world\.level\.block\.\w+;$", line))
        body = CLASS_TEMPLATE.format(
            guard=guard, guard_open=guard_open, host=host, params=param_decls,
            arg_names=arg_names, commit=used_commit or "", host_imports=host_imports)
        if len(available) < len(VERSIONS):
            if "1.21.11" not in available:
                body = body.rstrip() + chr(10) + " *///?}" + chr(10)
            else:
                body = body.rstrip() + chr(10) + "//?}" + chr(10)
        out.write_text(body, encoding="utf-8")
        generated += 1
    print(f"generated {generated} composite classes")

    # 2) 实例工厂
    def helper_map(blocks_src):
        helpers = {}
        for hm in re.finditer(r"static\s+\w+\s+(\w+)\s*\(", blocks_src):
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
            body = blocks_src[brace:end]
            cls_name, args = extract_new_args(body)
            if cls_name and args is not None:
                sig = re.search(r"\(\s*([^)]*)\)", blocks_src[hm.end()-1:])
                pnames = [p.split()[-1] for p in split_top(sig.group(1))] if sig else []
                helpers[name] = (cls_name, pnames, args)
        return helpers

    def substitute(args, pnames, call_args):
        # 把 helper 体的 new 参数里的形参替换为调用点实参（词边界替换）
        for pname, carg in zip(pnames, call_args):
            if pname:
                args = re.sub(r"%s" % re.escape(pname), carg, args)
        return args

    const_names_all = set()
    entries = []
    const_versions = {}
    for v in VERSIONS:
        commit = COMMITS[v]
        blocks_src = git_show(repo, commit, "src/main/java/net/minecraft/world/level/block/Blocks.java")
        const_names = set(re.findall(r"public static final Block\s+(\w+)\s*=", blocks_src))
        for _c in const_names:
            const_versions.setdefault(_c, set()).add(v)
        const_names_all |= const_names
        ids = set(ids_dir.joinpath(f"{v}.ids").read_text().split())
        helpers = helper_map(blocks_src)
        for sm in re.finditer(r"public static final Block\s+(\w+)\s*=\s*(\w+)\s*\(", blocks_src):
            const, call_name = sm.group(1), sm.group(2)
            expr = balanced(blocks_src, blocks_src.index("(", sm.end() - 1))
            sstr = re.search(r'"([a-z0-9_/\.]+)"', expr)
            if not sstr:
                continue
            sid = sstr.group(1)
            if sid not in ids:
                continue
            call_args = split_top(expr[1:-1])
            cls_name = args = None
            if call_name in helpers:
                cls_name, pnames, tmpl = helpers[call_name]
                args = substitute(tmpl, pnames, call_args)
            else:
                # 宿主构造可能藏在 helper 调用参数里（如 register("id", legacyStair(BASE))）
                for idx, carg in enumerate(call_args):
                    hm2 = re.match(r"(\w+)\s*\(", carg)
                    if hm2 and hm2.group(1) in helpers:
                        hcls, hpnames, htmpl = helpers[hm2.group(1)]
                        hcall_args = split_top(carg[carg.index("(") + 1:-1])
                        cls_name = hcls
                        args = substitute(htmpl, hpnames, hcall_args)
                        call_args[idx] = "__HELPER__"
                        break
                if cls_name is None:
                    cls_name, args = extract_new_args(expr)
            if not cls_name or cls_name not in host_params or args is None:
                continue
            # 直通 helper：stair/slab/wall 家族用模板构造（参数 = 基础方块 + 宿主属性拷贝）
            DIRECT = {
                "legacyStair": ("StairBlock", 1), "registerLegacyStair": ("StairBlock", 1),
                "registerStair": ("StairBlock", 2), "stair": ("StairBlock", 1),
                "registerSlab": ("SlabBlock", 1), "slab": ("SlabBlock", 1),
                "registerWall": ("WallBlock", 1), "wall": ("WallBlock", 1),
            }
            helper_used = call_name if call_name in DIRECT else None
            if helper_used is None:
                for carg in call_args:
                    hm2 = re.match(r"(\w+)\s*\(", carg)
                    if hm2 and hm2.group(1) in DIRECT:
                        helper_used = hm2.group(1)
                        break
            if helper_used and "Wire" + DIRECT[helper_used][0] in map(lambda n: f"Wire{n}", host_params):
                target_cls = DIRECT[helper_used][0]
                # 基础方块 = 该 helper 调用里第一个大写常量实参
                base = None
                for tok in re.findall(r"[A-Z][A-Z0-9_]{2,}", expr):
                    if tok in const_names:
                        base = tok
                        break
                if target_cls == "StairBlock" and base:
                    if "WireStairBlock" in host_params:
                        line = f'map.put(Blocks.{const}, (id) -> new WireStairBlock(Blocks.{base}.defaultBlockState(), properties(Blocks.{const}, id)));'
                        entries.append((v, sid, line))
                        continue
                elif target_cls == "SlabBlock" and "WireSlabBlock" in host_params:
                    line = f'map.put(Blocks.{const}, (id) -> new WireSlabBlock(properties(Blocks.{const}, id)));'
                    entries.append((v, sid, line))
                    continue
                elif target_cls == "WallBlock" and "WireWallBlock" in host_params:
                    line = f'map.put(Blocks.{const}, (id) -> new WireWallBlock(properties(Blocks.{const}, id)));'
                    entries.append((v, sid, line))
                    continue
            args_list = split_top(qualify(args, const_names))
            if len(args_list) != len(host_params[cls_name]):
                continue
            if "Properties" not in args_list[-1]:
                continue
            args_list[-1] = f"properties(Blocks.{const}, id)"
            line = f'map.put(Blocks.{const}, (id) -> new Wire{cls_name}({", ".join(args_list)}));'
            entries.append((v, sid, line))

    # 按条目聚合版本守卫（同一 java 行出现于多版本 → 守卫取交集）
    # 校验：丢弃含未解析标识符的条目（helper 形参残留会编译失败）
    bad = re.compile(r"[^\.](block|properties|string|bl|bl2|i|f|g|var\d+|p\d+)")
    clean = []
    for v, sid, line in entries:
        if bad.search(line):
            print(f"drop {v} {sid}: unresolved identifier")
            continue
        clean.append((v, sid, line))
    entries = clean

    # 后处理：stairs 的 base 参数回填为宿主自身默认状态；仍有残留标识符的条目丢弃
    fixed = []
    for v, sid, line in entries:
        m2 = re.search(r"new (WireStairBlock)\((\w+)\.defaultBlockState\(\), properties\(Blocks\.(\w+)\)\)", line)
        if m2 and m2.group(2) not in const_names_all:
            line = line.replace(
                f"new WireStairBlock({m2.group(2)}.defaultBlockState(), properties(Blocks.{m2.group(3)}))",
                f"new WireStairBlock(Blocks.{m2.group(3)}.defaultBlockState(), properties(Blocks.{m2.group(3)}))")
        if re.search(r"[^\w.](block|properties|string|bl|bl2|var\d+|p\d+)\s*\.", line) or "(block." in line or "(properties" in line:
            continue
        fixed.append((v, sid, line))
    entries = fixed

    # 守卫按"组合方块类存在的版本集合"定，而不是"这行文本出现过的版本"：
    # 同一宿主在不同版本的构造文本可能不同，按文本聚合会把 1.21.11 的条目错误剔除。
    class_versions = {}
    for host in host_params:
        class_versions["Wire" + host] = [v for v in VERSIONS if host in closures[v]]

    by_line = {}
    for v, sid, line in entries:
        by_line.setdefault(line, []).append(v)
    body_lines = []
    for line, vs in by_line.items():
        cls_hit = re.search(r"new (Wire\w+)\(", line)
        if cls_hit and cls_hit.group(1) in class_versions and class_versions[cls_hit.group(1)]:
            vs = class_versions[cls_hit.group(1)]
        avail = sorted(set(vs), key=VERSIONS.index)
        # 只发射"六版本都存在"的条目：类与其中引用的 Blocks 常量都要在六版本存在，
        # 否则会出现"1.21.11 才有的 TUFF_STAIRS"这类跨版本编译失败。
        consts = re.findall(r"Blocks\.(\w+)", line)
        universal = (len(avail) == len(VERSIONS)
                     and all(len(const_versions.get(c, set())) == len(VERSIONS) for c in consts))
        if universal:
            body_lines.append("            " + line)
    body = "\n        ".join(body_lines) or "            // 无条目"

    factory = """// 由 tools/gen_redstone_composites.py 生成——不要手改，重跑生成器。
package org.liquidphase.redstone.composites;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.block.WeatheringCopper;

/**
 * 宿主方块实例 → 红石相组合方块（§3.4 R1）。构造参数原文取自 vanilla Blocks.java，
 * Properties 参数统一替换为 properties(host)（= 原版属性拷贝，战利品表随宿主）。
 */
public final class CompositeFactories {

    private CompositeFactories() {
    }

    private static BlockBehaviour.Properties properties(Block host, net.minecraft.resources.ResourceKey<Block> id) {
        //? if 1.19.4 || 1.20.1 {
        /*return BlockBehaviour.Properties.copy(host);
         *///?} else if 1.21.1 || 1.21.10 {
        /*return BlockBehaviour.Properties.ofFullCopy(host);
         *///?} else {
        // 1.21.11+/26.3：Properties 必须带注册键，否则构造时 effectiveDrops 抛 "Block id not set"
        return BlockBehaviour.Properties.ofFullCopy(host).setId(id);
        //?}
    }

    private static final Function<Block, Function<net.minecraft.resources.ResourceKey<Block>, Block>> REGISTRY = buildRegistry();

    private static Function<Block, Function<net.minecraft.resources.ResourceKey<Block>, Block>> buildRegistry() {
        Map<Block, Function<net.minecraft.resources.ResourceKey<Block>, Block>> map = new HashMap<>();
        REGISTRY_BODY
        return map::get;
    }

    /** {@return 组合方块实例；host 不在表内时返回 null} */
    public static Block create(Block host, net.minecraft.resources.ResourceKey<Block> id) {
        Function<net.minecraft.resources.ResourceKey<Block>, Block> factory = REGISTRY.apply(host);
        return factory == null ? null : factory.apply(id);
    }
}
""".replace("REGISTRY_BODY", body)
    (out_dir / "CompositeFactories.java").write_text(factory, encoding="utf-8")
    print(f"emitted CompositeFactories.java with {len(by_line)} entries")

if __name__ == "__main__":
    main()
