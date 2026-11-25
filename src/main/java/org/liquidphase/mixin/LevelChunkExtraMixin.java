package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 旁路表一致性：一格被**换成另一个方块**时的兜底清理（0.2.7 改成"移除钩子优先"）。
 * <p>
 * 正常路径是方块移除钩子（{@code onRemove} / {@code affectNeighborsAfterRemoval}）读条目、
 * 按移除原因决定实体化/掉落，再清表——所以这里**不能**在 {@code setBlockState} HEAD 提前清掉，
 * 否则移除钩子读不到条目（"吸收/破坏保留/掉落"全部失效）。
 * <p>
 * 这里改成：HEAD 只记录旧条目，RETURN 时若旧条目**仍在**（说明移除钩子这次没有执行，例如
 * 某些不带 UPDATE_NEIGHBORS 的写入路径），才按"被替换"口径补一次掉落 + 清表。
 * 从空气换成方块（装配/放置）不受影响：那时候本来就没有条目。
 * <p>
 * 只比 {@code getBlock()}：同一方块换状态（楼梯形状、含水、我们的 {@code filling}）保留条目——
 * {@code filling} 的写入路径由 {@code PhaseHelper} 自己负责清条目（同格只表达一个相）。
 * 客户端是纯镜像：清理由服务端广播的 {@code extra_update(0)} 驱动。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkExtraMixin {

    @Unique
    private int liquidphase$oldEntry;

    @Unique
    private boolean liquidphase$pendingSweep;

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void liquidphase$captureEntry(BlockPos pos, BlockState state, int flags,
                                          CallbackInfoReturnable<BlockState> cir) {
        liquidphase$capture(pos, state);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void liquidphase$sweepEntry(BlockPos pos, BlockState state, int flags,
                                       CallbackInfoReturnable<BlockState> cir) {
        liquidphase$sweep(pos);
    }
    //?} else {
    /*@Inject(method = "setBlockState", at = @At("HEAD"))
    private void liquidphase$captureEntry(BlockPos pos, BlockState state, boolean movedByPiston,
                                          CallbackInfoReturnable<BlockState> cir) {
        liquidphase$capture(pos, state);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void liquidphase$sweepEntry(BlockPos pos, BlockState state, boolean movedByPiston,
                                       CallbackInfoReturnable<BlockState> cir) {
        liquidphase$sweep(pos);
    }
     *///?}

    @Unique
    private void liquidphase$capture(BlockPos pos, BlockState state) {
        LevelChunk self = (LevelChunk) (Object) this;
        this.liquidphase$pendingSweep = false;
        this.liquidphase$oldEntry = PhaseExtra.EMPTY;
        if (self.getLevel().isClientSide()) {
            return;
        }
        BlockState previous = self.getBlockState(pos);
        if (previous.getBlock() != state.getBlock()
            && previous.getBlock() instanceof net.minecraft.world.level.block.ComparatorBlock
            && self.getLevel().getBlockEntity(pos)
                instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity entity) {
            // 比较器即将被替换：趁方块实体还在，把 OutputSignal 记下来给吸收路径用（根因 C）。
            org.liquidphase.phase.ElementSignals.capture(pos, entity.getOutputSignal());
        }
        if (previous.getBlock() == state.getBlock()) {
            return;
        }
        int entry = PhaseExtras.get(self.getLevel(), pos);
        if (entry == PhaseExtra.EMPTY) {
            return;
        }
        this.liquidphase$oldEntry = entry;
        this.liquidphase$pendingSweep = true;
    }

    @Unique
    private void liquidphase$sweep(BlockPos pos) {
        if (!this.liquidphase$pendingSweep) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        int current = PhaseExtras.get(self.getLevel(), pos);
        if (current != this.liquidphase$oldEntry) {
            // 移除钩子已经消费并清掉了条目（正常路径）。
            this.liquidphase$pendingSweep = false;
            return;
        }
        // 移除钩子这次没跑：按"被替换"口径（J-1）掉落后清表。
        Level level = self.getLevel();
        net.minecraft.world.item.ItemStack drop = org.liquidphase.phase.PhaseDrops.droppedItem(
            org.liquidphase.phase.PhaseHelper.getPhase(self.getBlockState(pos)), this.liquidphase$oldEntry);
        if (!drop.isEmpty()) {
            net.minecraft.world.level.block.Block.popResource(level, pos, drop);
        }
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY, PhaseExtras.Notify.INSIDE_BLOCK_WRITE);
        this.liquidphase$pendingSweep = false;
    }
}
