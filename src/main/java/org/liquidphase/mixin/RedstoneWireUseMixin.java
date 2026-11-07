package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.redstone.RedstoneBlocks;
import org.liquidphase.redstone.WirePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 手持"可含水方块"右击红石线 → 该格换成含红石相的宿主方块，功率沿用原线（§3.0.2 两向吸收）。
 * <p>
 * 必须挂在红石线自己的右键入口上：红石线的"点/十字切换"会先吃掉交互，
 * {@code BlockItem.place} 那条分支根本走不到（这就是之前"放不进去"的原因）。
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
        if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) {
            return;
        }
        if (!level.isClientSide() && liquidphase$place(level, pos, player, hand, hit)) {
            player.getItemInHand(hand).shrink(1);
        }
        cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide()));
    }
     *///?} else {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void liquidphase$placeHost(BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        InteractionHand hand = player.getMainHandItem().getItem() instanceof BlockItem
            && player.getMainHandItem().getItem() != net.minecraft.world.item.Items.REDSTONE
            ? InteractionHand.MAIN_HAND
            : player.getOffhandItem().getItem() instanceof BlockItem
                && player.getOffhandItem().getItem() != net.minecraft.world.item.Items.REDSTONE
                ? InteractionHand.OFF_HAND : null;
        if (hand == null) {
            return;
        }
        if (!level.isClientSide() && liquidphase$place(level, pos, player, hand, hit)) {
            player.getItemInHand(hand).consume(1, player);
        }
        // 客户端也吃掉这次交互：否则本地会先跑点/十字切换再被服务端纠正。
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
    //?}

    /** {@return 是否已把该格换成含红石相的宿主} */
    private static boolean liquidphase$place(Level level, BlockPos pos, Player player, InteractionHand hand,
                                             BlockHitResult hit) {
        BlockState wire = level.getBlockState(pos);
        if (!wire.is(Blocks.REDSTONE_WIRE)) {
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof PhaseCapable)) {
            return false;
        }
        // 红石线不是可替换方块，普通 context 的点击位置会算成线旁边那格；
        // WirePlaceContext 强制 replaceClicked，让宿主按线所在格算放置状态。
        BlockPlaceContext context = new WirePlaceContext(level, player, hand, stack, hit);
        BlockState hostState = blockItem.getBlock().getStateForPlacement(context);
        if (hostState == null) {
            return false;
        }
        return RedstoneBlocks.placeHostIntoWire(level, pos, hostState,
            wire.getValue(BlockStateProperties.POWER));
    }
}
