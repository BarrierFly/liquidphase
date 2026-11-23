package org.liquidphase.phase;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 可含水的相位宿主（楼梯/台阶/栅栏/树叶/告示牌……全部 {@code SimpleWaterloggedBlock} 实现）。
 * <p>
 * 在 {@link PhaseCapable} 的熔岩分支之外补上原版含水语义：
 * <ul>
 *   <li>有材料相（{@code filling != none}）时拒绝水；</li>
 *   <li>无材料相时水走 {@code SimpleWaterloggedBlock} 的默认实现；</li>
 *   <li>红石/元件条目（0.2.5 起）：<b>可冲落</b>——水会先把它冲掉（弹出对应物品），
 *       然后正常充水，对齐原版"水冲掉红石粉/红石火把"的语义。判定走
 *       {@link PhaseExtra#isWashable}，是实时的（以后新增可冲落种类不用改这里）。</li>
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
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            return false;
        }
        // 0.2.5：可冲落的条目（红石相/元件）不阻止充水——真正冲落在 placeLiquid 里做，
        // 探测阶段保持"未改变世界"（流体引擎会先 canPlaceLiquid 再 placeLiquid）。
        return SimpleWaterloggedBlock.super.canPlaceLiquid(level, pos, state, fluid);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default boolean canPlaceLiquid(net.minecraft.world.entity.player.Player user, BlockGetter level, BlockPos pos,
                                   BlockState state, Fluid fluid) {
        if (fluid == Fluids.LAVA) {
            return PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
        }
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return false;
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
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
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
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            return false;
        }
        // 0.2.5：水冲落可冲落的条目（红石相/元件），再照常充水。
        // 放在这里而不是 canPlaceLiquid 里，是因为探测阶段应当无副作用。
        if (level instanceof Level actualLevel && PhaseHelper.washOut(actualLevel, pos)) {
            state = actualLevel.getBlockState(pos);
        }
        return SimpleWaterloggedBlock.super.placeLiquid(level, pos, state, fluidState);
    }

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            return PhaseCapable.super.pickupBlock(level, pos, state);
        }
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return ItemStack.EMPTY;
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
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return ItemStack.EMPTY;
        }
        return SimpleWaterloggedBlock.super.pickupBlock(user, level, pos, state);
    }
     *///?} else {
    @Override
    default ItemStack pickupBlock(LivingEntity user, LevelAccessor level, BlockPos pos, BlockState state) {
        if (PhaseHelper.getPhase(state) == Phase.LAVA) {
            return PhaseCapable.super.pickupBlock(user, level, pos, state);
        }
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return ItemStack.EMPTY;
        }
        return SimpleWaterloggedBlock.super.pickupBlock(user, level, pos, state);
    }
    //?}

    @Override
    default Optional<SoundEvent> getPickupSound() {
        return SimpleWaterloggedBlock.super.getPickupSound();
    }
}
