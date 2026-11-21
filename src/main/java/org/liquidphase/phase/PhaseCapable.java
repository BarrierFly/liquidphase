package org.liquidphase.phase;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 相容宿主标记接口（鸭子模式，mixin 包外——fork 的"接口陷阱"教训：带 default 方法的接口
 * 不能直接作为 @Mixin 目标，用"空壳类 mixin implements 它"注入）。
 * <p>
 * 这是**基接口**：只实现熔岩相（真实熔岩源），不碰原版 {@code WATERLOGGED} 语义。
 * 可含水的宿主走子接口 {@link WaterloggedPhaseCapable}；没有 {@code WATERLOGGED} 属性的
 * 宿主（下界传送门/末地传送门/末地折跃门）直接实现本接口即可——
 * 这样它们的 {@code placeLiquid}/{@code pickupBlock} 永远不会去读不存在的属性
 * （0.1.13 的 barrier 崩溃就是读 {@code WATERLOGGED} 导致的）。
 * <p>
 * 熔岩相 = 真实熔岩源（可流出/被流入/可收走）。原版桶路径
 * （{@code BucketItem.emptyContents} / {@code FlowingFluid.spreadTo}）经接口默认方法自然生效。
 */
public interface PhaseCapable extends LiquidBlockContainer, BucketPickup {

    // ------------------------------------------------------------------
    // canPlaceLiquid —— 各版本 user 参数不同，用 stonecutter 分支
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return fluid == Fluids.LAVA && PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default boolean canPlaceLiquid(net.minecraft.world.entity.player.Player user, BlockGetter level, BlockPos pos,
                                   BlockState state, Fluid fluid) {
        return fluid == Fluids.LAVA && PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
    }
     *///?} else {
    @Override
    default boolean canPlaceLiquid(LivingEntity user, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return fluid == Fluids.LAVA && PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA);
    }
    //?}

    // ------------------------------------------------------------------
    // placeLiquid（签名全版本一致）
    // ------------------------------------------------------------------

    @Override
    default boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (fluidState.is(Fluids.LAVA)) {
            return PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // pickupBlock —— 各版本 user 参数不同
    // ------------------------------------------------------------------

    //? if 1.19.4 || 1.20.1 {
    /*@Override
    default ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        return PhaseCapable.pickupLava(level, pos, state, null);
    }
     *///?} elif 1.21.1 {
    /*@Override
    default ItemStack pickupBlock(net.minecraft.world.entity.player.Player user, LevelAccessor level, BlockPos pos,
                                  BlockState state) {
        return PhaseCapable.pickupLava(level, pos, state, user);
    }
     *///?} else {
    @Override
    default ItemStack pickupBlock(LivingEntity user, LevelAccessor level, BlockPos pos, BlockState state) {
        return PhaseCapable.pickupLava(level, pos, state, user);
    }
    //?}

    /** 收走熔岩相（含水宿主在子接口里处理水相）。 */
    private static ItemStack pickupLava(LevelAccessor level, BlockPos pos, BlockState state, LivingEntity user) {
        if (PhaseHelper.getPhase(state) != Phase.LAVA) {
            return ItemStack.EMPTY;
        }
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), net.minecraft.world.level.block.Block.UPDATE_ALL);
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
        playPickupSound(level, pos);
        // 功能三开启：右键收取禁用（挖掘路径掉落液体物品）；关闭：返还熔岩桶。
        return org.liquidphase.LiquidPhaseConfig.get().liquidInteraction
            ? ItemStack.EMPTY : new ItemStack(Items.LAVA_BUCKET);
    }

    /** 收走熔岩相的音效（原版经 getPickupSound 播放，但它拿不到状态，这里直接播）。 */
    private static void playPickupSound(LevelAccessor level, BlockPos pos) {
        if (level instanceof Level actualLevel) {
            actualLevel.playSound(null, pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    @Override
    default Optional<SoundEvent> getPickupSound() {
        return Optional.empty();
    }
}
