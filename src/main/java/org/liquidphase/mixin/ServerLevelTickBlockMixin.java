package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 元件计划刻的**执行点**（0.2.7，根因 B）。
 * <p>
 * 元件的计划刻用原版元件方块本身当 tick key（{@code REPEATER}/{@code COMPARATOR}/{@code OBSERVER}），
 * 由 {@code ScheduledTickAccess.scheduleTick(pos, block, ...)} 排进原版容器。原版
 * {@code ServerLevel.tickBlock} 只判 {@code blockState.is(type)} 就调 {@code blockState.tick(...)}，
 * 而本 mod 的元件不占方块（宿主从来不是元件方块），所以那条路径永远不会执行到我们这里。
 * <p>
 * 注入点选在 {@code tickBlock} 的 HEAD：**先**看这一格有没有与该 tick 类型匹配的元件条目，
 * 有就执行元件刻并取消原版调用；没有就原样放行。
 * <ul>
 *   <li>宿主自己的计划刻用 {@code (pos, hostBlock)} 槽位，与元件槽位互不影响；</li>
 *   <li>原版元件方块（真方块）排下的刻在"该格已经是元件相"之后也会走这里——吸收真实元件时
 *       正是靠这一点让"填充形态直接照常继承"（正在脉冲的侦测器会在原定时刻复位）；</li>
 *   <li>没有匹配条目的刻（例如真元件方块自己排的刻而那一格还没被吸收）原样放行给原版。</li>
 * </ul>
 * 六版本的 {@code tickBlock} 都是私有、同名同描述符 {@code (BlockPos, Block)V}。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickBlockMixin {

    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void liquidphase$tickElement(BlockPos pos, net.minecraft.world.level.block.Block type, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        int extra = PhaseExtras.get(level, pos);
        if (!PhaseExtra.isElement(extra)) {
            return;
        }
        net.minecraft.world.level.block.Block key = org.liquidphase.element.ElementBlocks.tickKey(extra);
        if (key == null || key != type) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            // 条目还在但方块没了：清掉，避免僵尸刻。
            PhaseExtras.set(level, pos, PhaseExtra.EMPTY, PhaseExtras.Notify.NONE);
            return;
        }
        org.liquidphase.element.ElementBlocks.tick(level, pos, state, extra);
        ci.cancel();
    }
}
