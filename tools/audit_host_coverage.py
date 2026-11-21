#!/usr/bin/env python3
"""相容宿主覆盖面审计：历史补丁逐提交 / 当前 HEAD 两用。

## 为什么需要它

本 mod 的"相容宿主"不是一张显式名单，而是**运行时推导**出来的：`PhaseCapableMixin`
把标记接口挂在一组原版基类上，其全部已注册子类都会继承，`LiquidPhase` 再遍历注册表
给 `instanceof PhaseCapable` 的方块注入 `phase`。于是"哪些方块能含相"与
"哪些方块真的有 blockstate / 外壳 / 桶交互 / 整格碰撞"是**四条彼此独立的链路**，
任何一条漏掉一族方块都会表现为"含相了却看不见 / 点不动 / 灌不进"。

本脚本把这些链路各自算一遍并做差集：

    A  原版真值   Blocks.java 注册、且类（或祖先）实现 SimpleWaterloggedBlock 的方块
    B  运行时真值  @Mixin 目标闭包 ∩ 注册表（真的会拿到 phase 属性的方块）
    C  生成清单   tools/phase-blocks/<版本>.ids（决定 blockstate / 相覆盖层）
    S  外壳覆盖   真正生成红石外壳的方块（≤0.1.10 由 tools/redstone-hosts.txt 门控）

另附"能力被宿主覆写遮蔽"的检查（mixin 挂在基类上，子类覆写不转调 super 就失效）：

    1. PhaseCapable 的 canPlaceLiquid / placeLiquid / pickupBlock 被宿主覆写
       （0.1.13 起台阶/蜡烛/营火/干化恶魂由 SlabLiquidMixin / HostPlaceLiquidMixin
       接管，清单里标为"覆写已接管"；1.21.1+ barrier 的覆写按原版创造模式语义有意不接管）
    2. 块级 getCollisionShape 被宿主覆写（固相"整格碰撞"0.1.13 起挂在状态级，
       这条只反映宿主自己的碰撞形状，不再是缺口）
    3. getStateForPlacement 的声明类不在 PlacementMixin 目标里（放置吸收失效）

用法:
    python tools/audit_host_coverage.py <guardian 仓库路径> [liquidphase 仓库路径] [--history]

`--history` 逐个提交重算 A/B/C/S（需要 liquidphase 的 git 历史；较慢，约 1–2 分钟）。
"""
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_phase_blocks as gpb

VERSIONS = ["1.19.4", "1.20.1", "1.21.1", "1.21.10", "1.21.11", "26.3"]

# 0.1.13 起由专门 mixin 接管的"覆写遮蔽"条目（(宿主类, 方法) → 接管 mixin）。
# 这些条目不再是缺口，只作为接管记录打印（防止有人误以为还在漏）。
LIQUID_COVERAGE = {
    ("SlabBlock", "canPlaceLiquid"): "SlabLiquidMixin",
    ("SlabBlock", "placeLiquid"): "SlabLiquidMixin",
    ("CandleBlock", "placeLiquid"): "HostPlaceLiquidMixin",
    ("CampfireBlock", "placeLiquid"): "HostPlaceLiquidMixin",
    ("DriedGhastBlock", "placeLiquid"): "HostPlaceLiquidMixin",
}


# ---------------------------------------------------------------------------
# guardian 源码批量读取（一次 git cat-file --batch，避免数百个 git 进程）
# ---------------------------------------------------------------------------
_cache = {}


def git(guardian, args, stdin=None):
    return subprocess.run(["git", "-C", str(guardian)] + args, input=stdin, capture_output=True)


def read_blobs(guardian, commit, paths):
    if not paths:
        return {}
    spec = "\n".join("%s:%s" % (commit, p) for p in paths) + "\n"
    data = git(guardian, ["cat-file", "--batch"], stdin=spec.encode("utf-8")).stdout
    out, i = {}, 0
    for p in paths:
        nl = data.index(b"\n", i)
        header = data[i:nl].decode("utf-8", "replace")
        i = nl + 1
        parts = header.split(" ")
        if len(parts) < 3 or parts[1] == "missing":
            out[p] = ""
            continue
        size = int(parts[2])
        out[p] = data[i:i + size].decode("utf-8", "replace")
        i += size + 1
    return out


def level_sources(guardian, commit):
    key = ("level", commit)
    if key not in _cache:
        listing = git(guardian, ["ls-tree", "-r", "--name-only", commit,
                                 "src/main/java/net/minecraft/world/level"]).stdout
        paths = [p for p in listing.decode("utf-8", "replace").splitlines() if p.endswith(".java")]
        _cache[key] = read_blobs(guardian, commit, paths)
    return _cache[key]


def show(repo, commit, path):
    return git(repo, ["show", "%s:%s" % (commit, path)]).stdout.decode("utf-8", "replace")


