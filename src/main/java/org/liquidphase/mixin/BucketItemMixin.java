package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版 {@code BucketItem.emptyContents} 只让"水"走容器回调；其他流体（含熔岩）对接受该流体的
 * 容器走"普通放置"分支——先破坏方块再铺流体（fork 的"倒岩浆吞方块"问题）。
 * 这里拦截：目标是相容宿主时改为正确充入熔岩相；不能接受时拒绝（绝不破坏方块）。
 * 发射器装液体走同一个 emptyContents，一并覆盖。
 * <p>
 * 签名分支：≤1.21.1 的 user 参数是 Player，1.21.10+ 是 LivingEntity。
 */
@Mixin(net.minecraft.world.item.BucketItem.class)
public abstract class BucketItemMixin {

    @Shadow
    @Final
    private Fluid content;

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Shadow
    protected abstract void playEmptySound(LivingEntity user, LevelAccessor level, BlockPos pos);

    @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
    private void liquidphase$emptyContents(LivingEntity user, Level level, BlockPos pos, BlockHitResult hitResult, CallbackInfoReturnable<Boolean> cir) {
        if (this.content != Fluids.LAVA) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PhaseCapable)) {
            return;
        }
        if (PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA)) {
            this.playEmptySound(user, level, pos);
            cir.setReturnValue(true);
        } else {
            cir.setReturnValue(false);
        }
    }
    //?} else {
    /*@Shadow
    protected abstract void playEmptySound(Player user, LevelAccessor level, BlockPos pos);

    @Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
    private void liquidphase$emptyContents(Player user, Level level, BlockPos pos, BlockHitResult hitResult, CallbackInfoReturnable<Boolean> cir) {
        if (this.content != Fluids.LAVA) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PhaseCapable)) {
            return;
        }
        if (PhaseHelper.fillWithPhase(level, pos, state, Phase.LAVA)) {
            this.playEmptySound(user, level, pos);
            cir.setReturnValue(true);
        } else {
            cir.setReturnValue(false);
        }
    }
     *///?}
}
