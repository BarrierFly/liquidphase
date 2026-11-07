package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.19.4/1.20.1 的 {@code propagatesSkylightDown} 声明在 {@link Block} 上
 * （1.21.1+ 挪到 {@code BlockBehaviour}，见 {@link BlockBehaviourMixin}）。
 * <p>
 * 红石相组合方块的选择框改成了整格（§3.4 交互箱），父类默认的"整格形状 ⇒ 不透光"
 * 会让方块下方少一格天光；这里用组合状态自己的碰撞形状（= 宿主的）回答。
 * 1.21.1+ 的 {@code Block} 没有这个方法 → {@code require = 0} 让注入目标缺失时静默跳过。
 */
@Mixin(Block.class)
public abstract class BlockSkylightMixin {

    @Inject(method = "propagatesSkylightDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void liquidphase$compositeSkylight(BlockState state, BlockGetter level, BlockPos pos,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            VoxelShape collision = state.getCollisionShape(level, pos);
            cir.setReturnValue((collision == null || !Block.isShapeFullBlock(collision)) && state.getFluidState().isEmpty());
        }
    }
}
