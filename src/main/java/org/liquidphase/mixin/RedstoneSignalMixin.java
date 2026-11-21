package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 信号输出（方案 C）：红石相与元件相都只存在于旁路表里，按坐标读取。
 * <p>
 * 只挂带坐标的 {@code getSignal} / {@code getDirectSignal}。{@code isSignalSource()}
 * 没有坐标，**不改**——红石线连接判定改由 {@code RedStoneWireBlockMixin} 在
 * {@code getConnectingSide}（带 Level/BlockPos）里处理。
 */
@Mixin(net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class)
public abstract class RedstoneSignalMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void liquidphase$signal(BlockGetter level, BlockPos pos, Direction direction,
                                    CallbackInfoReturnable<Integer> cir) {
        BlockState self = (BlockState) (Object) this;
        int extra = PhaseExtras.get(level, pos);
        if (PhaseExtra.isEmpty(extra)) {
            return;
        }
        if (PhaseExtra.isRedstone(extra)) {
            cir.setReturnValue(RedstoneBlocks.getSignal(self, level, pos, direction));
        } else if (PhaseExtra.isElement(extra)) {
            cir.setReturnValue(ElementBlocks.getSignal(self, level, pos, direction, extra));
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void liquidphase$directSignal(BlockGetter level, BlockPos pos, Direction direction,
                                          CallbackInfoReturnable<Integer> cir) {
        BlockState self = (BlockState) (Object) this;
        int extra = PhaseExtras.get(level, pos);
        if (PhaseExtra.isEmpty(extra)) {
            return;
        }
        if (PhaseExtra.isRedstone(extra)) {
            cir.setReturnValue(RedstoneBlocks.getDirectSignal(self, level, pos, direction));
        } else if (PhaseExtra.isElement(extra)) {
            cir.setReturnValue(ElementBlocks.getDirectSignal(self, level, pos, direction, extra));
        }
    }
}
