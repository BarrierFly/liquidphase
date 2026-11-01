package org.liquidphase.mixin;

import java.util.function.Function;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 形状缓存补丁：栅栏自建按状态遮挡形状表（仅 1.21.10+；构造期建表，新状态查 null 会崩——
 * 新状态 initCache 时就会触碰，必须拦在 initCache 之前）。
 */
@Mixin(FenceBlock.class)
public abstract class FenceOcclusionMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Shadow
    @Final
    private Function<BlockState, VoxelShape> occlusionShapes;

    @Inject(method = "getOcclusionShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getOcclusionShape(BlockState state, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            cir.setReturnValue(this.occlusionShapes.apply(state.setValue(PhaseHelper.PHASE, Phase.NONE)));
        }
    }
    //?}
}
