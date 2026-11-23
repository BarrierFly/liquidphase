package org.liquidphase.mixin;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 相材料放置的**改写层**：任何来源（{@code Level.setBlock} 是所有方块写入的唯一汇聚点——
 * 玩家放置、发射器、命令、下落方块、任意 mod）向已有相容宿主格写入相材料方块
 * （冰/霜冰/黑曜石/红石线/真实中继器·比较器·侦测器）时，改写为"宿主 + 对应填充相"，
 * 而不是用材料覆盖宿主。
 * <p>
 * <b>0.2.6：判定与写入统走 {@link PhaseAbsorption#cellOf} /
 * {@link PhaseAbsorption#canAbsorbCell} / {@link PhaseAbsorption#absorbCell}。</b>
 * 以前这里对红石线与元件各写一份分支，于是"新增一种填充相"必须在四个 mixin 各补一遍，
 * 漏掉一处就少一条组装路径——用户反馈的"冰/黑曜石好了但中继器/比较器/侦测器填充不全"
 * 正是这个结构问题的表现。
 * <p>
 * 水/熔岩**不在**材料口径：它们走流体容器路径（{@code LiquidBlockContainer.placeLiquid}，
 * 熔岩相由那里处理；{@code Level.setBlock(熔岩源)} 是"破坏并铺流体"的原版语义，不改写）。
 * 改写后的目标仍是宿主状态（非材料），再次进入本注入点不会递归。
 * <p>
 * 红石相的初始功率与重算口径（0.1.22）：改写层写入线状态属于"重新放置红石粉"，
 * 初始功率 0；宿主落进线格属于"吸收已存在的线"，初始功率沿用原线
 * （{@code BlockItemMixin} / {@code FallingBlockIntoWireMixin}）。
 * 两条路径落地后都由 {@code BlockStateBaseMixin.liquidphase$onPlace} 立即按邻居重算。
 * <p>
 * 版本差异：≤1.21.11 的 {@code Level} 声明 3 参 {@code setBlock(pos, state, flags)}
 * （委托 4 参）；26.3 只有 4 参 {@code setBlock(pos, state, flags, limit)}。
 */
@Mixin(Level.class)
public abstract class FillingPlacementMixin {

    //? if 26.3 {
    /*@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$fillingPlacement(BlockPos pos, BlockState newState, int flags, int limit,
                                              CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return;
        }
        Boolean handled = liquidphase$rewrite(level, pos, newState, state -> level.setBlock(pos, state, flags, limit));
        if (handled != null) {
            cir.setReturnValue(handled);
        }
    }
     *///?} else {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$fillingPlacement(BlockPos pos, BlockState newState, int flags,
                                              CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return;
        }
        Boolean handled = liquidphase$rewrite(level, pos, newState, state -> level.setBlock(pos, state, flags));
        if (handled != null) {
            cir.setReturnValue(handled);
        }
    }
    //?}

    /**
     * 共用的改写主体（两个版本的注入体只差在"怎么写回状态"）。
     * <ol>
     *   <li>其它方块（含石头、水、熔岩源本身）→ {@code null}（走原版覆盖）；</li>
     *   <li>材料相写进宿主格 → 用 {@code writeState} 写成"宿主 + 该填充相"；</li>
     *   <li>红石线 / 真实元件方块写进宿主格 → 只写旁路表，方块状态不变，返回 {@code true}。</li>
     * </ol>
     * 三种都问同一个 {@link PhaseAbsorption#cellOf}：不再出现"某条路径只认某几种填充"。
     *
     * @return {@code null} = 不接管；否则是本次 {@code setBlock} 的返回值
     */
    private static Boolean liquidphase$rewrite(Level level, BlockPos pos, BlockState newState,
                                               Function<BlockState, Boolean> writeState) {
        PhaseAbsorption.Cell cell = PhaseAbsorption.cellOf(newState);
        if (cell == null || cell.isLava()) {
            // 熔岩源不是"填充相材料"：原版 setBlock(熔岩) 是"破坏并铺流体"的语义，不改写。
            return null;
        }
        BlockState current = level.getBlockState(pos);
        if (!(current.getBlock() instanceof PhaseCapable)) {
            return null;
        }
        if (!PhaseAbsorption.canAbsorbCell(level, pos, current, cell)) {
            return null;
        }
        if (cell.isMaterial()) {
            // 改写为"宿主 + 填充相"：一次 setBlock 到位（目标不是材料格，不会递归）。
            return writeState.apply(PhaseHelper.withPhase(current, cell.filling()));
        }
        // 红石线按"新放置"口径（初始 0，落地由 onPlace 按邻居重算；来料状态自带的 POWER
        // 不作为初始状态，见 AGENTS §3.3）；元件方块继承它自己的状态。两条都只写旁路表。
        if (!PhaseAbsorption.absorbCell(level, pos, current, cell, -1)) {
            return null;
        }
        level.updateNeighborsAt(pos, current.getBlock());
        return true;
    }
}
