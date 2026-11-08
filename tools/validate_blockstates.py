#!/usr/bin/env python3
"""按原版 multipart 解析语义校验相覆盖 blockstate。

原版规则（`KeyValueCondition` / `CombinedCondition`，1.19.4–26.3 一致）：
- `when` 是 object：键 = 属性名，值 = `a|b|c`（`|` 是同一属性的 OR，`!x` 取反）；
- 一个 object 内的多个键之间是 AND；
- 跨属性组合必须显式写成 `{"AND": [...]}` / `{"OR": [...]}`，且一个 object 里
  **不能**同时出现 AND/OR 与属性键。

所以 `phase: "(obsidian)&(none|lava)"` 这类拼接会被当成单个取值去查，
抛 `Unknown value`，**整份 blockstate 加载失败** → 该方块所有状态变 missing model。
0.1.5/0.1.6 的"可含水方块全变错误模型"就是这个原因（0.1.7 修复）。

用法:
    python tools/validate_blockstates.py                 # 校验各版本的构建产物
    python tools/validate_blockstates.py --jars          # 校验 dist/ 里的 jar
    python tools/validate_blockstates.py <目录或 jar> ...  # 校验指定路径
"""
import collections
import glob
import json
import os
import re
import sys
import zipfile

PHASE_VALUES = frozenset(
    ['none', 'lava', 'obsidian', 'ice', 'frosted_0', 'frosted_1', 'frosted_2', 'frosted_3']
    + ['redstone_%d' % i for i in range(16)])
VERSIONS = ['1.19.4', '1.20.1', '1.21.1', '1.21.10', '1.21.11', '26.3']
BLOCKSTATE_PREFIX = 'assets/minecraft/blockstates/'


def vanilla_props(ver):
    """从该版本客户端 jar 收集"属性名 → 合法取值"（原版解析器的查询口径）。"""
    jars = glob.glob(os.path.expanduser(
        '~/.gradle/caches/fabric-loom/%s/minecraft-client.jar' % ver))
    if not jars:
        return None
    props = collections.defaultdict(set)

    def harvest(cond):
        if not isinstance(cond, dict):
            return
        for key, value in cond.items():
            if key in ('OR', 'AND'):
                for sub in (value if isinstance(value, list) else []):
                    harvest(sub)
                continue
            if isinstance(value, str):
                for term in value.split('|'):
                    props[key].add(term.lstrip('!'))

    with zipfile.ZipFile(jars[0]) as z:
        names = [n for n in z.namelist()
                 if n.startswith(BLOCKSTATE_PREFIX) and n.endswith('.json')]
        for name in names:
            try:
                d = json.loads(z.read(name))
            except Exception:
                continue
            for key in (d.get('variants') or {}):
                for piece in key.split(','):
                    if '=' in piece:
                        k, v = piece.split('=', 1)
                        props[k.strip()].add(v.strip())
            for part in (d.get('multipart') or []):
                harvest(part.get('when'))
    return props


def check_condition(cond, props, errors, where):
    if not isinstance(cond, dict):
        errors.append((where, 'condition-not-object', repr(cond)[:60]))
        return
    if any(key in ('OR', 'AND') for key in cond):
        if len(cond) != 1:
            # 原版 CombinedCondition 解码要求 object 里恰好一个 AND/OR
            errors.append((where, 'combiner-mixed-with-properties', sorted(cond)))
            return
        key = next(iter(cond))
        entries = cond[key]
        if not isinstance(entries, list) or not entries:
            errors.append((where, 'empty-combiner', key))
            return
        for i, sub in enumerate(entries):
            check_condition(sub, props, errors, '%s/%s%d' % (where, key, i))
        return
    for key, value in cond.items():
        if key == 'phase':
            valid = PHASE_VALUES
        else:
            valid = props.get(key)
            if valid is None:
                errors.append((where, 'unknown-property', key))
                continue
        if not isinstance(value, str):
            errors.append((where, 'non-string-value', '%s=%r' % (key, value)))
            continue
        for term in value.split('|'):
            raw = term.lstrip('!')
            if raw not in valid:
                errors.append((where, 'unknown-value', '%s=%s (term %r)' % (key, value, raw)))
                return


def iter_blockstates(source):
    """产出 (文件名, 解析后的 JSON)。source 可以是目录或 jar。"""
    if os.path.isdir(source):
        for name in sorted(os.listdir(source)):
            if not name.endswith('.json'):
                continue
            path = os.path.join(source, name)
            try:
                yield name, json.load(open(path, encoding='utf-8'))
            except Exception as exc:
                yield name, exc
        return
    with zipfile.ZipFile(source) as z:
        for name in sorted(n for n in z.namelist()
                           if n.startswith(BLOCKSTATE_PREFIX) and n.endswith('.json')):
            try:
                yield os.path.basename(name), json.loads(z.read(name))
            except Exception as exc:
                yield os.path.basename(name), exc


def validate_source(label, source, props):
    errors = []
    files = 0
    for name, data in iter_blockstates(source):
        files += 1
        if isinstance(data, Exception):
            errors.append((name, 'unparseable-json', str(data)[:80]))
            continue
        for i, part in enumerate(data.get('multipart') or []):
            check_condition(part.get('when') or {}, props, errors, '%s#%d' % (name, i))
    print('%-30s files=%-4d errors=%d' % (label, files, len(errors)))
    for e in errors[:5]:
        print('   ', e)
    return errors


def guess_version(path):
    m = re.search(r'\+([0-9][0-9.]*)\.jar$', path)
    if m:
        return m.group(1)
    m = re.search(r'versions[/\\]([0-9][0-9.]*)[/\\]', path)
    return m.group(1) if m else None


def main(argv):
    args = [a for a in argv[1:] if not a.startswith('--')]
    use_jars = '--jars' in argv[1:]
    if not args and not use_jars:
        args = ['versions/%s/build/liquidphase-blockstates/assets/minecraft/blockstates' % v
                for v in VERSIONS]
    elif not args:
        args = sorted(glob.glob('dist/liquidphase-*.jar'))

    total = 0
    for path in args:
        ver = guess_version(path)
        props = vanilla_props(ver) if ver else None
        if props is None:
            print('%-30s skipped (no client jar for version %r)' % (os.path.basename(path), ver))
            continue
        total += len(validate_source(os.path.basename(path), path, props))
    print('\nTOTAL ERRORS:', total)
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
