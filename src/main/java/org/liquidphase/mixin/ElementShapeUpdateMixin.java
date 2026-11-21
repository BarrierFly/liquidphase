package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 元件相的状态级 {@code updateShape}（方案 C）：状态里看不出有没有元件，所以每个
 * 宿主状态都要问一次旁路表。
 * <ul>
 *   <li>侦测器：观察面（{@code facing}）邻居变化 → 排 2 tick 计划刻；</li>
 *   <li>中继器：侧向邻居变化 → 重算锁存（{@code locked}）。</li>
 * </ul>
 * 注入点选状态级分发点（{@code BlockBehaviour.BlockStateBase}），宿主自己的覆写不会遮蔽。
 * 两个 API 时代的签名不同（≤1.21.1 六参 / 1.21.10+ 八参），用 stonecutter 分支。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ElementShapeUpdateMixin {

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "updateShape", at = @At("HEAD"))
    private void liquidphase$updateShape(Direction direction, BlockState neighbourState,
                                         LevelAccessor level, BlockPos pos, BlockPos neighbourPos,
                                         CallbackInfoReturnable<BlockState> cir) {
        liquidphase$onShapeChange(level, pos, direction);
    }
     *///?} else {
    @Inject(method = "updateShape", at = @At("HEAD"))
    private void liquidphase$updateShape(net.minecraft.world.level.LevelReader level,
                                         net.minecraft.world.level.ScheduledTickAccess ticks, BlockPos pos,
                                         Direction direction, BlockPos neighbourPos, BlockState neighbourState,
                                         net.minecraft.util.RandomSource random, CallbackInfoReturnable<BlockState> cir) {
        if (level instanceof LevelAccessor accessor) {
            liquidphase$onShapeChange(accessor, pos, direction);
        }
    }
    //?}

    private void liquidphase$onShapeChange(LevelAccessor level, BlockPos pos, Direction direction) {
        BlockState self = (BlockState) (Object) this;
        int extra = PhaseExtras.get(level, pos);
        if (PhaseExtra.isElement(extra)) {
            ElementBlocks.onUpdateShape(level, pos, self, extra, direction);
        }
    }
}
