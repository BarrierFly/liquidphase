#!/usr/bin/env python3
"""检查每个版本 jar 里 PhaseCapableMixin 的 @Mixin 目标（针对多面体/石钟乳家族）。

为什么要单独查这个：这些方块在 1.21.10+ 合并/改名过
（GlowLichenBlock+SculkVeinBlock → MultifaceBlock、PointedDripstoneBlock → SpeleothemBlock），
stonecutter 分支写错不会编译失败，但会让方块拿不到 phase 属性 →
客户端 blockstate 里的 phase 条件报 Unknown property 并退化成 missing model。

用法: python tools/check_mixin_targets.py [版本 ...]
默认检查 tools/phase-blocks/*.ids 里出现过的所有版本。
"""
import glob
import os
import re
import struct
import sys
import zipfile

FAMILY = re.compile(r'GlowLichenBlock|SculkVeinBlock|MultifaceBlock|'
                    r'PointedDripstoneBlock|SpeleothemBlock')
CLASS_DESC = re.compile(r'^Lnet/minecraft/(?:world/level/block/)?([A-Za-z0-9_$]+);$')


def utf8_constants(data):
    """最小常量池解析：只取 Utf8 项。"""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    count = struct.unpack('>H', data[8:10])[0]
    i, idx, out = 10, 1, []
    while idx < count:
        tag = data[i]
        i += 1
        if tag == 1:  # Utf8
            length = struct.unpack('>H', data[i:i + 2])[0]
            i += 2
            out.append(data[i:i + length].decode('utf-8', 'replace'))
            i += length
        elif tag in (7, 8, 16, 19, 20):
            i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8
            idx += 1
        else:
            raise ValueError('unknown constant pool tag %d' % tag)
        idx += 1
    return out


def load_remap_table(ver):
    """intermediary 内部名 → 官方名。

    fabric-loom 缓存里同版本有多个 mappings.tiny（不同 hash / 不同 mapping 类型），
    必须挑"把 net/minecraft/class_xxx 映射到官方名"的那一份（而不是映射到 a/b/c 混淆名）。
    """
    best = {}
    for mappings in glob.glob(os.path.expanduser(
            '~/.gradle/caches/fabric-loom/%s/*/mappings.tiny' % ver)):
        table = {}
        for line in open(mappings, encoding='utf-8'):
            if line.startswith('c\t'):
                parts = line.rstrip('\n').split('\t')
                if len(parts) > 3:
                    table[parts[2]] = parts[3]
        score = sum(1 for v in table.values() if v.startswith('net/minecraft/'))
        if score > sum(1 for v in best.values() if v.startswith('net/minecraft/')):
            best = table
    return best


def jar_path(ver):
    candidates = []
    for pattern in ('versions/%s/build/libs/liquidphase-*.jar',
                    'dist/liquidphase-*+%s.jar'):
        for path in glob.glob(pattern % ver):
            if 'sources' not in path:
                candidates.append(path)
    if not candidates:
        return None
    # 同一版本可能残留旧版本号的 jar（AGENTS.md §6 的坑），只取最新的
    return max(candidates, key=os.path.getmtime)


def main(argv):
    versions = argv[1:] or sorted(
        os.path.basename(p)[:-len('.ids')]
        for p in glob.glob('tools/phase-blocks/*.ids'))
    failed = False
    for ver in versions:
        jar = jar_path(ver)
        if jar is None:
            print('%-8s jar not found (build it first)' % ver)
            failed = True
            continue
        with zipfile.ZipFile(jar) as z:
            data = z.read('org/liquidphase/mixin/PhaseCapableMixin.class')
        table = load_remap_table(ver)
        names = []
        for const in utf8_constants(data):
            # jar 里是 intermediary 名（Lnet/minecraft/class_xxxx;），映射表键也是 intermediary
            m = re.match(r'^L(net/minecraft/[^;]+);$', const)
            if not m:
                continue
            intermediary = m.group(1)
            official = table.get(intermediary, intermediary)
            names.append(official.split('/')[-1])
        family = sorted(set(n for n in names if FAMILY.search(n)))
        if ver in ('1.21.10', '1.21.11', '26.3'):
            ok = ('MultifaceBlock' in family
                  and 'GlowLichenBlock' not in family
                  and 'SculkVeinBlock' not in family)
        else:
            ok = 'GlowLichenBlock' in family and 'SculkVeinBlock' in family
        failed |= not ok
        print('%-8s targets=%-2d family=%s %s'
              % (ver, len(names), family, 'OK' if ok else 'MISMATCH'))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
