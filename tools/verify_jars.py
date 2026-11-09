"""发布前抽查 dist/ 里的 jar：版本号、坏 condition、关键实现是否真的在包里。

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

# jar 里的类已 remap 成 intermediary，不能按 Mojang 名查字符串，
# 只能查我们自己写的、不会被 remap 的 handler/方法名。
REQUIRED_MARKERS = {
    'org/liquidphase/mixin/PhaseSkipRenderingMixin.class': b'liquidphase$dontCullPhasedFaces',
    'org/liquidphase/phase/PhaseRenderSupport.class': b'allowVanillaCulling',
    'org/liquidphase/sync/PhaseSyncState.class': b'serverSupportsPhases',
    'org/liquidphase/sync/PhaseHandshake.class': b'currentBits',
}
# 26.3 按贴图透明度自动推导图层，客户端没有“判断 SOLID 并升级”的代码。
LAYER_MARKER_VERSIONS = {'1.19.4', '1.20.1', '1.21.1', '1.21.10', '1.21.11'}


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


def host_parts(data, phase):
    """按原版 multipart 语义数出某个状态下命中的 part 数（0 = 该状态没有模型）。"""
    def value_matches(raw, actual):
        neg = raw.startswith('!')
        terms = [t.lstrip('!') for t in raw.split('|')]
        ok = str(actual) in terms
        return (not ok) if neg else ok

    def matches(cond, state):
        if not cond:
            return True
        for key, value in cond.items():
            if key in ('AND', 'OR'):
                subs = value if isinstance(value, list) else []
                rs = [matches(s, state) for s in subs]
                if key == 'AND' and not all(rs):
                    return False
                if key == 'OR' and not any(rs):
                    return False
                continue
            actual = state.get(key)
            if actual is None:
                return False
            if not value_matches(str(value), actual):
                return False
        return True

    return sum(1 for part in (data.get('multipart') or [])
               if matches(part.get('when') or {}, {'phase': phase}))


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

        has_replace_mixin = b'liquidphase$wireIsReplaceableForHost' in replace_mixin
        has_phase_write = b'liquidphase$redstonePhase' in block_item
        removed_wire_use = 'org/liquidphase/mixin/RedstoneWireUseMixin.class' not in names
        removed_wire_ctx = 'org/liquidphase/redstone/WirePlaceContext.class' not in names

        # 新增的渲染/同步实现必须在包里。
        markers = dict(REQUIRED_MARKERS)
        if ver in LAYER_MARKER_VERSIONS:
            markers['org/liquidphase/client/LiquidPhaseClient.class'] = b'liquidphase$isSolidLayer'
        missing_markers = []
        for path, marker in markers.items():
            if path not in names:
                missing_markers.append(path)
                continue
            if marker not in z.read(path):
                missing_markers.append(path + ' (marker missing)')

        # 整格宿主 + 红石相：blockstate 里必须真有能命中该状态的 part
        # （0.1.8 的"树叶含红石相变隐形"就是这里为 0）。
        empty_states = []
        for block, phase in [('oak_leaves', 'redstone_15'),
                             ('glass_pane', 'redstone_15'),
                             ('iron_bars', 'redstone_15'),
                             ('oak_stairs', 'redstone_15')]:
            path = 'assets/minecraft/blockstates/%s.json' % block
            if path not in names:
                continue
            try:
                data = json.loads(z.read(path))
            except Exception:
                empty_states.append(block)
                continue
            if host_parts(data, phase) == 0:
                empty_states.append(block)

        line_ok = (bad == 0 and has_replace_mixin and has_phase_write
                   and removed_wire_use and removed_wire_ctx
                   and not missing_markers and not empty_states)
        ok = ok and line_ok
        print('%-8s ver=%-8s blockstates=%-5d bad_conds=%-3d replace_mixin=%s '
              'phase_write=%s render_sync=%s empty_phase_states=%s -> %s'
              % (ver, mod['version'], len(blockstates), bad, has_replace_mixin,
                 has_phase_write, 'ok' if not missing_markers else missing_markers,
                 empty_states or 'none',
                 'OK' if line_ok else 'BROKEN'))

if not jars():
    print('no dist jars found')

sys.exit(0 if ok else 1)