# ---------------------------------------------------------------------------
# Java 类层级
# ---------------------------------------------------------------------------
CLASS_DECL = re.compile(
    r"^(?:public |protected |private )?(?:static )?(?:final )?(?:abstract )?"
    r"(?:sealed )?(class|interface|enum|record)\s+(\w+)"
    r"([^{]*)\{", re.M)


def parse_types(rels):
    out = {}
    for rel, src in rels.items():
        body = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
        body = re.sub(r"//[^\n]*", "", body)
        for m in CLASS_DECL.finditer(body):
            name, rest = m.group(2), m.group(3)
            sup, ifaces = None, []
            mm = re.search(r"extends\s+(.+?)(?=\bimplements\b|$)", rest, re.S)
            if mm:
                sup = re.sub(r"<.*", "", mm.group(1).split(",")[0].strip(), flags=re.S) \
                    .split(".")[-1].strip()
            mm = re.search(r"implements\s+(.+)$", rest, re.S)
            if mm:
                for part in mm.group(1).split(","):
                    part = re.sub(r"<.*", "", part).strip().split(".")[-1].strip()
                    if part:
                        ifaces.append(part)
            out.setdefault(name, {"file": rel, "extends": sup, "implements": ifaces})
    return out


def ancestors(name, types):
    seen, cur = [], name
    while cur and cur not in seen:
        seen.append(cur)
        info = types.get(cur)
        if not info:
            break
        cur = info["extends"]
    return seen


def implements(name, types, iface, depth=0):
    info = types.get(name)
    if not info or depth > 12:
        return False
    if iface in info["implements"]:
        return True
    for i in info["implements"]:
        if i == iface or implements(i, types, iface, depth + 1):
            return True
    if info["extends"] and info["extends"] != "Object":
        return implements(info["extends"], types, iface, depth + 1)
    return False


# ---------------------------------------------------------------------------
# Blocks.java → {id: {构造用到的类}}
# ---------------------------------------------------------------------------
def registrations(blocks_src, consts):
    bodies = gpb.helper_bodies(blocks_src)
    reg = {}
    for field, stmt in gpb.statements(blocks_src):
        head, args = gpb.split_call(stmt)
        head = head.split()[-1] if head.split() else head
        text = gpb.expand_helpers(args, bodies)
        if head in bodies:
            text += "\n" + gpb.expand_helpers(bodies[head], bodies)
        classes = set(re.findall(r"new\s+(\w+)\s*[<(]", text)) | set(re.findall(r"(\w+)::new", text))
        if not classes:
            continue
        base = gpb.resolve_base(args, consts, zipped=head.startswith("ColorCollection.zipMap"))
        if not base:
            continue
        if head.startswith("ColorCollection"):
            ids = ["%s_%s" % (c, base) for c in gpb.COLOR_NAMES]
        elif head.startswith("WeatheringCopper"):
            ids = [p + base for p in gpb.WEATHERING_PREFIXES]
        else:
            ids = [base]
        for i in ids:
            reg.setdefault(i, set()).update(classes)
    return reg


def reference_consts(repo, commit):
    consts = {}
    for p in ("src/main/java/net/minecraft/references/BlockItemIds.java",
              "src/main/java/net/minecraft/references/BlockIds.java"):
        consts.update(dict(gpb.CONST_DEF.findall(show(repo, commit, p))))
    return consts


# ---------------------------------------------------------------------------
# stonecutter 预处理（只给本 mod 源码用）
# ---------------------------------------------------------------------------
_TOK = re.compile(r"!|\(|\)|\|\||&&|[\w.\-]+")
_IF = re.compile(r"//\?\s*if\s+(.+?)\s*\{\s*$")
_ELIF = re.compile(r"//\?\s*\}\s*elif\s+(.+?)\s*\{\s*$")
_ELSE = re.compile(r"//\?\s*\}\s*else\s*\{\s*$")
_END = re.compile(r"//\?\s*\}\s*$")


def eval_cond(expr, version):
    toks, pos = _TOK.findall(expr), [0]

    def peek():
        return toks[pos[0]] if pos[0] < len(toks) else None

    def take():
        pos[0] += 1
        return toks[pos[0] - 1]

    def atom():
        if peek() == "(":
            take()
            v = parse_or()
            if peek() == ")":
                take()
            return v
        return take() == version

    def unary():
        if peek() == "!":
            take()
            return not unary()
        return atom()

    def parse_and():
        v = unary()
        while peek() == "&&":
            take()
            v = unary() and v
        return v

    def parse_or():
        v = parse_and()
        while peek() == "||":
            take()
            v = parse_and() or v
        return v

    try:
        return parse_or()
    except Exception:  # noqa: BLE001
        return False


