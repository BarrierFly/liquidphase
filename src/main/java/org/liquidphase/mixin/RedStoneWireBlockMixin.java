package org.liquidphase.mixin;

//? if !(26.3) {

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 红石线连接判定（§3.4）：组合方块是信号源，原版默认"任何信号源都连"会让线连到被覆盖的面——
 * 收窄为"露出的面才连"。26.3 的钩子在 BSB.shouldRedstoneWireConnectTo（见 BlockStateBaseConnectMixin）。
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {

    @Inject(method = "shouldConnectTo(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void liquidphase$shouldConnectTo(BlockState state, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (RedstoneBlocks.isRedstonePhase(state)) {
            cir.setReturnValue(direction == null || RedstoneBlocks.exposed(state, direction.getOpposite()));
        }
    }
}
//?}
