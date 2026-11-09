package org.liquidphase.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseTargeting;
import org.liquidphase.sync.PhaseSyncState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 功能三的客户端半边：空桶左键液体 / 满锅时，**不进入本地挖掘进度**，
 * 只发一个 {@code START_DESTROY_BLOCK} 交给服务端权威处理。
 * <p>
 * 不这样做的话客户端会按原版节奏把锅"挖"到底再发 STOP，服务端把锅当普通方块砸掉
 * （历史 bug：取水把锅一起破坏），而且中途还会显示碎裂动画。
 * <p>
 * <b>必须确认服务端支持相</b>（{@link PhaseSyncState#serverSupportsPhases()}）：
 * 连原版服务器时这个分支必须整条不生效，否则客户端发出去的挖掘包会被原版服务端
 * 当成普通挖掘处理（或直接被丢弃），表现为"看到碎裂但什么都没发生"。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ClientLiquidMineMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private net.minecraft.client.multiplayer.ClientPacketListener connection;

    private BlockPos liquidphase$lastMinedPos;
    private BlockState liquidphase$lastMinedState;

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void liquidphase$startLiquidMine(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (liquidphase$mineOnce(pos)) {
            liquidphase$sendStart(pos, direction);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void liquidphase$continueLiquidMine(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (liquidphase$isLiquidTarget(pos)) {
            // 重复按住的后续 tick 不再发包、也不再累积本地挖掘进度。
            if (liquidphase$mineOnce(pos)) {
                liquidphase$sendStart(pos, direction);
            }
            cir.setReturnValue(true);
        }
    }

    private boolean liquidphase$mineOnce(BlockPos pos) {
        if (!liquidphase$isLiquidTarget(pos)) {
            return false;
        }
        BlockState state = this.minecraft.level.getBlockState(pos);
        if (pos.equals(this.liquidphase$lastMinedPos) && state == this.liquidphase$lastMinedState) {
            return false;
        }
        this.liquidphase$lastMinedPos = pos.immutable();
        this.liquidphase$lastMinedState = state;
        return true;
    }

    private boolean liquidphase$isLiquidTarget(BlockPos pos) {
        if (!LiquidPhaseConfig.get().liquidInteraction || this.minecraft.player == null) {
            return false;
        }
        // 服务端没握手前（单人登录中 / 连原版服务器）一律不接管，避免客户端单方面改行为。
        if (!PhaseSyncState.serverSupportsPhases()) {
            return false;
        }
        if (!PhaseTargeting.holdingEmptyBucket(this.minecraft.player)) {
            return false;
        }
        return PhaseTargeting.isLiquidMineTarget(this.minecraft.level, pos);
    }

    private void liquidphase$sendStart(BlockPos pos, Direction direction) {
        this.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, 0));
    }
}
