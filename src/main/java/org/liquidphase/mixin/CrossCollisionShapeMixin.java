package org.liquidphase.mixin;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CrossCollisionBlock;
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
 * 形状缓存补丁：栅栏/玻璃板/铁栏杆父类（仅 1.21.10+ 按 Function 建表；
 * 更早版本用按位索引数组，phase 状态天然兼容，无需处理）。
 */
@Mixin(CrossCollisionBlock.class)
public abstract class CrossCollisionShapeMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Shadow
    @Final
    private Function<BlockState, VoxelShape> collisionShapes;

    @Shadow
    @Final
    private Function<BlockState, VoxelShape> shapes;

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            cir.setReturnValue(this.shapes.apply(state.setValue(PhaseHelper.PHASE, Phase.NONE)));
        }
    }

    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            cir.setReturnValue(this.collisionShapes.apply(state.setValue(PhaseHelper.PHASE, Phase.NONE)));
        }
    }
    //?}
}
