#!/bin/bash
# 重新生成红石相组合方块类（CompositeFactories 已改为手写维护，不再自动生成）。
# 用法: bash tools/regen_all.sh
set -e
cd "$(dirname "$0")/.."
python tools/gen_redstone_composites.py "D:/git_repos/Minecraft Mods/liquid phase/guardian" \
  tools/phase-blocks tools/phase-blocks src/main/java/org/liquidphase/redstone/composites | tail -2
python tools/clean_composites.py src/main/java/org/liquidphase/redstone/composites
echo "注意：CompositeFactories.java 为手写文件，上面的生成器不覆盖它。"
