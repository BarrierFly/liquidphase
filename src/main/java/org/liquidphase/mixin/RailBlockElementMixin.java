package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RailState;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 铁轨朝向更新的 {@code isSignalSource()} 缺口（0.2.7，F 的 E-2）。
 * <p>
 * 原版 {@code RailBlock.updateState} 的条件是
 * {@code block.defaultBlockState().isSignalSource() && new RailState(...).countPotentialConnections() == 3}：
 * 旁边被破坏/变化的那个方块是信号源、且当前铁轨恰好有 3 个潜在连接时才改朝向。
 * 元件宿主不是信号源，所以这一条永远不成立。
 * <p>
 * 这里在 {@code RailBlock.updateState} 的 HEAD 补一个旁路分支，**两个条件都按原版**：
 * <ol>
 *   <li>"变化的那一格是元件宿主"：{@code updateState} 只给方块类型、不给坐标，所以按
 *       "六邻里有 {@code getBlock() == changedBlock} 且带元件条目的格子"判定（原版也只关心
 *       "变化的那个方块"，不看它具体在哪）；</li>
 *   <li>{@code countPotentialConnections() == 3}。</li>
 * </ol>
 * 两条同时成立时执行与原版 {@code updateDir(level, pos, state, false)} 完全相同的动作
 * （{@code RailState.place} 自己会在需要时 {@code setBlock}）。
 */
@Mixin(RailBlock.class)
public abstract class RailBlockElementMixin {

    @Inject(method = "updateState", at = @At("HEAD"), cancellable = true)
    private void liquidphase$railBesideElement(BlockState state, Level level, BlockPos pos,
                                               net.minecraft.world.level.block.Block changedBlock, CallbackInfo ci) {
        if (changedBlock.defaultBlockState().isSignalSource()) {
            // 原版分支本来就会成立，交给原逻辑。
            return;
        }
        if (!liquidphase$changedNeighbourIsElement(level, pos, changedBlock)) {
            return;
        }
        if (liquidphase$countPotentialConnections(level, pos) == 3) {
            // 等价于 RailBlock#updateDir(level, pos, state, false)：RailState.place 内部自己会
            // 在需要时 setBlock，这里不需要再用返回值。
            new RailState(level, pos, state).place(level.hasNeighborSignal(pos), false,
                state.getValue(RailBlock.SHAPE));
            ci.cancel();
        }
    }

    /**
     * {@code RailState#countPotentialConnections} 是 protected，这里按同一算式复刻：
     * 四水平方向上有铁轨就算一个潜在连接。
     */
    private static int liquidphase$countPotentialConnections(Level level, BlockPos pos) {
        int count = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (net.minecraft.world.level.block.BaseRailBlock.isRail(level, pos.relative(direction))) {
                count++;
            }
        }
        return count;
    }
    /** {@return 六邻里是否存在"方块类型 = 变化方块 且带元件条目"的格子} */
    private static boolean liquidphase$changedNeighbourIsElement(Level level, BlockPos pos,
                                                                 net.minecraft.world.level.block.Block changedBlock) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (level.getBlockState(neighbour).getBlock() != changedBlock) {
                continue;
            }
            int extra = PhaseExtras.get(level, neighbour);
            if (PhaseExtra.isElement(extra)) {
                return true;
            }
        }
        return false;
    }
}
