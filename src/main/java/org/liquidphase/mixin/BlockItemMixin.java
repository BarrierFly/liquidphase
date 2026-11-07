package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 放置吸收（规划 §3.0.2 / 决策点 D4：按原版充水的判定来）：
 * (a) 拿相材料物品（冰/黑曜石）指向可 log 方块 → 相写入该格（对齐"水桶指向楼梯→含水"）；
 * (b) 拿可 log 方块指向相材料方块本体（冰/霜冰/黑曜石）→ 相被吸收、原格变为"含相宿主"
 *     （对齐"把方块放进水源格→含水"；原稿示例：准星点到冰块本体放楼梯 → 含冰楼梯）。
 * 熔岩源格的吸收走 PlacementMixin（原版可替换路径），这里只处理不可替换的固体相材料。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void liquidphase$place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        BlockHitResult hit = ((UseOnContextInvoker) context).liquidphase$getHitResult();
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = level.getBlockState(hitPos);
        Block held = this.getBlock();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        // (a0) 红石粉指向相容宿主 → 该格写入红石相（§3.4；§3.5 元件相待后续里程碑）。
        if (held == net.minecraft.world.level.block.Blocks.REDSTONE_WIRE
            && hitState.getBlock() instanceof PhaseCapable
            && !org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(hitState)
            && PhaseHelper.getPhase(hitState) == Phase.NONE
            && (!hitState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                || !hitState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
            && org.liquidphase.redstone.RedstoneBlocks.setRedstonePhase(level, hitPos, hitState, 15)) {
            liquidphase$consume(stack, player);
            cir.setReturnValue(liquidphase$success(level));
            return;
        }

        // (b0) 可 log 方块指向纯红石线格 → 该格换成含红石相的宿主，功率随线（§3.0.2 两向吸收）。
        //      （红石线自己的右键会先吃掉交互，正常玩家路径走的是 RedstoneWireUseMixin；
        //       这里兜住发射器/命令等不经过红石线 use 的放置路径。）
        if (hitState.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE) && held instanceof PhaseCapable) {
            BlockState hostState = held.getStateForPlacement(context);
            if (hostState != null && PhaseHelper.canAcceptPhase(hostState, level, hitPos, Phase.REDSTONE_15)) {
                int power = hitState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER);
                level.setBlock(hitPos, PhaseHelper.withPhase(hostState, Phase.redstone(power)), Block.UPDATE_ALL);
                liquidphase$consume(stack, player);
                cir.setReturnValue(liquidphase$success(level));
                return;
            }
        }

        // (a) 相材料物品 → 写入被点击的相容宿主。
        Phase fromItem = PhaseAbsorption.phaseOfItem(held);
        if (fromItem != null && hitState.getBlock() instanceof PhaseCapable
            && PhaseHelper.fillWithPhase(level, hitPos, hitState, fromItem)) {
            liquidphase$consume(stack, player);
            cir.setReturnValue(liquidphase$success(level));
            return;
        }

        // (b) 可 log 方块 → 放进相材料方块本体所在格。
        Phase fromBlock = PhaseAbsorption.phaseOfBlock(hitState);
        if (fromBlock != null && held instanceof PhaseCapable) {
            BlockState hostState = held.getStateForPlacement(context);
            if (hostState != null && PhaseHelper.canAcceptPhase(hostState, level, hitPos, fromBlock)) {
                level.setBlock(hitPos, PhaseHelper.withPhase(hostState, fromBlock), Block.UPDATE_ALL);
                liquidphase$consume(stack, player);
                cir.setReturnValue(liquidphase$success(level));
            }
        }
    }

    private static void liquidphase$consume(ItemStack stack, Player player) {
        //? if 1.21.10 || 1.21.11 || 26.3 {
        stack.consume(1, player);
        //?} else {
        /*if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
         *///?}
    }

    private static InteractionResult liquidphase$success(Level level) {
        //? if 1.21.10 || 1.21.11 || 26.3 {
        return InteractionResult.SUCCESS;
        //?} else {
        /*return InteractionResult.sidedSuccess(level.isClientSide());
         *///?}
    }
}
