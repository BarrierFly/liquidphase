package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块放置接线，<b>判定只依据"放的是哪个方块、落到哪一格"</b>，不涉及玩家实体、
 * {@code InteractionHand}、是否潜行（{@code context.getPlayer()} 为 {@code null} 时行为一致）。
 * <p>
 * {@link #liquidphase$redstonePhase}：真正写方块前，如果目标格此刻还是红石线，
 * 就把宿主状态补成 {@code phase=redstone_<原线功率>}。这是纯几何判断（这一格现在是线），
 * 不关心是谁把方块放进来的。
 * <p>
 * 让放置流程愿意把方块放进线格的是 {@code RedstoneWireReplaceMixin}（放宽
 * {@code canBeReplaced(state, context)}），因为"这一格能不能被替换"只有那个方法能回答——
 * {@code BlockItem.place} 第一步就问它，自己搜索落点的物品（如脚手架堆叠）也问它。
 * <p>
 * 另外保留原有的"放置吸收"语义（同样只看手里的方块物品与命中的方块）：
 * (a0) 红石粉点相容宿主 → 写红石相；(a) 相材料物品点宿主 → 写相；
 * (b) 相容宿主点相材料方块 → 吸收该相。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    /**
     * {@return 写进方块前补好相位的状态}
     * <p>
     * 目标格此刻仍是红石线 → 把这次要放的宿主状态写成 {@code phase=redstone_<功率>}。
     * 用"目标格现在是红石线"而不是"上下文里带着标记"：这样无论方块是通过
     * {@code BlockItem.place} 直接放进来的，还是物品先自己搜索落点（
     * {@code updatePlacementContext}）再进来，判定都成立。
     * <p>
     * 挂 {@code placeBlock} 而不是 {@code getPlacementState}：告示牌/床这类子类会整个
     * 覆写 {@code getPlacementState} 且不调 super，但它们的 {@code placeBlock} 仍走这里。
     */
    @ModifyVariable(method = "placeBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState liquidphase$redstonePhase(BlockState state, BlockPlaceContext context) {
        if (state == null || context == null || !PhaseHelper.hasPhaseProperty(state)) {
            return state;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState target = level.getBlockState(pos);
        if (!target.is(Blocks.REDSTONE_WIRE)) {
            return state;
        }
        int power = target.hasProperty(BlockStateProperties.POWER)
            ? target.getValue(BlockStateProperties.POWER) : 0;
        return PhaseHelper.withPhase(state, Phase.redstone(power));
    }

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void liquidphase$place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        // 客户端必须等服务端握手确认支持相（连原版服务器时永远为 false）。
        // 否则客户端会自己把相写进本地世界（红石粉点宿主、液体物品、相材料吸收），
        // 服务端随后下发真实状态覆盖回来，表现为方块闪一下就变回去。
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return;
        }
        BlockHitResult hit = ((UseOnContextInvoker) context).liquidphase$getHitResult();
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = level.getBlockState(hitPos);
        Block held = this.getBlock();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        // (a0) 红石粉指向相容宿主 → 该格写入红石相（§3.4）。
        if (held == Blocks.REDSTONE_WIRE
            && hitState.getBlock() instanceof PhaseCapable
            && !RedstoneBlocks.isRedstonePhase(hitState)
            && PhaseHelper.getPhase(hitState) == Phase.NONE
            && (!hitState.hasProperty(BlockStateProperties.WATERLOGGED)
                || !hitState.getValue(BlockStateProperties.WATERLOGGED))
            && RedstoneBlocks.setRedstonePhase(level, hitPos, hitState, 15)) {
            liquidphase$consume(stack, player);
            cir.setReturnValue(liquidphase$success(level));
            return;
        }

        // (a) 相材料物品 → 写入被点击的相容宿主。
        Phase fromItem = PhaseAbsorption.phaseOfItem(held);
        if (fromItem != null && hitState.getBlock() instanceof PhaseCapable
            && PhaseHelper.fillWithPhase(level, hitPos, hitState, fromItem)) {
            liquidphase$consume(stack, player);
            cir.setReturnValue(liquidphase$success(level));
            return;
        }

        // (b) 相容宿主 → 放进相材料方块本体所在格。
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
