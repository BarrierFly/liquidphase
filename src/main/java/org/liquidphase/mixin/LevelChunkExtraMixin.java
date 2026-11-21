package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 旁路表一致性：一格被**换成另一个方块**时清掉它的元件/红石条目。
 * <p>
 * 只比 {@code getBlock()}：同一方块换状态（楼梯形状、含水、我们的 {@code filling}）保留条目——
 * {@code filling} 的写入路径由 {@code PhaseHelper} 自己负责清条目（同格只表达一个相）。
 * <p>
 * 这是"表 ↔ 方块状态"唯一自动清理点，覆盖放置、破坏、活塞推动（元件不随动，见 README 已知限制）、
 * {@code /setblock}、世界生成。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkExtraMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void liquidphase$clearExtraOnBlockChange(BlockPos pos, BlockState state, int flags,
                                                     CallbackInfoReturnable<BlockState> cir) {
        liquidphase$maybeClear(pos, state);
    }
    //?} else {
    /*@Inject(method = "setBlockState", at = @At("HEAD"))
    private void liquidphase$clearExtraOnBlockChange(BlockPos pos, BlockState state, boolean movedByPiston,
                                                     CallbackInfoReturnable<BlockState> cir) {
        liquidphase$maybeClear(pos, state);
    }
     *///?}

    private void liquidphase$maybeClear(BlockPos pos, BlockState state) {
        LevelChunk self = (LevelChunk) (Object) this;
        // 客户端是纯镜像：清理由服务端广播的 extra_update(0) 驱动。客户端在
        // setBlockState 上顺手清表会被区块/方块数据重放按错误顺序抹掉条目。
        if (self.getLevel().isClientSide()) {
            return;
        }
        BlockState previous = self.getBlockState(pos);
        if (previous.getBlock() == state.getBlock()) {
            return;
        }
        // 换成空气时不在这里清：方块移除处理器（onRemove/affectNeighborsAfterRemoval）
        // 还要读旁路表里的红石功率来留下红石线。那边会负责清理。
        if (state.isAir()) {
            return;
        }
        // 换成移塞方块（活塞推动）时同样不清：移除处理器要按"被覆盖"口径把红石相/元件
        // 弹出为物品，先清表会让它读不到条目（0.2.3）。
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            return;
        }
        PhaseExtras.clearAt(self.getLevel(), pos);
    }
}
