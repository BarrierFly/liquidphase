package org.liquidphase.phase;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.liquidphase.LiquidPhaseConfig;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 相容宿主标记接口（鸭子模式，mixin 包外——fork 的"接口陷阱"教训：带 default 方法的接口
 * 不能直接作为 @Mixin 目标，用"空壳类 mixin implements 它"注入给全部可含水方块）。
 * <p>
 * 默认方法镜像原版 {@link SimpleWaterloggedBlock} 的流体逻辑：
 * 熔岩相 = 真实熔岩源（可流出/被流入/可收走）；相与水互斥（有相拒水、含水拒熔岩/冰）。
 * 原版桶路径（BucketItem.emptyContents / FlowingFluid.spreadTo）经接口默认方法自然生效；
 * 发射器走同一个 emptyContents。
 */
public interface PhaseCapable extends SimpleWaterloggedBlock {

    // ------------------------------------------------------------------
    // canPlaceLiquid —— 各版本 user 参数不同，用 stonecutter 分支
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        if (fluid == Fluids.LAVA) {
            return PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            return false;
        }
        return SimpleWaterloggedBlock.super.canPlaceLiquid(level, pos, state, fluid);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default boolean canPlaceLiquid(net.minecraft.world.entity.player.Player user, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        if (fluid == Fluids.LAVA) {
            return PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            return false;
        }
        return SimpleWaterloggedBlock.super.canPlaceLiquid(user, level, pos, state, fluid);
    }
     *///?} else {
    @Override
    default boolean canPlaceLiquid(LivingEntity user, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        if (fluid == Fluids.LAVA) {
            return PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            return false;
        }
        return SimpleWaterloggedBlock.super.canPlaceLiquid(user, level, pos, state, fluid);
    }
    //?}

    // ------------------------------------------------------------------
    // placeLiquid（签名全版本一致）
    // ------------------------------------------------------------------

    @Override
    default boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (fluidState.is(Fluids.LAVA)) {
            // 已有相/已含水时拒绝，避免原版"普通放置"分支吞掉方块（fork 的 BucketItemMixin 语义）。
            return PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            return false;
        }
        return SimpleWaterloggedBlock.super.placeLiquid(level, pos, state, fluidState);
    }

    // ------------------------------------------------------------------
    // pickupBlock —— 各版本 user 参数不同
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), Block.UPDATE_ALL);
            if (!state.canSurvive(level, pos)) {
                level.destroyBlock(pos, true);
            }
            playPickupSound(level, pos);
            // 功能三开启：右键收取禁用（挖掘路径掉落液体物品，规划 §3.0.4）；关闭：返还熔岩桶。
            return LiquidPhaseConfig.get().liquidInteraction ? ItemStack.EMPTY : new ItemStack(Items.LAVA_BUCKET);
        }
        return SimpleWaterloggedBlock.super.pickupBlock(level, pos, state);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default ItemStack pickupBlock(net.minecraft.world.entity.player.Player user, LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), Block.UPDATE_ALL);
            if (!state.canSurvive(level, pos)) {
                level.destroyBlock(pos, true);
            }
            playPickupSound(level, pos);
            return LiquidPhaseConfig.get().liquidInteraction ? ItemStack.EMPTY : new ItemStack(Items.LAVA_BUCKET);
        }
        return SimpleWaterloggedBlock.super.pickupBlock(user, level, pos, state);
    }
     *///?} else {
    @Override
    default ItemStack pickupBlock(LivingEntity user, LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), Block.UPDATE_ALL);
            if (!state.canSurvive(level, pos)) {
                level.destroyBlock(pos, true);
            }
            playPickupSound(level, pos);
            return LiquidPhaseConfig.get().liquidInteraction ? ItemStack.EMPTY : new ItemStack(Items.LAVA_BUCKET);
        }
        return SimpleWaterloggedBlock.super.pickupBlock(user, level, pos, state);
    }
    //?}

    /** 收走熔岩相的音效（原版经 getPickupSound 播放，但它拿不到状态，这里直接播）。 */
    private static void playPickupSound(LevelAccessor level, BlockPos pos) {
        if (level instanceof Level actualLevel) {
            actualLevel.playSound(null, pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    // ------------------------------------------------------------------
    // getPickupSound：水相沿用原版默认（熔岩相的音效在 pickupBlock 里播）
    // ------------------------------------------------------------------

    @Override
    default Optional<net.minecraft.sounds.SoundEvent> getPickupSound() {
        return SimpleWaterloggedBlock.super.getPickupSound();
    }
}
