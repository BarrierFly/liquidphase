package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 形状表兜底（全版本、通用）：不少宿主把形状表按"当时的全部状态"建 Map/Function，
 * 追加的 phase 状态查不到会返回 null；而 `BlockStateBase.initCache()` 里
 * `collisionShape.isEmpty()` 会直接 NPE（启动期崩溃）。
 * <p>
 * 这里在状态级把 null 统一兜底成"同状态但 phase=none 的对应形状"：
 * 覆盖 outline / collision / occlusion 三条路径，宿主自己的形状表无需逐个打补丁。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ShapeFallbackMixin {

    private static BlockState liquidphase$vanillaSibling(BlockState state) {
        return state.hasProperty(PhaseHelper.PHASE) ? state.setValue(PhaseHelper.PHASE, Phase.NONE) : state;
    }

    private static boolean liquidphase$isPhased(BlockState state) {
        return state.hasProperty(PhaseHelper.PHASE) && state.getValue(PhaseHelper.PHASE) != Phase.NONE;
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void liquidphase$shapeFallback(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            cir.setReturnValue(liquidphase$vanillaSibling(self).getShape(level, pos, context));
        }
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void liquidphase$collisionFallback(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            cir.setReturnValue(liquidphase$vanillaSibling(self).getCollisionShape(level, pos, context));
        }
    }

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getOcclusionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void liquidphase$occlusionFallback(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            // 用状态自身的公开两参版本（块级的 getOcclusionShape 是 protected）
            cir.setReturnValue(liquidphase$vanillaSibling(self).getOcclusionShape(level, pos));
        }
    }
     *///?} else {
    @Inject(method = "getOcclusionShape()Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"), cancellable = true)
    private void liquidphase$occlusionFallback(CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            cir.setReturnValue(liquidphase$vanillaSibling(self).getOcclusionShape());
        }
    }
    //?}

    @Inject(method = "getBlockSupportShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void liquidphase$supportFallback(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            cir.setReturnValue(liquidphase$vanillaSibling(self).getBlockSupportShape(level, pos));
        }
    }

    @Inject(method = "getVisualShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void liquidphase$visualFallback(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            cir.setReturnValue(liquidphase$vanillaSibling(self).getVisualShape(level, pos, context));
        }
    }
}
