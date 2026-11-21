package org.liquidphase.mixin;

//? if !(26.3) {

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 红石线连接判定（方案 C）：红石相不占状态，{@code shouldConnectTo(BlockState)} 没有坐标、
 * 无从知道"这一格有没有红石相"，所以改挂在带 {@code Level/BlockPos} 的
 * {@code getConnectingSide} 上。
 * <p>
 * 只有"该格有红石条目且朝向线上的那一面露出"才算连接；功率读取本身走
 * {@code level.getBestNeighborSignal} → 状态级 {@code getSignal}（带坐标），与连接判定独立。
 * 26.3 的等价入口是 {@code BlockStateBase.shouldRedstoneWireConnectTo}（见 BlockStateBaseMixin）。
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {

    @Inject(method = "getConnectingSide(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Lnet/minecraft/world/level/block/state/properties/RedstoneSide;",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$connectToRedstoneHost(BlockGetter level, BlockPos pos, Direction direction, boolean up,
                                                   CallbackInfoReturnable<RedstoneSide> cir) {
        BlockPos neighbourPos = pos.relative(direction);
        BlockState neighbour = level.getBlockState(neighbourPos);
        int extra = PhaseExtras.get(level, neighbourPos);
        if (PhaseExtra.isRedstone(extra) && RedstoneBlocks.exposed(neighbour, direction.getOpposite())) {
            cir.setReturnValue(RedstoneSide.SIDE);
        }
    }
}
//?}
