package org.liquidphase.phase;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 相材料的物品↔方块映射（规划 §3.0.2 放置吸收）：
 * 拿相材料物品指向可 log 方块 → 相写入该格；拿可 log 方块指向相材料方块本体 → 相被吸收。
 * 里程碑 2 的红石线/元件材料在这里扩展。
 */
public final class PhaseAbsorption {

    private PhaseAbsorption() {
    }

    /** {@return 相材料物品的 BlockItem 对应写入的相；非相材料返回 null} */
    public static Phase phaseOfItem(Block block) {
        if (block == Blocks.ICE) {
            return Phase.ICE;
        }
        if (block instanceof FrostedIceBlock) {
            return Phase.FROSTED_0;
        }
        if (block == Blocks.OBSIDIAN) {
            return Phase.OBSIDIAN;
        }
        return null;
    }

    /** {@return 真实相材料方块状态对应被吸收的相；非相材料方块返回 null} */
    public static Phase phaseOfBlock(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ICE) {
            return Phase.ICE;
        }
        if (block == Blocks.FROSTED_ICE) {
            int age = state.hasProperty(BlockStateProperties.AGE_3) ? state.getValue(BlockStateProperties.AGE_3) : 0;
            return Phase.frosted(age);
        }
        if (block == Blocks.OBSIDIAN) {
            return Phase.OBSIDIAN;
        }
        return null;
    }

    /** {@return 手持物品是否为相材料 BlockItem} */
    public static boolean isPhaseMaterialItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && phaseOfItem(item.getBlock()) != null;
    }

    /**
     * {@return 手持物品是否为"填充相材料"（可写进宿主格的相材料）：
     * 冰/霜冰/黑曜石的 BlockItem，或红石粉（红石线的物品形态）。}
     * 与 {@link #isPhaseMaterialItem} 的差别：红石粉也算填充相材料（写红石相）。
     */
    public static boolean isFillingMaterialItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item
            && (phaseOfItem(item.getBlock()) != null || item.getBlock() == Blocks.REDSTONE_WIRE);
    }
}
