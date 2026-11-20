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
 * 0.1.16 起同样的吸收逻辑也作用于"落点格"：点旁边的方块让红石粉/冰/宿主落进
 * 相容宿主格（原版会因不可替换直接 FAIL 的那条路径），同样写相。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    /**
     * {@return 写进方块前补好相位的状态}
     * <p>
     * 目标格此刻仍是红石线 → 把这次要放的宿主状态补上红石相，功率**沿用原线**：
     * 吸收一格已存在的红石线不算重新放置红石粉，线的现有状态原样带进填充相
     * （"单独放置"的初始 0 口径只适用于点粉那条新放置路径）。用"目标格现在是红石线"
     * 而不是"上下文里带着标记"：这样无论方块是通过 {@code BlockItem.place} 直接放进来的，
     * 还是物品先自己搜索落点（{@code updatePlacementContext}）再进来，判定都成立。
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
        // 依次对"点击格"与"落点格"尝试吸收（判定只依据放的是哪个方块、落到哪一格）：
        // - 点击格 = 被点击的方块本身（直接点宿主/相材料，原有路径）；
        // - 落点格 = context.getClickedPos()（0.1.16 新增）：点旁边的方块让红石粉/冰/
        //   宿主"落进"目标格。原版对不可替换的落点格直接 FAIL——这条路径此前从未
        //   被接管，表现为"非直接点击放置失败"（用户实测缺口）。
        BlockPos hitPos = hit.getBlockPos();
        if (liquidphase$absorbInto(level, context, hitPos)) {
            cir.setReturnValue(liquidphase$success(level));
            return;
        }
        BlockPos landedPos = context.getClickedPos();
        if (!landedPos.equals(hitPos) && liquidphase$absorbInto(level, context, landedPos)) {
            cir.setReturnValue(liquidphase$success(level));
            return;
        }
    }

    /**
     * 对目标格尝试相吸收（红石粉写红石相 / 相材料物品写相 / 相容宿主吸相材料格的相）。
     * {@code true} = 已吸收（物品已消耗，放置结束）；{@code false} = 目标格不适用，交给原版流程。
     */
    private boolean liquidphase$absorbInto(Level level, BlockPlaceContext context, BlockPos target) {
        BlockState targetState = level.getBlockState(target);
        Block held = this.getBlock();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        // (a0) 红石粉指向相容宿主 → 该格写入红石相（§3.4）。初始功率 0，与单独放置
        // 红石粉一致；真实功率由 onPlace 的邻居重算立即结算（见 RedstoneBlocks.setRedstonePhase）。
        // 门控统一走 canAcceptPhase（0.1.17 可替换填充相规则）：现有填充相是水/熔岩
        // （可替换）时同样写红石相，不可替换相（冰/霜冰/黑曜石/红石）按不可放置处理。
        if (held == Blocks.REDSTONE_WIRE
            && targetState.getBlock() instanceof PhaseCapable
            && PhaseHelper.canAcceptPhase(targetState, level, target, Phase.REDSTONE_15)
            && RedstoneBlocks.setRedstonePhase(level, target, targetState)) {
            liquidphase$consume(stack, player);
            return true;
        }

        // (a) 相材料物品 → 写入相容宿主。
        Phase fromItem = PhaseAbsorption.phaseOfItem(held);
        if (fromItem != null && targetState.getBlock() instanceof PhaseCapable
            && PhaseHelper.fillWithPhase(level, target, targetState, fromItem)) {
            liquidphase$consume(stack, player);
            return true;
        }

        // (b) 相容宿主 → 放进相材料方块本体所在格（吸收该格的相）。
        Phase fromBlock = PhaseAbsorption.phaseOfBlock(targetState);
        if (fromBlock != null && held instanceof PhaseCapable) {
            BlockState hostState = held.getStateForPlacement(context);
            if (hostState != null && PhaseHelper.canAcceptPhase(hostState, level, target, fromBlock)) {
                level.setBlock(target, PhaseHelper.withPhase(hostState, fromBlock), Block.UPDATE_ALL);
                liquidphase$consume(stack, player);
                return true;
            }
        }
        return false;
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
