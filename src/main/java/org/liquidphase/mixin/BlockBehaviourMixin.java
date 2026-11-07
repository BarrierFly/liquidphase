package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 决策点 D7 的碰撞覆盖（块级）：固体相 = 整格实心。
 * BlockStateBase 的碰撞形状缓存在 initCache 里经"块级" getCollisionShape 计算（新状态
 * initCache 时经过这里得到整格实心）；本注入与 BlockStateBaseMixin 的状态级注入共同
 * 覆盖缓存与直连两条路径。
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {

    @Inject(method = "getCollisionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (PhaseHelper.isSolidPhase(state) && !state.hasOffsetFunction()) {
            cir.setReturnValue(Shapes.block());
        }
    }

    /** 块级兜底：phase 状态在宿主的形状表里不存在时（返回 null），回退到 phase=none 的同一状态。 */
    /**
     * 需要形状兜底的状态：①带相的状态（phase != none）；②红石相组合方块（有 power）。
     * 其余状态直接放行——同名状态是宿主自己的，形状表里一定有，也避免递归。
     */
    private static boolean liquidphase$needsFallback(net.minecraft.world.level.block.state.BlockState state) {
        boolean phased = state.hasProperty(org.liquidphase.phase.PhaseHelper.PHASE)
            && state.getValue(org.liquidphase.phase.PhaseHelper.PHASE) != org.liquidphase.phase.Phase.NONE;
        boolean composite = state.hasProperty(org.liquidphase.redstone.RedstoneBlocks.POWER);
        return phased || composite;
    }

    private static net.minecraft.world.level.block.state.BlockState liquidphase$sibling(net.minecraft.world.level.block.state.BlockState state) {
        if (state.hasProperty(org.liquidphase.redstone.RedstoneBlocks.POWER)) {
            // 组合方块：POWER=0 的原版状态（宿主形状表里一定有）
            return state.setValue(org.liquidphase.redstone.RedstoneBlocks.POWER, 0);
        }
        return state.hasProperty(org.liquidphase.phase.PhaseHelper.PHASE)
            ? state.setValue(org.liquidphase.phase.PhaseHelper.PHASE, org.liquidphase.phase.Phase.NONE)
            : state;
    }

    @Inject(method = "getCollisionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$collisionNullable(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getCollisionShape(level, pos, context);
            cir.setReturnValue(fallback == null ? net.minecraft.world.phys.shapes.Shapes.empty() : fallback);
        }
    }

    @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$shapeNullable(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getShape(level, pos, context);
            cir.setReturnValue(fallback == null ? net.minecraft.world.phys.shapes.Shapes.empty() : fallback);
        }
    }

    // 块级遮挡形状兜底：initCache 里 occlusionShape 为 null 会让 isSolidRender 崩溃（1.21.1 实测）。
    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getOcclusionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$occlusionNullable(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getOcclusionShape(level, pos);
            cir.setReturnValue(fallback == null ? net.minecraft.world.phys.shapes.Shapes.empty() : fallback);
        }
    }
     *///?} else {
    @Inject(method = "getOcclusionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$occlusionNullable(BlockState state, CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getOcclusionShape();
            cir.setReturnValue(fallback == null ? net.minecraft.world.phys.shapes.Shapes.empty() : fallback);
        }
    }
    //?}

    /**
     * 红石相组合方块的遮挡形状 / 透光按宿主：组合状态的 {@code getShape} 已改成整格选择框（§3.4），
     * 默认的 {@code getOcclusionShape = getShape} 与"整格 ⇒ 不透光"会让整格挡住邻面渲染、吃掉天光。
     * <p>
     * 这里用组合状态自己的**碰撞形状**（= 宿主的，红石相不加碰撞）代替宿主状态查询：
     * {@code initCache} 在注册时就会跑，那时 {@code COMPOSITE_TO_HOST} 还没填，查表会拿到空气。
     */
    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getOcclusionShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$compositeOcclusion(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            VoxelShape collision = state.getCollisionShape(level, pos);
            cir.setReturnValue(collision == null ? Shapes.empty() : collision);
        }
    }
     *///?} else {
    @Inject(method = "getOcclusionShape", at = @At("HEAD"), cancellable = true)
    private void liquidphase$compositeOcclusion(BlockState state, CallbackInfoReturnable<VoxelShape> cir) {
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            cir.setReturnValue(collision == null ? Shapes.empty() : collision);
        }
    }
    //?}

    // 透光判断：1.19.4/1.20.1 声明在 Block 上（另见 BlockSkylightMixin），1.21.1 起在 BlockBehaviour 上。
    //? if 1.21.1 {
    /*@Inject(method = "propagatesSkylightDown", at = @At("HEAD"), cancellable = true)
    private void liquidphase$compositeSkylight(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            VoxelShape collision = state.getCollisionShape(level, pos);
            cir.setReturnValue((collision == null || !Block.isShapeFullBlock(collision)) && state.getFluidState().isEmpty());
        }
    }
     *///?} else if 1.21.10 || 1.21.11 || 26.3 {
    @Inject(method = "propagatesSkylightDown", at = @At("HEAD"), cancellable = true)
    private void liquidphase$compositeSkylight(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            cir.setReturnValue((collision == null || !Block.isShapeFullBlock(collision)) && state.getFluidState().isEmpty());
        }
    }
    //?}
}
