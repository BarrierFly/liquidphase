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
        replace_mixin = z.read('org/liquidphase/mixin/RedstoneWireReplaceMixin.class')
        block_item = z.read('org/liquidphase/mixin/BlockItemMixin.class')

        # 放行口子必须真的在包里。注意 jar 里的类已 remap 成 intermediary，
        # 不能按 canBeReplaced / getItemInHand 这类 Mojang 名去查字符串，
        # 只能查我们自己写的、不会被 remap 的 handler 名。
        has_replace_mixin = b'liquidphase$wireIsReplaceableForHost' in replace_mixin
        # 写相位的那一步在 BlockItemMixin
        has_phase_write = b'liquidphase$redstonePhase' in block_item
        # 前两轮"接管红石线右键"的 mixin 必须已经删除
        removed_wire_use = 'org/liquidphase/mixin/RedstoneWireUseMixin.class' not in names
        # 只在 BlockItem.place 入口生效的那套已删除
        removed_wire_ctx = 'org/liquidphase/redstone/WirePlaceContext.class' not in names

        line_ok = (bad == 0 and has_replace_mixin and has_phase_write
                   and removed_wire_use and removed_wire_ctx)
        ok = ok and line_ok
        print('%-8s ver=%-8s blockstates=%-5d bad_conds=%-3d replace_mixin=%s '
              'phase_write=%s no_wire_use_mixin=%s no_entry_only_hack=%s -> %s'
              % (ver, mod['version'], len(blockstates), bad, has_replace_mixin,
                 has_phase_write, removed_wire_use, removed_wire_ctx,
                 'OK' if line_ok else 'BROKEN'))

if not jars():
    print('no dist jars found')

sys.exit(0 if ok else 1)
