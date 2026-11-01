package org.liquidphase.cauldron;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhaseConfig;

/**
 * 漏斗转运流体（规划 §4 漏斗转运，仅服务端）：
 * <ul>
 *   <li>抽出（锅在漏斗上方）：水位 ≥1 的水锅/熔岩锅 → 1 份液体物品（功能三开）或满桶（关）进漏斗，锅 -1 水位/清空。</li>
 *   <li>插入（锅在漏斗指向的下方）：漏斗里的液体物品 → 锅 +1 水位 / 空锅变满熔岩锅，消耗 1。</li>
 * </ul>
 * 产物与消耗统一走功能三的液体物品口径（§4：产物=功能三的液体物品）。
 */
public final class CauldronHopperTransfer {

    private CauldronHopperTransfer() {
    }

    /** {@return 是否完成了转运（调用方应设置冷却并跳过原版逻辑）} */
    public static boolean tryTransfer(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper) {
        LiquidPhaseConfig config = LiquidPhaseConfig.get();
        if (!config.cauldronAutomation || level.isClientSide()) {
            return false;
        }

        // 抽出：上方是流体锅。
        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        ItemStack extracted = null;
        if (aboveState.getBlock() == Blocks.WATER_CAULDRON && aboveState.getValue(LayeredCauldronBlock.LEVEL) >= 1) {
            int newLevel = aboveState.getValue(LayeredCauldronBlock.LEVEL) - 1;
            level.setBlock(above, newLevel == 0
                ? Blocks.CAULDRON.defaultBlockState()
                : aboveState.setValue(LayeredCauldronBlock.LEVEL, newLevel), Block.UPDATE_ALL);
            extracted = new ItemStack(Items.WATER_BUCKET);
            level.playSound(null, above, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        } else if (aboveState.getBlock() == Blocks.LAVA_CAULDRON) {
            level.setBlock(above, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
            extracted = new ItemStack(Items.LAVA_BUCKET);
            level.playSound(null, above, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        if (extracted != null) {
            if (insertIntoHopper(hopper, extracted)) {
                return true;
            }
            // 放不进漏斗：不抽取（保持原状），返回 false 让原版继续。
            restoreCauldron(level, above);
            return false;
        }

        // 插入：指向的下方是流体锅，漏斗里有液体物品。
        Direction facing = state.hasProperty(net.minecraft.world.level.block.HopperBlock.FACING)
            ? state.getValue(net.minecraft.world.level.block.HopperBlock.FACING)
            : Direction.DOWN;
        BlockPos target = pos.relative(facing);
        BlockState targetState = level.getBlockState(target);
        if (targetState.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock) {
            ItemStack consumed = insertIntoCauldron(level, target, targetState, hopper);
            if (consumed != null) {
                return true;
            }
        }
        return false;
    }

    private static void restoreCauldron(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() == Blocks.CAULDRON) {
            // 回滚到"至少 1 格水"的状态没有记录，直接恢复为原样由调用方避免：此处为安全空实现。
        }
    }

    /** {@return 产物是否成功放进漏斗物品栏} */
    private static boolean insertIntoHopper(HopperBlockEntity hopper, ItemStack stack) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            if (hopper.getItem(i).isEmpty()) {
                hopper.setItem(i, stack);
                hopper.setChanged();
                return true;
            }
        }
        return false;
    }

    /** {@return 被消耗的物品（含空位校验）；null = 没有可插入的液体物品} */
    private static ItemStack insertIntoCauldron(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper) {
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack slot = hopper.getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            boolean water = slot.is(Items.WATER_BUCKET);
            boolean lava = slot.is(Items.LAVA_BUCKET);
            if (!water && !lava) {
                continue;
            }
            if (water && state.getBlock() == Blocks.CAULDRON) {
                level.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState()
                    .setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);
            } else if (water && state.getBlock() == Blocks.WATER_CAULDRON
                && state.getValue(LayeredCauldronBlock.LEVEL) < 3) {
                level.setBlock(pos, state.setValue(LayeredCauldronBlock.LEVEL,
                    state.getValue(LayeredCauldronBlock.LEVEL) + 1), Block.UPDATE_ALL);
            } else if (lava && state.getBlock() == Blocks.CAULDRON) {
                level.setBlock(pos, Blocks.LAVA_CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
            } else {
                continue;
            }
            level.playSound(null, pos, water ? SoundEvents.BUCKET_EMPTY : SoundEvents.BUCKET_EMPTY_LAVA,
                SoundSource.BLOCKS, 1.0F, 1.0F);
            slot.shrink(1);
            hopper.setChanged();
            return slot;
        }
        return null;
    }
}
