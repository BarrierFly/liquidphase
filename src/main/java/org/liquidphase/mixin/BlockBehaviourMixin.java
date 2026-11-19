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
 * 块级形状兜底（null → phase=none 的兄弟状态）。
 * <p>
 * 不少宿主把形状表按"构造时的全部状态"建 Map/Function，追加的 phase 状态查不到会返回
 * null；{@code Cache} 构造时会 NPE。这里在块级把 null 兜回 {@code phase=none} 的兄弟状态。
 * 兜底必须留在块级：{@code Cache.<init>} 直接调块级 {@code getCollisionShape}，状态级钩子管不到它。
 * <p>
 * 固相"整格碰撞"（决策点 D7）**不在**这里：块级注入会被自己覆写 {@code getCollisionShape}
 * 的宿主（栅栏/玻璃板/铁栏杆、墙、墙悬挂告示牌、脚手架、sculk_shrieker、big_dripleaf）
 * 整个遮蔽。它已移到状态级 {@code ShapeFallbackMixin}（见 docs/host-coverage-audit.md §4.4），
 * 对全部宿主一致生效。红石相两种注入都不参与：它用宿主原有形状。
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {

    /** 需要形状兜底的状态：带相且不是 none（红石相也在内，形状表同样可能查不到）。 */
    private static boolean liquidphase$needsFallback(BlockState state) {
        return state.hasProperty(PhaseHelper.FILLING) && state.getValue(PhaseHelper.FILLING) != Phase.NONE;
    }

    private static BlockState liquidphase$sibling(BlockState state) {
        return state.hasProperty(PhaseHelper.FILLING) ? state.setValue(PhaseHelper.FILLING, Phase.NONE) : state;
    }

    @Inject(method = "getCollisionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$collisionNullable(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                               CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getCollisionShape(level, pos, context);
            cir.setReturnValue(fallback == null ? Shapes.empty() : fallback);
        }
    }

    @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$shapeNullable(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                           CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getShape(level, pos, context);
            cir.setReturnValue(fallback == null ? Shapes.empty() : fallback);
        }
    }

    // 块级遮挡形状兜底：initCache 里 occlusionShape 为 null 会让 isSolidRender 崩溃（1.21.1 实测）。
    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getOcclusionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$occlusionNullable(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getOcclusionShape(level, pos);
            cir.setReturnValue(fallback == null ? Shapes.empty() : fallback);
        }
    }
     *///?} else {
    @Inject(method = "getOcclusionShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$occlusionNullable(BlockState state, CallbackInfoReturnable<VoxelShape> cir) {
        if (cir.getReturnValue() == null && liquidphase$needsFallback(state)) {
            VoxelShape fallback = liquidphase$sibling(state).getOcclusionShape();
            cir.setReturnValue(fallback == null ? Shapes.empty() : fallback);
        }
    }
    //?}
}
