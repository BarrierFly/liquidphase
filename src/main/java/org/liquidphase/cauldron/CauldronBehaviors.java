package org.liquidphase.cauldron;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.liquidphase.LiquidPhaseConfig;

/**
 * 功能二第一批（仅服务端、掉落版，规划 §4；行为对照 cauldronfix，CC0）：
 * <ul>
 *   <li>熔岩锅+水 → 掉落黑曜石物品、锅清空（掉落版，决策点 D12）</li>
 *   <li>水锅+熔岩 → 按水位 3/2/1 掉落石头/圆石/圆石台阶（"半锅圆石"→圆石台阶，
 *       对照 cauldronfix 的半圆石锅掉落表）、锅清空</li>
 *   <li>空锅收流体：只收直接接触（六向）的源（决策点 D13），挂在邻居更新上</li>
 *   <li>混凝土粉末固化：消耗 1 水位，1 个粉末换成对应颜色混凝土</li>
 *   <li>细雪锅遇水/熔岩 → 满水锅</li>
 *   <li>发射器/漏斗自动化见 {@code LiquidInteractions} / {@code CauldronHopperTransfer}</li>
 * </ul>
 * 液体物品口径：功能三开启时水/熔岩"桶"即液体物品，消耗不返还桶；关闭时返还空桶。
 */
public final class CauldronBehaviors {

    private CauldronBehaviors() {
    }

    // ------------------------------------------------------------------
    // 邻居驱动的自动转化（空锅收流体 / 锅内反应）——由 BlockStateBaseMixin 的邻居更新钩子调用
    // ------------------------------------------------------------------

    public static void handleNeighborChanged(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof AbstractCauldronBlock) || level.isClientSide()) {
            return;
        }
        LiquidPhaseConfig config = LiquidPhaseConfig.get();

        if (state.getBlock() == Blocks.CAULDRON && config.cauldronCollectFluid) {
            if (adjacentSource(level, pos, Fluids.WATER)) {
                level.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState()
                    .setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                return;
            }
            if (adjacentSource(level, pos, Fluids.LAVA)) {
                level.setBlock(pos, Blocks.LAVA_CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
                level.playSound(null, pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
                return;
            }
        }

        if (state.getBlock() == Blocks.WATER_CAULDRON && config.cauldronStone && adjacentSource(level, pos, Fluids.LAVA)) {
            lavaMeetsWaterCauldron(level, pos, state);
            return;
        }
        if (state.getBlock() == Blocks.LAVA_CAULDRON && config.cauldronObsidian && adjacentSource(level, pos, Fluids.WATER)) {
            waterMeetsLavaCauldron(level, pos);
            return;
        }
        if (state.getBlock() == Blocks.POWDER_SNOW_CAULDRON && config.cauldronPowderSnow
            && (adjacentSource(level, pos, Fluids.WATER) || adjacentSource(level, pos, Fluids.LAVA))) {
            powderSnowMeetsFluid(level, pos);
        }
    }

    private static boolean adjacentSource(Level level, BlockPos pos, Fluid fluid) {
        for (Direction direction : Direction.values()) {
            if (isSourceOf(level, pos.relative(direction), fluid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSourceOf(Level level, BlockPos pos, Fluid fluid) {
        FluidState fluidState = level.getFluidState(pos);
        return fluidState.isSource() && fluidState.is(fluid);
    }

    // ------------------------------------------------------------------
    // 转化实现（掉落版）
    // ------------------------------------------------------------------

    /** 熔岩锅+水：掉 1 黑曜石、锅清空（掉落版，§4）。 */
    public static void waterMeetsLavaCauldron(Level level, BlockPos pos) {
        pop(level, pos, new ItemStack(Items.OBSIDIAN));
        level.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
        level.levelEvent(1501, pos, 0);
    }

    /** 水锅+熔岩：按水位 3/2/1 掉石头/圆石/圆石台阶、锅清空。 */
    public static void lavaMeetsWaterCauldron(Level level, BlockPos pos, BlockState state) {
        int fluidLevel = state.getValue(LayeredCauldronBlock.LEVEL);
        ItemStack drop = fluidLevel >= 3 ? new ItemStack(Items.STONE)
            : fluidLevel == 2 ? new ItemStack(Items.COBBLESTONE)
            : new ItemStack(Items.COBBLESTONE_SLAB);
        pop(level, pos, drop);
        level.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
        level.levelEvent(1501, pos, 0);
    }

    /** 细雪锅+水/熔岩：变满水锅。 */
    public static void powderSnowMeetsFluid(Level level, BlockPos pos) {
        level.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.POWDER_SNOW_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /** 混凝土粉末固化：消耗 1 水位，手持 1 个粉末换成对应颜色混凝土。 */
    public static boolean hardenConcrete(Level level, BlockPos pos, BlockState state, Player player, InteractionHand hand) {
        if (state.getBlock() != Blocks.WATER_CAULDRON || state.getValue(LayeredCauldronBlock.LEVEL) < 1) {
            return false;
        }
        ItemStack held = player.getItemInHand(hand);
        ItemStack concrete = ConcreteMappings.concreteFor(held);
        if (concrete.isEmpty()) {
            return false;
        }
        held.shrink(1);
        if (held.isEmpty()) {
            player.setItemInHand(hand, concrete);
        } else if (!player.getInventory().add(concrete)) {
            pop(level, pos, concrete);
        }
        int newLevel = state.getValue(LayeredCauldronBlock.LEVEL) - 1;
        level.setBlock(pos, newLevel == 0
            ? Blocks.CAULDRON.defaultBlockState()
            : state.setValue(LayeredCauldronBlock.LEVEL, newLevel), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    // ------------------------------------------------------------------
    // 物品口径（功能三联动）
    // ------------------------------------------------------------------

    /**
     * 液体桶类物品对锅消耗后的返还：功能三开 → 整组消耗 1、无桶返还；
     * 关 → 消耗 1、返还空桶到玩家背包（掉落兜底）。
     */
    public static void consumeLiquidContainer(Level level, BlockPos pos, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        //? if 1.21.10 || 1.21.11 || 26.3 {
        held.consume(1, player);
        if (!LiquidPhaseConfig.get().liquidInteraction && !player.isCreative()) {
            giveOrDrop(level, pos, player, new ItemStack(Items.BUCKET));
        }
        //?} else {
        /*if (!player.isCreative()) {
            held.shrink(1);
            if (!LiquidPhaseConfig.get().liquidInteraction) {
                giveOrDrop(level, pos, player, new ItemStack(Items.BUCKET));
            }
        }
         *///?}
    }

    public static void giveOrDrop(Level level, BlockPos pos, Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            pop(level, pos, stack);
        }
    }

    public static void pop(Level level, BlockPos pos, ItemStack stack) {
        level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }
}
