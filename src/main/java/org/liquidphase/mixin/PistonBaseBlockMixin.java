package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PistonPhaseOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 活塞移动的"相"接线（0.2.3）：
 * <ul>
 *   <li><b>破坏列表标记</b>：{@code moveBlocks} 的 toDestroy 循环里被摧毁方块的移除钩子
 *       （≤1.21.1 是 {@code setBlock(AIR, 18)} 触发的 {@code onRemove}；1.21.10+ 是循环末尾
 *       显式的 {@code affectNeighborsAfterRemoval(..., false)}）与普通破坏走同一签名，
 *       无法靠参数区分。这里把该段包进 {@link PistonPhaseOps#runAsDestroyList}，
 *       让 {@code BlockStateBaseMixin.liquidphase$removed} 按"弹出物品"而非"破坏保留"处理。</li>
 *   <li><b>移动状态剥相</b>：所有被推/拉走的方块实体携带的状态剥掉材料填充相——
 *       相不随活塞移动：被覆盖的原格弹出物品、被清空的原格实体化留在原位
 *       （{@code BlockStateBaseMixin} 按 movedByPiston 与原格内容分发）。含水标志保留。</li>
 * </ul>
 * 推不动由 {@code BlockStateBaseMixin.getPistonPushReaction}（黑曜石相 → BLOCK）覆盖，
 * 无需改这里——解析器对 BLOCK 反应的方块直接让整个移动失败。
 * <p>
 * <b>require</b>：两个 {@code newMovingBlockEntity} 调用点（推入方块 + 活塞头）全版本一致；
 * ≤1.21.1 的 {@code setBlock} 在 moveBlocks 里恰好 5 个调用点（1.19.4/1.20.1/1.21.1 实测一致，
 * 按 flags==18 分派破坏列表那次）；1.21.10+/26.3 的显式 affect 调用点恰好 1 个。
 * 数量对不上时启动即失败，不静默退回。
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Redirect(method = "moveBlocks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;affectNeighborsAfterRemoval(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Z)V"),
        require = 1)
    private void liquidphase$destroyListAffect(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        PistonPhaseOps.runAsDestroyList(() -> state.affectNeighborsAfterRemoval(level, pos, moved));
    }
    //?} else {
    /*@Redirect(method = "moveBlocks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        require = 5)
    private boolean liquidphase$markDestroyListSetBlock(Level level, BlockPos pos, BlockState state, int flags) {
        if (flags != 18) {
            return level.setBlock(pos, state, flags);
        }
        boolean[] result = new boolean[1];
        PistonPhaseOps.runAsDestroyList(() -> result[0] = level.setBlock(pos, state, flags));
        return result[0];
    }
     *///?}

    @Redirect(method = "moveBlocks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/piston/MovingPistonBlock;newMovingBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;ZZ)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
        require = 2)
    private net.minecraft.world.level.block.entity.BlockEntity liquidphase$stripCarriedPhase(BlockPos pos, BlockState mpState, BlockState carried,
                                                                  Direction direction, boolean extending, boolean source) {
        return MovingPistonBlock.newMovingBlockEntity(pos, mpState,
            PistonPhaseOps.stripCarriedPhase(carried), direction, extending, source);
    }
}
