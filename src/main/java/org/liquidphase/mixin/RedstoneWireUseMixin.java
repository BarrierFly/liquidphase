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
 * 手持"可组合的宿主方块"右击红石线 → 该格变成红石相组合方块（§3.0.2 两向吸收）。
 * <p>
 * 必须挂在红石线自己的右键入口上：红石线的"点/十字切换"会先吃掉交互，方块物品的
 * 放置流程根本走不到（这就是之前"放不进去"的原因）。
 * <p>
 * 两端行为：服务端权威地把线替换成组合方块并消耗 1 个物品；客户端只取消原版的
 * 点/十字切换（否则本地先切形状、再被服务端纠正，会闪一下）。
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
    private void liquidphase$placeHost(BlockState state, Level level, BlockPos pos, Player player,
                                       InteractionHand hand, BlockHitResult hit,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (!RedstoneBlocks.isPhaseHostItem(player.getItemInHand(hand))) {
            return;
        }
        if (!level.isClientSide() && RedstoneBlocks.placeHostIntoWire(level, pos, player, hand, hit)) {
            player.getItemInHand(hand).shrink(1);
        }
        cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide()));
    }
     *///?} else {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void liquidphase$placeHost(BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        InteractionHand hand = RedstoneBlocks.isPhaseHostItem(player.getMainHandItem())
            ? InteractionHand.MAIN_HAND
            : RedstoneBlocks.isPhaseHostItem(player.getOffhandItem()) ? InteractionHand.OFF_HAND : null;
        if (hand == null) {
            return;
        }
        if (!level.isClientSide() && RedstoneBlocks.placeHostIntoWire(level, pos, player, hand, hit)) {
            player.getItemInHand(hand).consume(1, player);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
    //?}
}
