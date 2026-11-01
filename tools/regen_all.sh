#!/bin/bash
# 重新生成红石相组合方块（类 + 工厂）并清理。用法: bash tools/regen_all.sh
set -e
cd "$(dirname "$0")/.."
python tools/gen_redstone_composites.py "D:/git_repos/Minecraft Mods/liquid phase/guardian" \
  tools/phase-blocks tools/phase-blocks src/main/java/org/liquidphase/redstone/composites | tail -2
python tools/clean_composites.py src/main/java/org/liquidphase/redstone/composites
