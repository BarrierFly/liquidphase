package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 红石相（§3.4）的信号输出：含红石相的宿主方块（{@code phase = redstone_0…15}）的
 * {@code getSignal} / {@code getDirectSignal} / {@code isSignalSource}
 * 全部委托到 {@link RedstoneBlocks}。
 * <p>
 * 红石相现在是宿主方块上的一个 phase 取值，不再是独立方块，所以只能注入
 * 状态级基类统一分发（宿主自己的覆写会遮蔽块级注入）。
 */
@Mixin(net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class)
public abstract class RedstoneSignalMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void liquidphase$redstoneSignal(BlockGetter level, BlockPos pos, Direction direction,
                                            CallbackInfoReturnable<Integer> cir) {
        BlockState self = (BlockState) (Object) this;
        if (RedstoneBlocks.isRedstonePhase(self)) {
            cir.setReturnValue(RedstoneBlocks.getSignal(self, level, pos, direction));
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void liquidphase$redstoneDirectSignal(BlockGetter level, BlockPos pos, Direction direction,
                                                  CallbackInfoReturnable<Integer> cir) {
        BlockState self = (BlockState) (Object) this;
        if (RedstoneBlocks.isRedstonePhase(self)) {
            cir.setReturnValue(RedstoneBlocks.getDirectSignal(self, level, pos, direction));
        }
    }

    @Inject(method = "isSignalSource", at = @At("HEAD"), cancellable = true)
    private void liquidphase$redstoneSignalSource(CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        if (RedstoneBlocks.isRedstonePhase(self)) {
            cir.setReturnValue(true);
        }
    }
}
