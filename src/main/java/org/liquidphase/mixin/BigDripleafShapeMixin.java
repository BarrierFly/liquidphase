package org.liquidphase.mixin;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BigDripleafBlock;
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
 * 形状缓存补丁：大垂滴叶的 outline 形状表按状态建表（构造期），
 * phase 状态查表得 null 会崩。碰撞形状按 TILT 静态取，无需修复（fork 结论）。
 * 字段类型分版本：≤1.21.1 是 Map（shapesCache），1.21.10+ 是 Function（shapes）。
 */
@Mixin(BigDripleafBlock.class)
public abstract class BigDripleafShapeMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Shadow
    @Final
    private Function<BlockState, VoxelShape> shapes;
    //?} else {
    /*@Shadow
    @Final
    private java.util.Map<BlockState, VoxelShape> shapesCache;
     *///?}

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.getPhase(state) != Phase.NONE) {
            BlockState vanilla = state.setValue(PhaseHelper.PHASE, Phase.NONE);
            //? if 1.21.10 || 1.21.11 || 26.3 {
            cir.setReturnValue(this.shapes.apply(vanilla));
            //?} else {
            /*cir.setReturnValue(this.shapesCache.get(vanilla));
             *///?}
        }
    }
}
