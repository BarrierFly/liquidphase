package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 状态级形状注入（{@code BlockStateBase} 是统一分发点，宿主覆写块级方法不会遮蔽这里）：
 * <p>
 * ① 固相"整格碰撞"（决策点 D7）：固体相（冰/霜冰/黑曜石）的碰撞 = 整格实心。
 *    必须挂在**状态级**——块级 {@code BlockBehaviour.getCollisionShape} 会被
 *    {@code CrossCollisionBlock}（栅栏/玻璃板/铁栏杆/铜栏杆）、{@code WallBlock}、
 *    {@code WallHangingSignBlock}、{@code ScaffoldingBlock}、{@code SculkShriekerBlock}、
 *    {@code BigDripleafBlock} 自己覆写且不转调 super，块级注入对这 66–88 个宿主完全不执行
 *    （0.1.12 及以前的 bug，见 docs/host-coverage-audit.md §4.4）。
 *    两参版本也要挂：它走 {@code Cache.collisionShape} 缓存，不经过块级分发；
 *    {@code isCollisionShapeFullBlock} 等原版判定走的就是这条缓存路径。
 * ② 形状表兜底（全版本、通用）：不少宿主把形状表按"当时的全部状态"建 Map/Function，
 *    追加的 phase 状态查不到会返回 null；而 {@code BlockStateBase.initCache()} 里
 *    {@code collisionShape.isEmpty()} 会直接 NPE（启动期崩溃）。
 *    这里在状态级把 null 统一兜底成"同状态但 phase=none 的对应形状"：
 *    覆盖 outline / collision / occlusion 三条路径，宿主自己的形状表无需逐个打补丁。
 * <p>
 * 红石相不参与这两项：它用宿主原有形状（不加碰撞、不改轮廓）。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ShapeFallbackMixin {

    private static BlockState liquidphase$vanillaSibling(BlockState state) {
        return state.hasProperty(PhaseHelper.FILLING) ? state.setValue(PhaseHelper.FILLING, Phase.NONE) : state;
    }

    private static boolean liquidphase$isPhased(BlockState state) {
        return state.hasProperty(PhaseHelper.FILLING) && state.getValue(PhaseHelper.FILLING) != Phase.NONE;
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
        if (PhaseHelper.isSolidPhase(self)) {
            cir.setReturnValue(Shapes.block());
        } else if (cir.getReturnValue() == null && liquidphase$isPhased(self)) {
            cir.setReturnValue(liquidphase$vanillaSibling(self).getCollisionShape(level, pos, context));
        }
    }

    /**
     * 两参版本（无 CollisionContext）：它**不走**上面的三参方法——
     * {@code cache != null} 时直接返回 {@code Cache.collisionShape}（启动期按块级方法算好的
     * 宿主形状），只有 {@code cache == null} 才落到三参。不挂这条，固相的整格碰撞
     * 在缓存路径（含 {@code isCollisionShapeFullBlock}）上仍是宿主形状。
     */
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void liquidphase$solidPhaseCollisionCached(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (PhaseHelper.isSolidPhase(self)) {
            cir.setReturnValue(Shapes.block());
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
