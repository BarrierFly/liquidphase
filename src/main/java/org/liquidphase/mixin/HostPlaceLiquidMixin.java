package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
//? if 1.21.10 || 1.21.11 || 26.3 {
import net.minecraft.world.level.block.DriedGhastBlock;
//?}
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 蜡烛 / 营火（1.21.10+ 另有干化恶魂）自己声明了 {@code placeLiquid}（不转调 super），
 * {@link org.liquidphase.phase.PhaseCapable} 的熔岩分支被遮蔽：{@code canPlaceLiquid} 走
 * 接口默认方法**接受**熔岩，而宿主的 {@code placeLiquid} 只处理水并返回 false →
 * {@code FlowingFluid.spreadTo} 每 tick 重试、**永久灌不进去**（表现为"熔岩贴着蜡烛不流进去"）。
 * 见 docs/host-coverage-audit.md §4.3。
 * <p>
 * 这里只接管熔岩分支：水仍走宿主自己的实现（蜡烛被水浇灭、营火进水等原版行为不变）。
 * 目标类只列**声明了** {@code placeLiquid} 的（mixin 只能注入目标类自己声明的方法）：
 * 台阶还遮蔽 {@code canPlaceLiquid}，单独立在 {@link SlabLiquidMixin}。
 */
@Mixin({
        CandleBlock.class,
        CampfireBlock.class,
        //? if 1.21.10 || 1.21.11 || 26.3 {
        DriedGhastBlock.class,
        //?}
})
public abstract class HostPlaceLiquidMixin {

    @Inject(method = "placeLiquid", at = @At("HEAD"), cancellable = true)
    private void liquidphase$placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (fluidState.is(Fluids.LAVA)) {
            cir.setReturnValue(PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA));
        }
    }
}
