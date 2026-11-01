package org.liquidphase.mixin;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.PhaseTargeting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 瞄准合并（决策点 D8）：射线对固相宿主取"宿主轮廓 ∪ 相可见区域"，
 * 客户端准星与服务端判定共用同一条路径（ClipContext 在两端都被用于 OUTLINE 射线）。
 */
@Mixin(ClipContext.class)
public abstract class ClipContextMixin {

    @Inject(method = "getBlockShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$mergePhaseOutline(BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape merged = PhaseTargeting.mergedOutline(state, cir.getReturnValue());
        if (merged != null) {
            cir.setReturnValue(merged);
        }
    }
}
