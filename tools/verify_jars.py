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
    'org/liquidphase/mixin/PhaseRenderShapeMixin.class': b'liquidphase$phasedRenderShape',
    'org/liquidphase/phase/PhaseRenderSupport.class': b'hasPhase',
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


def always_true_conditions(data):
    """原版 `KeyValueCondition` 对"覆盖属性全部取值"的条件会打 always-true 警告。

    我们生成的 `filling` 条件里，一个 `|` 分隔值若包含全部 24 个相取值就算恒真。
    每个方块状态一条警告，1.21.10+ 会把加载日志刷爆。
    """
    all_values = {'none', 'lava', 'obsidian', 'ice',
                  'frosted_0', 'frosted_1', 'frosted_2', 'frosted_3'}
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
            if key == 'filling' and isinstance(value, str):
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
        has_phase_write = b'liquidphase$captureWirePower' in block_item
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

        # 方案 C：**每一个**被本 mod 改写过的 blockstate 都必须有一条无条件的
        # `liquidphase:block/dynamic` part（红石外壳/元件相的几何由它按坐标发射）。
        missing_dynamic = []
        for path in blockstates:
            block = path[len('assets/minecraft/blockstates/'):-len('.json')]
            try:
                data = json.loads(z.read(path))
            except Exception:
                missing_dynamic.append(block)
                continue
            if not any('liquidphase:block/' in json.dumps(part.get('apply') or {})
                       for part in (data.get('multipart') or [])):
                continue
            if not any(part.get('when') is None
                       and 'liquidphase:block/dynamic' in json.dumps(part.get('apply') or {})
                       for part in (data.get('multipart') or [])):
                missing_dynamic.append(block)

        # 反向：清单里每个 id 都要有产物，且产物集合不能有清单之外的多余项。
        # 只比数量不够——清单两边各错一个、数量一样，漏掉的那个方块仍然不显示红石相。
        ids_file = 'tools/phase-blocks/%s.ids' % ver
        missing_blockstates = []
        if os.path.exists(ids_file):
            ids = [line.strip() for line in open(ids_file, encoding='utf-8') if line.strip()]
            missing_blockstates = [i for i in ids
                                   if 'assets/minecraft/blockstates/%s.json' % i not in names]
            generated = {path[len('assets/minecraft/blockstates/'):-len('.json')]
                         for path in blockstates}
            unlisted = sorted(generated - set(ids))
            if unlisted:
                missing_blockstates.append('unlisted: %s' % unlisted[:5])

        # 随 jar 发布的清单必须与这里读到的清单一致（防止打包漏带资源）。
        shipped = 'phase-blocks.ids'
        if shipped in names:
            bundled = {line.strip() for line in z.read(shipped).decode('utf-8').splitlines()
                       if line.strip() and not line.startswith('#')}
            if bundled != set(ids if os.path.exists(ids_file) else ()):
                missing_blockstates.append('bundled phase-blocks.ids != tools/phase-blocks/%s.ids' % ver)
        else:
            missing_blockstates.append('bundled phase-blocks.ids missing')

        line_ok = (bad == 0 and has_replace_mixin and has_phase_write
                   and removed_wire_use and removed_wire_ctx
                   and not missing_markers and not missing_dynamic and not missing_blockstates
                   and not cullface_models and not shell_gaps
                   and always_true == 0)
        ok = ok and line_ok
        print('%-8s ver=%-8s blockstates=%-5d bad_conds=%-3d always_true=%-4d replace_mixin=%s '
              'phase_write=%s render_sync=%s cullface=%s shell=%s missing_dynamic=%s '
              'unrendered_ids=%s -> %s'
              % (ver, mod['version'], len(blockstates), bad, always_true, has_replace_mixin,
                 has_phase_write, 'ok' if not missing_markers else missing_markers,
                 cullface_models or 'none', shell_gaps or 'ok',
                 missing_dynamic or 'none', missing_blockstates or 'none',
                 'OK' if line_ok else 'BROKEN'))

if not jars():
    print('no dist jars found')

sys.exit(0 if ok else 1)
