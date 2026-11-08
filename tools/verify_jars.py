"""发布前抽查 dist/ 里的 jar：版本号、坏 condition、关键 mixin 是否真的在包里。

用法: python tools/verify_jars.py [版本 ...]
默认检查 dist/ 下所有 liquidphase-<ver>+<mc>.jar。
"""
import glob
import json
import os
import re
import sys
import zipfile

VERSIONS = sys.argv[1:]


def jars():
    found = sorted(glob.glob('dist/liquidphase-*.jar'))
    out = []
    for jar in found:
        if 'sources' in os.path.basename(jar):
            continue
        match = re.search(r'\+([^/\\]+)\.jar$', jar)
        if not match:
            continue
        ver = match.group(1)
        if VERSIONS and ver not in VERSIONS:
            continue
        out.append((ver, jar))
    return out


def bad_conditions(data):
    bad = 0
    for part in data.get('multipart') or []:
        def walk(cond):
            nonlocal bad
            if not isinstance(cond, dict):
                return
            for key, value in cond.items():
                if key in ('AND', 'OR'):
                    for sub in (value if isinstance(value, list) else []):
                        walk(sub)
                elif isinstance(value, str) and ('&' in value or '(' in value):
                    bad += 1
        walk(part.get('when') or {})
    return bad


ok = True
for ver, jar in jars():
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        blockstates = [n for n in names
                       if n.startswith('assets/minecraft/blockstates/') and n.endswith('.json')]
        bad = 0
        for name in blockstates:
            try:
                bad += bad_conditions(json.loads(z.read(name)))
            except Exception:
                print('  !! unreadable blockstate', name)
                ok = False
                bad += 1
        mod = json.loads(z.read('fabric.mod.json'))
        block_item = z.read('org/liquidphase/mixin/BlockItemMixin.class')

        # 放置入口必须真的在包里：BlockItemMixin 要引用 WirePlaceContext（落点改写）
        has_wire_ctx = b'WirePlaceContext' in block_item
        # 前两轮的"接管右键 / 全局放宽可替换"必须已经删除
        removed_wire_use = 'org/liquidphase/mixin/RedstoneWireUseMixin.class' not in names
        removed_replace = 'org/liquidphase/mixin/RedstoneWireReplaceMixin.class' not in names
        has_wire_ctx_class = 'org/liquidphase/redstone/WirePlaceContext.class' in names

        line_ok = (bad == 0 and has_wire_ctx and removed_wire_use
                   and removed_replace and has_wire_ctx_class)
        ok = ok and line_ok
        print('%-8s ver=%-8s blockstates=%-5d bad_conds=%-3d wire_ctx=%s '
              'no_wire_use_mixin=%s no_global_replace=%s -> %s'
              % (ver, mod['version'], len(blockstates), bad, has_wire_ctx,
                 removed_wire_use, removed_replace, 'OK' if line_ok else 'BROKEN'))

if not jars():
    print('no dist jars found')

sys.exit(0 if ok else 1)
