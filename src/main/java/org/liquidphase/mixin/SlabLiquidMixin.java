package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 台阶自己声明了 {@code canPlaceLiquid} + {@code placeLiquid}（不转调 super），
 * {@link org.liquidphase.phase.PhaseCapable} 的熔岩分支被整个遮蔽：
 * {@code canPlaceLiquid} 对非水恒拒 → {@code FlowingFluid.canHoldFluid} 拒熔岩，
 * "相邻熔岩按形状灌入台阶"完全不成立（熔岩桶/发射器/放置吸收走
 * {@code PhaseHelper.fillWithPhase}，不受影响）。见 docs/host-coverage-audit.md §4.3。
 * <p>
 * 两个注入只接管熔岩：水仍走宿主自己的 SimpleWaterlogged 逻辑，其余行为原封不动。
 * canPlaceLiquid 的 user 参数分版本（≤1.20.1 无、1.21.1 是 Player、1.21.10+ 是 LivingEntity）。
 */
@Mixin(SlabBlock.class)
public abstract class SlabLiquidMixin {

    //? if 1.19.4 || 1.20.1 {
    /*@Inject(method = "canPlaceLiquid", at = @At("HEAD"), cancellable = true)
    private void liquidphase$canPlaceLiquid(net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                            BlockState state, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (fluid == Fluids.LAVA) {
            cir.setReturnValue(PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA));
        }
    }
     *///?} elif 1.21.1 {
    /*@Inject(method = "canPlaceLiquid", at = @At("HEAD"), cancellable = true)
    private void liquidphase$canPlaceLiquid(net.minecraft.world.entity.player.Player user,
                                            net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                            BlockState state, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (fluid == Fluids.LAVA) {
            cir.setReturnValue(PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA));
        }
    }
     *///?} else {
    @Inject(method = "canPlaceLiquid", at = @At("HEAD"), cancellable = true)
    private void liquidphase$canPlaceLiquid(net.minecraft.world.entity.LivingEntity user,
                                            net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                            BlockState state, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (fluid == Fluids.LAVA) {
            cir.setReturnValue(PhaseHelper.canAcceptPhase(state, level, pos, Phase.LAVA));
        }
    }
    //?}

    @Inject(method = "placeLiquid", at = @At("HEAD"), cancellable = true)
    private void liquidphase$placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (fluidState.is(Fluids.LAVA)) {
            cir.setReturnValue(PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA));
        }
    }
}
