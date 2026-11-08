"""按原版 multipart 语义，模拟某个具体方块状态命中哪些 part。

用途：确认红石相状态下**宿主模型仍然渲染**（形状/轮廓不消失），
粉尘外壳只是额外叠加一层。0.1.7 的 bug 就是宿主 part 被排除掉，
整格只剩一个不透明外壳，看起来像"表面被不透明渲染"。

用法:
    python tools/check_redstone_render.py [phase] [block] [jar-or-blockstates-dir]
"""
import json
import os
import sys
import zipfile

PHASE = sys.argv[1] if len(sys.argv) > 1 else 'redstone_15'
BLOCK = sys.argv[2] if len(sys.argv) > 2 else 'oak_stairs'
SOURCE = sys.argv[3] if len(sys.argv) > 3 else 'dist/liquidphase-0.1.7+1.21.11.jar'


def matches(cond, state):
    if not cond:
        return True
    for key, value in cond.items():
        if key in ('AND', 'OR'):
            subs = value if isinstance(value, list) else []
            results = [matches(s, state) for s in subs]
            if key == 'AND' and not all(results):
                return False
            if key == 'OR' and not any(results):
                return False
            continue
        actual = state.get(key)
        if actual is None:
            return False
        raw = str(value)
        negated = raw.startswith('!')
        terms = [t.lstrip('!') for t in raw.split('|')]
        ok = str(actual) in terms
        if negated:
            ok = not ok
        if not ok:
            return False
    return True


def load(source, block):
    if os.path.isdir(source):
        path = os.path.join(source, 'assets/minecraft/blockstates/%s.json' % block)
        return json.load(open(path, encoding='utf-8'))
    with zipfile.ZipFile(source) as z:
        return json.loads(z.read('assets/minecraft/blockstates/%s.json' % block))


state = {
    'facing': 'north', 'half': 'bottom', 'shape': 'straight',
    'waterlogged': 'false', 'phase': PHASE,
}

data = load(SOURCE, BLOCK)
host = shell = overlay = 0
for part in data['multipart']:
    if not matches(part.get('when') or {}, state):
        continue
    model = part['apply']['model']
    if 'redstone_phase' in model:
        shell += 1
    elif 'phase_' in model:
        overlay += 1
    else:
        host += 1

print('state          :', state)
print('host parts     :', host, '(must be > 0: the stair shape has to keep rendering)')
print('redstone shell :', shell)
print('phase overlay  :', overlay)

ok = host > 0 and (shell > 0 if PHASE.startswith('redstone') else True)
print('RESULT         :', 'OK' if ok else 'BROKEN')
sys.exit(0 if ok else 1)
