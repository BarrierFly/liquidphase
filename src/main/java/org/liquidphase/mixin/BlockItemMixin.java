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
import org.liquidphase.redstone.RedstoneBlocks;
import org.liquidphase.redstone.WirePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块放置的两处接线，<b>都只看"放的是哪个方块、落到哪一格"</b>：
 * <ol>
 *   <li><b>入口</b> {@link #liquidphase$wireContext}：落点是红石线格时，把放置上下文换成
 *       {@link WirePlaceContext}，让 {@code canPlace()} 放行。这是"可含水方块能放进红石线格"
 *       的<b>唯一</b>入口——{@code BlockItem.place} 是所有方块放置的公共通道
 *       （玩家、命令、结构方块、发射器语义、其它模组），没有任何一条路径绕得开；
 *       而且这里只对 {@code getBlock() instanceof PhaseCapable} 生效。</li>
 *   <li><b>落子</b> {@link #liquidphase$redstonePhase}：真正写方块前，给宿主状态补上
 *       {@code phase=redstone_<原线功率>}。宿主其它属性（朝向/半砖/连接态/含水）
 *       保持原版 {@code getStateForPlacement} 算出来的值。</li>
 * </ol>
 * <b>不做什么</b>：不接管红石线的 {@code use}/{@code useWithoutItem}（那是方块交互，
 * 会把玩家点击路径写进逻辑），不改方块级 {@code canBeReplaced}（那会让水流、活塞、
 * 邻居更新、其它模组都把红石线当可替换方块）。判定里没有玩家、没有手、
 * 没有 {@code InteractionHand}、没有潜行。
 * <p>
 * 另外保留原有的"放置吸收"语义（同样与玩家无关，只看手里的方块物品和命中的方块）：
 * (a0) 红石粉点相容宿主 → 写红石相；(a) 相材料物品点宿主 → 写相；
 * (b) 相容宿主点相材料方块 → 吸收该相。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    /**
     * {@return 落点在红石线格时改写过的放置上下文}
     * <p>
     * 只对"要放下去的这个方块是相容宿主"生效；红石粉、石头、火把等其它方块的
     * 放置语义原样不动。判定纯几何（{@link WirePlaceContext#of}），不看手势。
     */
    @ModifyVariable(method = "place", at = @At("HEAD"), argsOnly = true)
    private BlockPlaceContext liquidphase$wireContext(BlockPlaceContext context) {
        if (!(this.getBlock() instanceof PhaseCapable)) {
            return context;
        }
        return WirePlaceContext.of(context);
    }

    /**
     * {@return 写进红石线格的状态（带红石相）}
     * <p>
     * 挂 {@code placeBlock} 而不是 {@code getPlacementState}：告示牌/床这类子类会整个
     * 覆写 {@code getPlacementState} 且不调 super，但它们的 {@code placeBlock} 仍走这里。
     */
    @ModifyVariable(method = "placeBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState liquidphase$redstonePhase(BlockState state, BlockPlaceContext context) {
        if (!(context instanceof WirePlaceContext wireContext) || state == null) {
            return state;
        }
        if (!PhaseHelper.hasPhaseProperty(state)) {
            return state;
        }
        return PhaseHelper.withPhase(state, Phase.redstone(wireContext.wirePower()));
    }

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

        // (a0) 红石粉指向相容宿主 → 该格写入红石相（§3.4）。
        if (held == net.minecraft.world.level.block.Blocks.REDSTONE_WIRE
            && hitState.getBlock() instanceof PhaseCapable
            && !RedstoneBlocks.isRedstonePhase(hitState)
            && PhaseHelper.getPhase(hitState) == Phase.NONE
            && (!hitState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                || !hitState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
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