def stonecutter_variant(src, version):
    out, stack, active = [], [], True
    for line in src.splitlines(keepends=True):
        s = line.rstrip()
        m = _ELIF.search(s)
        if m:
            if stack:
                taken, outer = stack[-1]
                active = outer and eval_cond(m.group(1), version) and not any(
                    eval_cond(c, version) for c in taken)
                taken.append(m.group(1))
            continue
        m = _ELSE.search(s)
        if m:
            if stack:
                taken, outer = stack[-1]
                active = outer and not any(eval_cond(c, version) for c in taken)
            continue
        m = _IF.search(s)
        if m:
            stack.append(([m.group(1)], active))
            active = active and eval_cond(m.group(1), version)
            continue
        if _END.search(s):
            if stack:
                active = stack.pop()[1]
            continue
        if active:
            out.append(line)
    return re.sub(r"/\*.*?\*/", "", "".join(out), flags=re.S)


def mixin_targets(src, version):
    text = stonecutter_variant(src, version)
    m = re.search(r"@Mixin\(\s*\{(.*?)\}\s*\)", text, re.S)
    if m:
        return set(re.findall(r"(\w+)\.class", re.sub(r"//[^\n]*", "", m.group(1))))
    m = re.search(r"@Mixin\(\s*([\w.]+)\.class\s*\)", text)
    return {m.group(1).split(".")[-1]} if m else set()


# ---------------------------------------------------------------------------
# 方法声明 / 是否转调 super
# ---------------------------------------------------------------------------
DECL = re.compile(
    r"^[ \t]*(?:public|protected|private)\s+"
    r"(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:default\s+)?"
    r"[\w<>\[\], .?]+?\s+(\w+)\s*\(", re.M)


def method_bodies(src):
    src = re.sub(r"@\w+(?:\([^)]*\))?", "", src)
    out = {}
    for m in DECL.finditer(src):
        brace = src.find("{", m.end())
        semi = src.find(";", m.end())
        if brace == -1 or (semi != -1 and semi < brace):
            continue
        depth, i = 0, brace
        while i < len(src):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        out.setdefault(m.group(1), []).append(src[brace:i + 1])
    return out


def nearest_override(cls, method, types, rels, cache):
    for name in ancestors(cls, types):
        info = types.get(name)
        if not info:
            continue
        if name not in cache:
            cache[name] = method_bodies(rels.get(info["file"], ""))
        bodies = cache[name].get(method)
        if bodies:
            return name, bodies
    return None, []


# ---------------------------------------------------------------------------
# 单版本审计
# ---------------------------------------------------------------------------
def hosts_of(commit, guardian):
    rels = level_sources(guardian, commit)
    types = parse_types(rels)
    reg = registrations(show(guardian, commit,
                             "src/main/java/net/minecraft/world/level/block/Blocks.java"),
                        reference_consts(guardian, commit))
    hosts = {}
    for bid, classes in reg.items():
        hit = [c for c in classes if c in types]
        if hit and any(implements(c, types, "SimpleWaterloggedBlock") for c in hit):
            hosts[bid] = hit
    return types, rels, reg, hosts


def audit(guardian, version, commit, mixin_src, placement_src, ids, shell):
    types, rels, reg, hosts = hosts_of(commit, guardian)
    targets = mixin_targets(mixin_src, version)
    ptargets = mixin_targets(placement_src, version)

    # 0.2.0：传送门三件套不实现 SimpleWaterloggedBlock，由独立 mixin 注入 PhaseCapable
    # （它们没有 WATERLOGGED，只走基接口的熔岩分支）。审计集合里显式补上。
    portal_hosts = {"nether_portal", "end_portal", "end_gateway"}

    runtime = {bid for bid, classes in reg.items()
               if any(set(ancestors(c, types)) & targets for c in classes if c in types)}
    runtime |= portal_hosts

    cache = {}
    shadow, covered, collision, placement = {}, {}, {}, {}
    for bid, classes in hosts.items():
        cls = classes[0]
        hits = []
        for method in ("canPlaceLiquid", "placeLiquid", "pickupBlock"):
            owner, _ = nearest_override(cls, method, types, rels, cache)
            if owner:
                mixin = LIQUID_COVERAGE.get((owner, method))
                if mixin:
                    covered.setdefault(mixin, set()).add(bid)
                else:
                    hits.append((method, owner))
        if hits:
            shadow.setdefault(tuple(sorted(hits)), []).append(bid)

        owner, _ = nearest_override(cls, "getCollisionShape", types, rels, cache)
        if owner and owner not in ("Block", "BlockBehaviour"):
            collision.setdefault(owner, []).append(bid)

        owner, _ = nearest_override(cls, "getStateForPlacement", types, rels, cache)
        if owner and owner not in ptargets:
            placement.setdefault(owner, []).append(bid)

    return {
        "A": set(hosts) | portal_hosts, "B": runtime, "C": set(ids), "S": set(shell),
        "a_b": sorted((set(hosts) | portal_hosts) - runtime),
        "b_a": sorted(runtime - (set(hosts) | portal_hosts)),
        "b_c": sorted(runtime - set(ids)),
        "c_b": sorted(set(ids) - runtime),
        "c_s": sorted(set(ids) - set(shell)),
        "shadow": shadow, "covered": covered, "collision": collision, "placement": placement,
    }


