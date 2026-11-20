package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.redstone.RedstoneBlocks;
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
 * （写入宿主格 = 重新放置红石粉：初始功率 0，落地即按邻居重算——与单独放置一致）。
 * 水/熔岩**不在**材料口径——它们走流体容器路径
 * （{@code LiquidBlockContainer.placeLiquid}，含水/熔岩相由那里处理）。
 * 改写后的目标仍是宿主状态（非材料），再次进入本注入点不会递归。
 * <p>
 * 0.1.22 起本注入点还负责**吸收抑制窗口**：相容宿主状态落进红石线格
 * （{@code BlockItemMixin.placeBlock} / 下落方块改写后的那次 {@code setBlock}）=
 * 吸收已存在的红石线，功率沿用原线、落地**不重算**——原版红石线移除钩子
 * {@code affectNeighborsAfterRemoval} 会无条件向四周一圈发邻居更新，通知环经邻近方块
 * 折回本格，若不抑制会把继承的功率立刻重算掉。开窗包住那次 setBlock，窗口内本格的
 * 红石重算入口（{@code RedstoneBlocks.neighborChanged}）直接跳过；级联在深度 0 同步
 * 排空（{@code CollectingNeighborUpdater.addAndRun}），finally 关窗可靠。窗口结束后
 * 邻居变化照常重算。
 * <p>
 * 版本差异：≤1.21.11 的 {@code Level} 声明 3 参 {@code setBlock(pos, state, flags)}
 * （委托 4 参，limit 512）；26.3 只有 4 参 {@code setBlock(pos, state, flags, limit)}。
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
        if (RedstoneBlocks.isAbsorptionSuppressed(pos)) {
            return;
        }
        if (liquidphase$isAbsorption(newState, level, pos)) {
            cir.setReturnValue(liquidphase$runAbsorbing(level, pos, newState, flags, limit));
            return;
        }
        BlockState converted = liquidphase$convert(level, pos, newState);
        if (converted != null) {
            cir.setReturnValue(liquidphase$runConverted(level, pos, converted, flags, limit));
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
        if (RedstoneBlocks.isAbsorptionSuppressed(pos)) {
            return;
        }
        if (liquidphase$isAbsorption(newState, level, pos)) {
            cir.setReturnValue(liquidphase$runAbsorbing(level, pos, newState, flags, 512));
            return;
        }
        BlockState converted = liquidphase$convert(level, pos, newState);
        if (converted != null) {
            cir.setReturnValue(liquidphase$runConverted(level, pos, converted, flags, 512));
        }
    }
    //?}

    /** {@return 这次 setBlock 是否为"宿主（带红石相）落进红石线格"的吸收} */
    private static boolean liquidphase$isAbsorption(BlockState newState, Level level, BlockPos pos) {
        if (!newState.hasProperty(PhaseHelper.FILLING)
            || !PhaseHelper.getPhase(newState).isRedstone()) {
            return false;
        }
        return level.getBlockState(pos).is(Blocks.REDSTONE_WIRE);
    }

    /** 包住吸收 setBlock 的抑制窗口（级联同步排空，finally 关窗）。 */
    private boolean liquidphase$runAbsorbing(Level level, BlockPos pos, BlockState newState, int flags, int limit) {
        RedstoneBlocks.beginAbsorptionSuppression(pos);
        try {
            //? if 26.3 {
            /*return level.setBlock(pos, newState, flags, limit);
             *///?} else {
            return level.setBlock(pos, newState, flags);
            //?}
        } finally {
            RedstoneBlocks.endAbsorptionSuppression(pos);
        }
    }

    /** 改写层写入 + 新放置的红石相落地即按邻居重算（等价原版红石线 onPlace 的结算）。 */
    private boolean liquidphase$runConverted(Level level, BlockPos pos, BlockState converted, int flags, int limit) {
        boolean ok;
        //? if 26.3 {
        /*ok = level.setBlock(pos, converted, flags, limit);
         *///?} else {
        ok = level.setBlock(pos, converted, flags);
        //?}
        if (ok && level instanceof ServerLevel serverLevel) {
            BlockState landed = serverLevel.getBlockState(pos);
            if (RedstoneBlocks.isRedstonePhase(landed)) {
                RedstoneBlocks.updatePower(serverLevel, pos, landed);
            }
        }
        return ok;
    }

    /** {@return 改写后的"宿主 + 填充相"状态；非填充相材料或目标不是可接受宿主时返回 null} */
    private static BlockState liquidphase$convert(Level level, BlockPos pos, BlockState newState) {
        Phase filling = liquidphase$fillingOf(newState);
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

    /** {@return 向某状态写入的填充相；非填充相材料返回 null} */
    private static Phase liquidphase$fillingOf(BlockState state) {
        // 红石线**写入宿主格**是"重新放置红石粉"（该格原本没有线）：初始功率 0，
        // 与单独放置一致，写入后立即按邻居重算出真实功率；来料状态自带的
        // POWER 只反映写入者的意图，不作为初始状态。
        // 对比：宿主**落进线格**（吸收已存在的线）才是沿用原线功率、落地不重算，
        // 走上面的吸收抑制窗口，不经过本口径。
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return Phase.REDSTONE_0;
        }
        return PhaseAbsorption.phaseOfBlock(state);
    }
}
