package org.liquidphase.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 元件粒子（0.2.7，I 的 PT-2）：原版只有 {@code RepeaterBlock#animateTick} 会发红石粒子，
 * 比较器与侦测器都没有。我们的元件不占方块，宿主的 {@code animateTick} 不会替元件发粒子，
 * 所以在客户端 {@code ClientLevel.doAnimateTick} 的 TAIL 补一次——那里已经算好
 * {@code pos}/{@code random} 并调过宿主自己的 {@code animateTick}，与"原版每格每帧一次"
 * 的调用节奏完全一致（同一个随机源、同一批被抽到的格子）。
 * <p>
 * 判定全部走服务端权威同步过来的旁路表：只有"中继器条目且 {@code powered}"才发粒子。
 */
@Mixin(ClientLevel.class)
public abstract class PhaseElementParticleMixin {

    @Inject(method = "doAnimateTick", at = @At("TAIL"))
    private void liquidphase$elementParticles(int x, int y, int z, int range, RandomSource random,
                                              net.minecraft.world.level.block.Block markerParticleTarget,
                                              BlockPos.MutableBlockPos pos, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        BlockState state = level.getBlockState(pos);
        int extra = PhaseExtras.get(level, pos);
        if (!PhaseExtra.isElement(extra)) {
            return;
        }
        ElementBlocks.animateTick(state, level, pos, random, extra);
    }
}
