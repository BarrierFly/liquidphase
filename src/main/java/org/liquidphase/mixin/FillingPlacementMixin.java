package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 填充相放置的**改写层**（audit v2 用户实测缺口，0.1.16 修）：任何来源
 * （{@code Level.setBlock} 是所有方块写入的唯一汇聚点——玩家放置、发射器、命令、
 * 下落方块、任意 mod）向相容宿主格写入填充相材料方块时，改写为"宿主 + 对应填充相"，
 * 而不是用材料覆盖宿主。0.1.17 起接受条件就是 {@code PhaseHelper.canAcceptPhase}
 * （可替换填充相规则）：无相（含含水态）或含熔岩相的宿主格会被改写吸收；
 * 冰/霜冰/黑曜石/红石相不可替换，材料照原版覆盖。
 * <p>
 * 材料口径：冰/霜冰/黑曜石（{@code PhaseAbsorption.phaseOfBlock}）与红石线
 * （写入宿主格 = 重新放置红石粉：初始功率 0）。水/熔岩**不在**材料口径——
 * 它们走流体容器路径（{@code LiquidBlockContainer.placeLiquid}，含水/熔岩相由那里处理）。
 * 改写后的目标仍是宿主状态（非材料），再次进入本注入点不会递归。
 * <p>
 * 红石相的初始功率与重算口径（0.1.22）：改写层写入线状态属于"重新放置红石粉"，
 * 初始功率 0；宿主落进线格属于"吸收已存在的线"，初始功率沿用原线
 * （{@code BlockItemMixin.liquidphase$redstonePhase} / {@code FallingBlockIntoWireMixin}）。
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
        BlockState converted = liquidphase$convert(level, pos, newState);
        if (converted != null) {
            cir.setReturnValue(level.setBlock(pos, converted, flags, limit));
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
        BlockState converted = liquidphase$convert(level, pos, newState);
        if (converted != null) {
            cir.setReturnValue(level.setBlock(pos, converted, flags));
            return;
        }
        // 红石线写进已有的相容宿主格：该格原本没有线（重新放置口径），初始功率 0；
        // 落地后由 onPlace 立即按邻居重算。方块状态不变，只写旁路表。
        if (newState.is(Blocks.REDSTONE_WIRE)) {
            BlockState current = level.getBlockState(pos);
            if (current.getBlock() instanceof PhaseCapable && PhaseHelper.canAcceptExtra(level, pos, current)) {
                org.liquidphase.redstone.RedstoneBlocks.setRedstonePhase(level, pos, current);
                level.updateNeighborsAt(pos, current.getBlock());
                cir.setReturnValue(true);
            }
        }
    }
    //?}

    /** {@return 改写后的"宿主 + 填充相"状态；非填充相材料或目标不是可接受宿主时返回 null} */
    private static BlockState liquidphase$convert(Level level, BlockPos pos, BlockState newState) {
        if (newState.is(Blocks.REDSTONE_WIRE)) {
            return null; // 红石相不占状态，在调用点直接写旁路表。
        }
        Phase filling = PhaseAbsorption.phaseOfBlock(newState);
        if (filling == null) {
            return null;
        }
        BlockState current = level.getBlockState(pos);
        if (!(current.getBlock() instanceof PhaseCapable)
            || !PhaseHelper.canAcceptPhase(current, level, pos, filling)) {
            return null;
        }
        return PhaseHelper.withPhase(current, filling);
    }
}
