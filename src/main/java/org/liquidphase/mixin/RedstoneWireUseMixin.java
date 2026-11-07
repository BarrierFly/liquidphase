package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 红石线右键"点/十字"切换会先于物品使用执行，导致拿可含水方块点红石线时
 * 线只切换形状、方块根本放不进去（§3.0.2 两向吸收）。
 * <p>
 * 这里在手持"可组合的宿主方块物品"时放过右键，让 {@code BlockItem.place} 的
 * "宿主放进红石线格 → 红石相组合方块"分支跑起来；其余情况完全不干预。
 * <p>
 * 签名分支：≤1.20.1 是块级 {@code use(...)}，1.21.1+ 是 {@code useWithoutItem(...)}。
 */
//? if 26.3 {
/*@Mixin(net.minecraft.world.level.block.RedstoneWireBlock.class)
 *///?} else {
@Mixin(net.minecraft.world.level.block.RedStoneWireBlock.class)
//?}
public abstract class RedstoneWireUseMixin {

    //? if 1.19.4 || 1.20.1 {
    /*@Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void liquidphase$skipShapeToggle(BlockState state, Level level, BlockPos pos, Player player,
                                             InteractionHand hand, BlockHitResult hit,
                                             CallbackInfoReturnable<InteractionResult> cir) {
        if (RedstoneBlocks.isPhaseHostItem(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
     *///?} else {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void liquidphase$skipShapeToggle(BlockState state, Level level, BlockPos pos, Player player,
                                             BlockHitResult hit,
                                             CallbackInfoReturnable<InteractionResult> cir) {
        if (RedstoneBlocks.isPhaseHostItem(player.getItemInHand(InteractionHand.MAIN_HAND))
            || RedstoneBlocks.isPhaseHostItem(player.getItemInHand(InteractionHand.OFF_HAND))) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
    //?}
}
