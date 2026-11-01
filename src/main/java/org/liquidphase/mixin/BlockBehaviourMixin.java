package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
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
}
