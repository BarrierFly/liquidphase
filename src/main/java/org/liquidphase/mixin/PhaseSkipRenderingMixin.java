package org.liquidphase.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseRenderSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Neighbour face culling: phased states never take part in vanilla "same block, drop the face".
 * <p>
 * Vanilla {@code BlockState.skipRendering(neighbour, direction)} implementations
 * ({@code LeavesBlock}, {@code IronBarsBlock}, {@code HalfTransparentBlock}, ...) drop a face
 * when the neighbour is the same kind of block. Phase overlays and the redstone shell are an
 * <b>extra</b> layer; culling by that rule makes two adjacent phased blocks each lose the
 * shared face, so the phase appears to have a missing corner and redstone dust vanishes
 * between two adjacent redstone-phase stairs.
 * <p>
 * This only forces "do not cull" when either side carries a phase. States without a phase
 * behave exactly as vanilla.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PhaseSkipRenderingMixin {

    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void liquidphase$dontCullPhasedFaces(BlockState neighbour, net.minecraft.core.Direction direction,
                                                CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        if (!PhaseRenderSupport.allowVanillaCulling(self, neighbour)) {
            cir.setReturnValue(false);
        }
    }
}