def read_ids(repo, commit, version):
    raw = show(repo, commit, "tools/phase-blocks/%s.ids" % version)
    return set(raw.split()) if raw.strip() else None


def read_shell(repo, commit, ids):
    gen = show(repo, commit, "tools/gen_phase_blockstates.py")
    if "if redstone_host:" in gen:           # 0.1.5–0.1.10：按 redstone-hosts.txt 门控
        hosts = show(repo, commit, "tools/redstone-hosts.txt")
        return (set(hosts.split()) & ids) if hosts.strip() else ids
    return ids


def print_detail(version, r):
    print("== %s" % version)
    print("   A 原版可含水 %3d | B 注入 phase %3d | C 生成清单 %3d | S 红石外壳 %3d"
          % (len(r["A"]), len(r["B"]), len(r["C"]), len(r["S"])))
    for key, label in (("a_b", "A-B 应能含相、拿不到 phase"), ("b_a", "B-A 原版不可含水却被注入"),
                       ("b_c", "B-C 有 phase、无 blockstate"), ("c_b", "C-B 有 blockstate、无 phase"),
                       ("c_s", "C-S 有清单、无红石外壳")):
        if r[key]:
            print("   %s (%d): %s" % (label, len(r[key]), r[key][:20]))
    for pattern, ids in sorted(r["shadow"].items(), key=lambda kv: -len(kv[1])):
        print("   覆写遮蔽 %-46s %4d hosts e.g. %s"
              % (",".join("%s@%s" % (m, c) for m, c in pattern), len(ids),
                 ", ".join(sorted(ids)[:4])))
    for mixin, ids in sorted(r["covered"].items()):
        print("   覆写已接管 %-45s %4d hosts（%s）" % (mixin + ":", len(ids), ", ".join(sorted(ids)[:4])))
    for cls, ids in sorted(r["collision"].items(), key=lambda kv: -len(kv[1])):
        # 0.1.13 起固相"整格碰撞"挂在状态级 ShapeFallbackMixin（两参+三参 RETURN），
        # 块级覆写不再影响碰撞；这条只反映"宿主自己的碰撞形状"这一结构事实。
        print("   块级碰撞被覆写 %-26s %4d hosts e.g. %s"
              % (cls, len(ids), ", ".join(sorted(ids)[:4])))
    for cls, ids in sorted(r["placement"].items(), key=lambda kv: -len(kv[1])):
        print("   放置吸收未覆盖 %-28s %4d hosts e.g. %s"
              % (cls, len(ids), ", ".join(sorted(ids)[:4])))


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    guardian = Path(argv[1])
    repo = Path(argv[2]) if len(argv) > 2 and not argv[2].startswith("--") else Path(".")
    history = "--history" in argv

    commits = subprocess.run(["git", "-C", str(repo), "rev-list", "--reverse", "HEAD"],
                             capture_output=True, text=True).stdout.split()
    if not history:
        commits = ["HEAD"]

    for commit in commits:
        gp = show(repo, commit, "gradle.properties")
        m = re.search(r"mod_version=([\w.\-]+)", gp)
        print("\n######## %s  %s" % (commit[:8], m.group(1) if m else "?"))
        mixin_src = show(repo, commit, "src/main/java/org/liquidphase/mixin/PhaseCapableMixin.java")
        placement_src = show(repo, commit, "src/main/java/org/liquidphase/mixin/PlacementMixin.java")
        for version in VERSIONS:
            ids = read_ids(repo, commit, version)
            if ids is None:
                continue
            result = audit(guardian, version, gpb.COMMITS[version], mixin_src, placement_src,
                           ids, read_shell(repo, commit, ids))
            if history:
                print("  %-8s A=%3d B=%3d C=%3d S=%3d | A-B=%3d B-A=%3d B-C=%3d C-B=%3d C-S=%3d"
                      % (version, len(result["A"]), len(result["B"]), len(result["C"]),
                         len(result["S"]), len(result["a_b"]), len(result["b_a"]),
                         len(result["b_c"]), len(result["c_b"]), len(result["c_s"])))
                for key, note in (("b_c", "有属性无 blockstate"), ("c_b", "有 blockstate 无属性"),
                                  ("c_s", "无红石外壳"), ("a_b", "应能含相却无属性")):
                    if result[key]:
                        print("      %s (%d): %s" % (note, len(result[key]), result[key][:12]))
            else:
                print_detail(version, result)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
