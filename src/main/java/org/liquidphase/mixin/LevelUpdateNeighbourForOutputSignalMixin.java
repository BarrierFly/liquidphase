package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code updateNeighbourForOutputSignal} 的**反向桥接**（0.2.7，根因 A1）。
 * <p>
 * 原版这个方法只认 {@code Blocks.COMPARATOR}：容器内容变化（玩家存取、漏斗进出、唱片机换碟、
 * 讲台翻页、展示框转动、命令方块输出、探测铁轨上的矿车……）时，它去通知相邻的比较器。
 * 我们的比较器档位在旁路表里、方块仍是宿主，原版那条 {@code blockState.is(Blocks.COMPARATOR)}
 * 分支永远命不中，于是比较器相完全收不到这类通知。
 * <p>
 * 这里在 {@code TAIL} 补一遍"同一套邻居扫描"（四水平方向 + 隔着**一格导体**再往里看一格，
 * 与原版一致），命中"这一格有比较器条目"时执行与 {@code ComparatorBlock.neighborChanged}
 * 相同的 2 tick 重排（含 TICK 优先级与 {@code willTickThisTick} 守卫）。
 * <p>
 * 与原版的两处细节差异都保持原样：
 * <ul>
 *   <li>原版用 {@code Level.neighborChanged(state, pos, block, orientation, false)}，那会走到
 *       比较器的 {@code neighborChanged} 里；我们等价地直接调
 *       {@link ElementBlocks#neighborChanged}（它自己带 {@code willTickThisTick} 守卫）；</li>
 *   <li>原版这条链**只对四水平方向**（{@code Direction.Plane.HORIZONTAL}）生效，上下不触发。</li>
 * </ul>
 */
@Mixin(Level.class)
public abstract class LevelUpdateNeighbourForOutputSignalMixin {

    @Inject(method = "updateNeighbourForOutputSignal", at = @At("TAIL"))
    private void liquidphase$bridgeComparatorElements(BlockPos pos, net.minecraft.world.level.block.Block changedBlock,
                                                      CallbackInfo ci) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbourPos = pos.relative(direction);
            if (!level.hasChunkAt(neighbourPos)) {
                continue;
            }
            liquidphase$notifyComparatorElement(serverLevel, neighbourPos);
            BlockState neighbour = level.getBlockState(neighbourPos);
            if (neighbour.isRedstoneConductor(level, neighbourPos)) {
                liquidphase$notifyComparatorElement(serverLevel, neighbourPos.relative(direction));
            }
        }
    }

    private static void liquidphase$notifyComparatorElement(ServerLevel level, BlockPos pos) {
        int extra = PhaseExtras.get(level, pos);
        if (PhaseExtra.kind(extra) != PhaseExtra.Kind.COMPARATOR) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        ElementBlocks.neighborChanged(level, pos, state, extra);
    }
}
