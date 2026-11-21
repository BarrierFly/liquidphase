package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.redstone.RedstoneBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块放置接线，<b>判定只依据"放的是哪个方块、落到哪一格"</b>，不涉及玩家实体、
 * {@code InteractionHand}（{@code context.getPlayer()} 为 {@code null} 时行为一致）。
 * <p>
 * 方案 C 起：
 * <ul>
 *   <li>红石相不再占状态，放置/吸收只写旁路表（{@link PhaseExtras}）；</li>
 *   <li>元件相（中继器/比较器/侦测器）用**潜行右键宿主**放置——普通右键保留原版
 *       "放在宿主上面一格"的语义，不改玩家习惯；</li>
 *   <li>原有的相材料吸收（冰/霜冰/黑曜石 ↔ 宿主）不变。</li>
 * </ul>
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    /** 放置前捕获的红石线功率（-1 = 本次放置不是"宿主进线格"）。 */
    private static final ThreadLocal<Integer> LIQUIDPHASE$WIRE_POWER =
        ThreadLocal.withInitial(() -> -1);

    /**
     * 兼容宿主落进红石线格时，把原线功率记下来；真正写旁路表在
     * {@link #liquidphase$afterPlaceBlock} 的 RETURN 注入里（那时方块已经换好）。
     */
    @ModifyVariable(method = "placeBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState liquidphase$captureWirePower(BlockState state, BlockPlaceContext context) {
        LIQUIDPHASE$WIRE_POWER.set(-1);
        if (state == null || context == null || !PhaseHelper.hasPhaseProperty(state)) {
            return state;
        }
        BlockPos pos = context.getClickedPos();
        BlockState target = context.getLevel().getBlockState(pos);
        if (!target.is(Blocks.REDSTONE_WIRE)) {
            return state;
        }
        LIQUIDPHASE$WIRE_POWER.set(target.hasProperty(BlockStateProperties.POWER)
            ? target.getValue(BlockStateProperties.POWER) : 0);
        return state;
    }

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void liquidphase$afterPlaceBlock(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        int power = LIQUIDPHASE$WIRE_POWER.get();
        LIQUIDPHASE$WIRE_POWER.set(-1);
        if (power < 0 || !cir.getReturnValue()) {
            return;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState placed = level.getBlockState(pos);
        if (placed.getBlock() instanceof PhaseCapable && PhaseHelper.hasPhaseProperty(placed)) {
            RedstoneBlocks.setRedstonePhase(level, pos, power);
        }
    }

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void liquidphase$place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        // 客户端必须等服务端握手确认支持相（连原版服务器时永远为 false）。
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return;
        }

        // (e) 元件放置：潜行右键宿主上表面 → 写入元件相（普通右键保留"放在宿主上面"）。
        if (liquidphase$placeElement(level, context)) {
            cir.setReturnValue(liquidphase$success(level));
            return;
        }

        BlockHitResult hit = ((UseOnContextInvoker) context).liquidphase$getHitResult();
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (liquidphase$absorbInto(level, context, hitPos)) {
            cir.setReturnValue(liquidphase$success(level));
            return;
        }
        BlockPos landedPos = context.getClickedPos();
        if (!landedPos.equals(hitPos) && liquidphase$absorbInto(level, context, landedPos)) {
            cir.setReturnValue(liquidphase$success(level));
        }
    }

    /** 潜行右键宿主：按手持物品放中继器/比较器/侦测器。 */
    private boolean liquidphase$placeElement(Level level, BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return false;
        }
        PhaseExtra.Kind kind = ElementBlocks.kindOfItem(context.getItemInHand());
        if (kind == PhaseExtra.Kind.NONE) {
            return false;
        }
        BlockPos pos = context.getClickedPos();
        BlockState target = level.getBlockState(pos);
        if (!(target.getBlock() instanceof PhaseCapable) || !ElementBlocks.hasCavity(level, pos, target)) {
            return false;
        }
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (kind == PhaseExtra.Kind.OBSERVER) {
            // 侦测器的 FACING 是观察面：观察玩家看向的那一面。
            facing = context.getNearestLookingDirection();
            if (facing.getAxis().isVertical()) {
                facing = context.getHorizontalDirection();
            }
        }
        if (!ElementBlocks.place(level, pos, target, kind, facing)) {
            return false;
        }
        if (!level.isClientSide()) {
            liquidphase$consume(context.getItemInHand(), player);
        }
        return true;
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

        // (a0) 红石粉指向相容宿主 → 该格写入红石相（旁路表）。初始功率 0，由 onPlace 立即重算。
        if (held == Blocks.REDSTONE_WIRE
            && targetState.getBlock() instanceof PhaseCapable
            && PhaseHelper.canAcceptExtra(level, target, targetState)
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
