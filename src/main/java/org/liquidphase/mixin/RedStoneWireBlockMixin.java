package org.liquidphase.mixin;

//? if !(26.3) {

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 红石线连接判定（方案 C）：红石相/元件相不占状态，{@code shouldConnectTo(BlockState)} 没有坐标、
 * 无从知道"这一格有没有相"，所以改挂在带 {@code Level/BlockPos} 的
 * {@code getConnectingSide} 上。
 * <p>
 * 0.2.7 修的两条（E）：
 * <ul>
 *   <li><b>比较器任意非空面</b>：原版 {@code shouldConnectTo} 里比较器没有专门分支，落到
 *       {@code isSignalSource() && direction != null}，即任意面都连；这里按
 *       {@link ElementBlocks#connectsOnFace} 判定（中继器轴向两面、比较器全部、侦测器观察面）。</li>
 *   <li><b>红石相向上爬</b>：原版 {@code canConnectUp} 分支用无方向重载
 *       {@code shouldConnectTo(state above)}，它只认 {@code Blocks.REDSTONE_WIRE}；红石相是
 *       "红石线的等价物"，所以斜上方是红石相（且宿主底面露出）时也要返回 {@link RedstoneSide#UP}。
 *       元件**不**参与这条分支——原版对三种元件都是 false（参见审计 §13.0）。</li>
 * </ul>
 * 只有"该格有相且朝向线上的那一面露出"才算连接；功率读取本身走
 * {@code level.getBestNeighborSignal} → 状态级 {@code getSignal}（带坐标），与连接判定独立。
 * 26.3 的等价入口是 {@code BlockStateBase.shouldRedstoneWireConnectTo}（见 BlockStateBaseMixin）。
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {

    @Inject(method = "getConnectingSide(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Lnet/minecraft/world/level/block/state/properties/RedstoneSide;",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$connectToPhaseHost(BlockGetter level, BlockPos pos, Direction direction, boolean canConnectUp,
                                                CallbackInfoReturnable<RedstoneSide> cir) {
        BlockPos neighbourPos = pos.relative(direction);
        BlockState neighbour = level.getBlockState(neighbourPos);
        int extra = PhaseExtras.get(level, neighbourPos);
        boolean exposed = RedstoneBlocks.exposed(neighbour, direction.getOpposite());
        if (PhaseExtra.isRedstone(extra) && exposed) {
            cir.setReturnValue(RedstoneSide.SIDE);
            return;
        }
        if (PhaseExtra.isElement(extra) && exposed
            && ElementBlocks.connectsOnFace(extra, direction)) {
            cir.setReturnValue(RedstoneSide.SIDE);
            return;
        }
        // 红石相向上爬：原版 canConnectUp 分支只看"斜上方是不是红石线"。
        // 红石相是红石线的等价物，所以斜上方是红石相（且宿主底面露出）时走同一分支。
        // 原版还要求中间那格能承载线（活板门或 canSurviveOn），Sturdy 时给 UP、否则给 SIDE。
        if (canConnectUp) {
            BlockPos abovePos = neighbourPos.above();
            BlockState above = level.getBlockState(abovePos);
            int aboveExtra = PhaseExtras.get(level, abovePos);
            if (PhaseExtra.isRedstone(aboveExtra) && RedstoneBlocks.exposed(above, Direction.DOWN)) {
                boolean supports = neighbour.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                    || neighbour.isFaceSturdy(level, neighbourPos, Direction.UP);
                if (supports) {
                    cir.setReturnValue(neighbour.isFaceSturdy(level, neighbourPos, direction.getOpposite())
                        ? RedstoneSide.UP : RedstoneSide.SIDE);
                }
            }
        }
    }
}
//?}
