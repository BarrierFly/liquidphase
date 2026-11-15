package org.liquidphase.mixin;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.WallBlock;
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
 * 形状缓存补丁：墙的 outline/collision 形状表在构造器里按"当时的全部状态"建表，
 * 追加的 phase 状态查表得 null 会崩。重定向到 phase=none 的原版状态——连接态相同、形状相同。
 * 字段类型分版本：≤1.21.1 是 Map（shapeByIndex），1.21.10+ 是 Function（shapes）。
 */
@Mixin(WallBlock.class)
public abstract class WallBlockShapeMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Shadow
    @Final
    private Function<BlockState, VoxelShape> shapes;

    @Shadow
    @Final
    private Function<BlockState, VoxelShape> collisionShapes;
    //?} else {
    /*@Shadow
    @Final
    private java.util.Map<BlockState, VoxelShape> shapeByIndex;

    @Shadow
    @Final
    private java.util.Map<BlockState, VoxelShape> collisionShapeByIndex;
     *///?}

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            BlockState vanilla = state.setValue(PhaseHelper.FILLING, Phase.NONE);
            //? if 1.21.10 || 1.21.11 || 26.3 {
            cir.setReturnValue(this.shapes.apply(vanilla));
            //?} else {
            /*cir.setReturnValue(this.shapeByIndex.get(vanilla));
             *///?}
        }
    }

    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            BlockState vanilla = state.setValue(PhaseHelper.FILLING, Phase.NONE);
            //? if 1.21.10 || 1.21.11 || 26.3 {
            cir.setReturnValue(this.collisionShapes.apply(vanilla));
            //?} else {
            /*cir.setReturnValue(this.collisionShapeByIndex.get(vanilla));
             *///?}
        }
    }
}
