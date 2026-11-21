package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseTargeting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 功能三（服务端权威）+ 决策点 D8：
 * <ul>
 *   <li>左键"挖掘液体"：手持空桶对真实水/熔岩源或熔岩相宿主 → 移除并掉落液体物品
 *       （客户端经流体射线指到液体后按瞬挖流程发包，服务端在此收口）。</li>
 *   <li>直接挖相：破坏完成（destroyBlock）时若瞄准的是固相区域 → 只移除相、掉落相材料，
 *       宿主保留；挖掘速度已由 BlockStateBaseMixin 的 getDestroyProgress 按伪状态同步。</li>
 * </ul>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    public ServerPlayer player;

    /** 目标类里的实际字段名是 level（没有 getLevel() 方法）。 */
    @Shadow
    private net.minecraft.server.level.ServerLevel level;

    private Level liquidphase$level() {
        return this.level;
    }

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void liquidphase$mineLiquid(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction direction, int maxY, int sequence, CallbackInfo ci) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
            || !LiquidPhaseConfig.get().liquidInteraction
            || !PhaseTargeting.holdingEmptyBucket(this.player)) {
            return;
        }
        Level level = this.liquidphase$level();
        if (!PhaseTargeting.withinRange(this.player, pos) || !PhaseTargeting.isLiquidMineTarget(level, pos)) {
            return;
        }
        PhaseTargeting.mineLiquid(this.player, level, pos);
        // 同步该格新状态给客户端（锅还在，只是内部液体被取走）。
        // 注意：不能用 destroyAndAck —— 它会再调一次 destroyBlock，把刚恢复的空锅连锅砸掉。
        // 序列应答由 ServerGamePacketListenerImpl.handlePlayerAction 在返回后统一处理（ackBlockChangesUpTo）。
        this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, pos));
        level.destroyBlockProgress(this.player.getId(), pos, -1);
        ci.cancel();
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void liquidphase$minePhase(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level level = this.liquidphase$level();
        BlockState state = level.getBlockState(pos);
        boolean element = PhaseTargeting.isElementMineable(level, pos);
        if (!element && !PhaseTargeting.isPhaseMineable(level, pos, state)) {
            return;
        }
        if (!element && !PhaseTargeting.isPhaseTarget(this.player, level, pos)) {
            return;
        }
        PhaseTargeting.minePhase(this.player, level, pos, state);
        cir.setReturnValue(true);
    }
}
