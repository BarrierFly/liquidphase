package org.liquidphase.mixin;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
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
 * 形状缓存补丁（仅 1.21.10+：更早版本的多面体家族没有按状态的形状表）。
 */
//? if 1.21.10 || 1.21.11 || 26.3 {
@Mixin(MultifaceBlock.class)
public abstract class MultifaceShapeMixin {

    @Shadow
    @Final
    private Function<BlockState, VoxelShape> shapes;

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            cir.setReturnValue(this.shapes.apply(state.setValue(PhaseHelper.FILLING, Phase.NONE)));
        }
    }
}
//?} else {
/*// 空壳：旧版本多面体家族无按状态形状表，无需补丁。
@Mixin(MultifaceBlock.class)
public abstract class MultifaceShapeMixin {
}
 *///?}
