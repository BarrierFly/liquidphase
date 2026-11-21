package org.liquidphase.phase;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 可含水的相位宿主（楼梯/台阶/栅栏/树叶/告示牌……全部 {@code SimpleWaterloggedBlock} 实现）。
 * <p>
 * 在 {@link PhaseCapable} 的熔岩分支之外补上原版含水语义：
 * <ul>
 *   <li>有相（{@code filling != none}）时拒绝水；</li>
 *   <li>无相时水走 {@code SimpleWaterloggedBlock} 的默认实现；</li>
 *   <li>红石/元件条目不影响水的判定（它们不占状态）。</li>
 * </ul>
 * 没有 {@code WATERLOGGED} 属性的宿主（传送门三件套）只实现 {@link PhaseCapable}，
 * 永远不会走到这里——这是 0.1.13 barrier 崩溃的根因防线。
 */
public interface WaterloggedPhaseCapable extends PhaseCapable, SimpleWaterloggedBlock {

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        if (fluid == Fluids.LAVA) {
            return PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE
            || !PhaseExtra.isEmpty(PhaseExtras.get(level, pos))) {
            return false;
        }
        return SimpleWaterloggedBlock.super.canPlaceLiquid(level, pos, state, fluid);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default boolean canPlaceLiquid(net.minecraft.world.entity.player.Player user, BlockGetter level, BlockPos pos,
                                   BlockState state, Fluid fluid) {
        if (fluid == Fluids.LAVA) {
            return PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE
            || !PhaseExtra.isEmpty(PhaseExtras.get(level, pos))) {
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
        if (PhaseHelper.getPhase(state) != Phase.NONE
            || !PhaseExtra.isEmpty(PhaseExtras.get(level, pos))) {
            return false;
        }
        return SimpleWaterloggedBlock.super.canPlaceLiquid(user, level, pos, state, fluid);
    }
    //?}

    @Override
    default boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (fluidState.is(Fluids.LAVA)) {
            return PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA);
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE
            || !PhaseExtra.isEmpty(PhaseExtras.get(level, pos))) {
            return false;
        }
        return SimpleWaterloggedBlock.super.placeLiquid(level, pos, state, fluidState);
    }

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            return PhaseCapable.super.pickupBlock(level, pos, state);
        }
        return SimpleWaterloggedBlock.super.pickupBlock(level, pos, state);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default ItemStack pickupBlock(net.minecraft.world.entity.player.Player user, LevelAccessor level, BlockPos pos,
                                  BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            return PhaseCapable.super.pickupBlock(user, level, pos, state);
        }
        return SimpleWaterloggedBlock.super.pickupBlock(user, level, pos, state);
    }
     *///?} else {
    @Override
    default ItemStack pickupBlock(LivingEntity user, LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            return PhaseCapable.super.pickupBlock(user, level, pos, state);
        }
        return SimpleWaterloggedBlock.super.pickupBlock(user, level, pos, state);
    }
    //?}

    @Override
    default Optional<SoundEvent> getPickupSound() {
        return SimpleWaterloggedBlock.super.getPickupSound();
    }
}
