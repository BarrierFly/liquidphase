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
    'org/liquidphase/mixin/PhaseRenderShapeMixin.class': b'liquidphase$phasedRenderShape',
    'org/liquidphase/phase/PhaseRenderSupport.class': b'allowVanillaCulling',
    'org/liquidphase/sync/PhaseSyncState.class': b'serverSupportsPhases',
    'org/liquidphase/sync/PhaseHandshake.class': b'currentBits',
}
# 26.3 按贴图透明度自动推导图层，没有 render-layer mixin。
LAYER_MARKER_VERSIONS = {'1.19.4', '1.20.1', '1.21.1', '1.21.10', '1.21.11'}

# 内缩相覆盖层 / 外扩红石外壳的模型都不能带 cullface（见 AGENTS §3.2）。
NO_CULLFACE_MODELS = [
    'phase_obsidian', 'phase_ice',
    'phase_frosted_0', 'phase_frosted_1', 'phase_frosted_2', 'phase_frosted_3',
] + ['redstone_phase_%d' % i for i in range(16)]

# 外壳 16 个功率档位必须是 16 份不同贴图（0 档熄灭、15 档满功率）。
SHELL_POWERS = range(16)


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
        raw = str(value)
        neg = raw.startswith('!')
        terms = [t.lstrip('!') for t in raw.split('|')]
        ok = str(actual) in terms
        if neg:
            ok = not ok
        if not ok:
            return False
    return True


def host_parts(data, phase):
    """按原版 multipart 语义数出某个相下命中的 part 数（0 = 该状态没有模型）。"""
    return sum(1 for part in (data.get('multipart') or [])
               if matches(part.get('when') or {}, {'phase': phase}))


def always_true_conditions(data):
    """原版 `KeyValueCondition` 对"覆盖属性全部取值"的条件会打 always-true 警告。

    我们生成的 `phase` 条件里，一个 `|` 分隔值若包含全部 24 个相取值就算恒真。
    每个方块状态一条警告，1.21.10+ 会把加载日志刷爆。
    """
    all_values = {'none', 'lava', 'obsidian', 'ice',
                  'frosted_0', 'frosted_1', 'frosted_2', 'frosted_3'} \
                 | {'redstone_%d' % i for i in range(16)}
    hits = 0

    def walk(cond):
        nonlocal hits
        if not isinstance(cond, dict):
            return
        for key, value in cond.items():
            if key in ('AND', 'OR'):
                for sub in (value if isinstance(value, list) else []):
                    walk(sub)
                continue
            if key == 'phase' and isinstance(value, str):
                terms = {t.lstrip('!') for t in value.split('|')}
                if all_values <= terms:
                    hits += 1

    for part in (data.get('multipart') or []):
        walk(part.get('when') or {})
    return hits


ok = True
for ver, jar in jars():
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        blockstates = [n for n in names
                       if n.startswith('assets/minecraft/blockstates/') and n.endswith('.json')]
        bad = 0
        always_true = 0
        for name in blockstates:
            try:
                data = json.loads(z.read(name))
            except Exception:
                print('  !! unreadable blockstate', name)
                ok = False
                bad += 1
                continue
            bad += bad_conditions(data)
            always_true += always_true_conditions(data)

        replace_mixin = z.read('org/liquidphase/mixin/RedstoneWireReplaceMixin.class')
        block_item = z.read('org/liquidphase/mixin/BlockItemMixin.class')

        has_replace_mixin = b'liquidphase$wireIsReplaceableForHost' in replace_mixin
        has_phase_write = b'liquidphase$redstonePhase' in block_item
        removed_wire_use = 'org/liquidphase/mixin/RedstoneWireUseMixin.class' not in names
        removed_wire_ctx = 'org/liquidphase/redstone/WirePlaceContext.class' not in names

        # 新增的渲染/同步实现必须在包里。
        markers = dict(REQUIRED_MARKERS)
        if ver in LAYER_MARKER_VERSIONS:
            markers['org/liquidphase/mixin/client/PhaseRenderLayerMixin.class'] = b'liquidphase$phasedLayer'
        missing_markers = []
        for path, marker in markers.items():
            if path not in names:
                missing_markers.append(path)
                continue
            if marker not in z.read(path):
                missing_markers.append(path + ' (marker missing)')

        # 内缩覆盖层/外扩外壳不能带 cullface（0.1.10 实机 bug 的根因）。
        cullface_models = []
        for model in NO_CULLFACE_MODELS:
            path = 'assets/liquidphase/models/block/%s.json' % model
            if path not in names:
                cullface_models.append(model + ' (missing)')
                continue
            if b'"cullface"' in z.read(path):
                cullface_models.append(model)

        # 外壳 16 个功率档位：模型与贴图都必须齐全且互不相同。
        shell_gaps = [p for p in SHELL_POWERS
                      if 'assets/liquidphase/models/block/redstone_phase_%d.json' % p not in names
                      or 'assets/liquidphase/textures/block/redstone_phase_%d.png' % p not in names]
        shell_textures = {z.read('assets/liquidphase/textures/block/redstone_phase_%d.png' % p)
                          for p in SHELL_POWERS
                          if 'assets/liquidphase/textures/block/redstone_phase_%d.png' % p in names}
        if len(shell_textures) != len(list(SHELL_POWERS)):
            shell_gaps.append('power textures not all distinct')

        # 渲染层 mixin 配置必须在 fabric.mod.json 里接线（仅 1.19.4–1.21.11）。
        mod = json.loads(z.read('fabric.mod.json'))
        layer_wired = 'liquidphase.layer.mixins.json' in mod.get('mixins', [])
        if ver in LAYER_MARKER_VERSIONS and not layer_wired:
            missing_markers.append('layer mixin config not wired')
        if ver not in LAYER_MARKER_VERSIONS and layer_wired:
            missing_markers.append('layer mixin config wired on 26.3')

        # 整格宿主 + 红石相：blockstate 里必须真有能命中该状态的 part
        # （0.1.8 的"树叶含红石相变隐形"就是这里为 0）。
        empty_states = []
        for block, phase in [('oak_leaves', 'redstone_15'),
                             ('glass_pane', 'redstone_15'),
                             ('iron_bars', 'redstone_15'),
                             ('oak_stairs', 'redstone_15'),
                             ('barrier', 'redstone_15'),
                             ('oak_sign', 'redstone_15'),
                             ('oak_leaves', 'ice')]:
            path = 'assets/minecraft/blockstates/%s.json' % block
            if path not in names:
                continue
            try:
                data = json.loads(z.read(path))
            except Exception:
                empty_states.append(block)
                continue
            if host_parts(data, phase) == 0:
                empty_states.append('%s@%s' % (block, phase))

        line_ok = (bad == 0 and has_replace_mixin and has_phase_write
                   and removed_wire_use and removed_wire_ctx
                   and not missing_markers and not empty_states
                   and not cullface_models and not shell_gaps
                   and always_true == 0)
        ok = ok and line_ok
        print('%-8s ver=%-8s blockstates=%-5d bad_conds=%-3d always_true=%-4d replace_mixin=%s '
              'phase_write=%s render_sync=%s cullface=%s shell=%s empty_phase_states=%s -> %s'
              % (ver, mod['version'], len(blockstates), bad, always_true, has_replace_mixin,
                 has_phase_write, 'ok' if not missing_markers else missing_markers,
                 cullface_models or 'none', shell_gaps or 'ok',
                 empty_states or 'none',
                 'OK' if line_ok else 'BROKEN'))

if not jars():
    print('no dist jars found')

sys.exit(0 if ok else 1)
