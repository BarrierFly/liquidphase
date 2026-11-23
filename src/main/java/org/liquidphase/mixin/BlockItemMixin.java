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
 *   <li>元件相（中继器/比较器/侦测器）用**普通右键**放置——潜行右键保留原版
 *       "放在宿主上面一格"的语义；</li>
 *   <li>相材料吸收（冰/霜冰/黑曜石/熔岩源/红石线/真实元件方块 ↔ 宿主）走同一条
 *       {@link PhaseAbsorption#cellOf} 判定 + {@link PhaseAbsorption#absorbCell} 写入。</li>
 * </ul>
 * <p>
 * <b>0.2.6 修正</b>：以前"落点格是相材料格"的三分支（线/材料/元件）在这里与
 * {@code FillingPlacementMixin}/{@code FallingBlockIntoWireMixin}/{@code PhaseCellReplaceMixin}
 * 各写一份，元件只是逐个调用点被打补丁；现在统走 {@link PhaseAbsorption}。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Shadow
    public abstract Block getBlock();

    /** 放置前捕获的落点格内容（null = 本次落点格不是相材料格）。 */
    private static final ThreadLocal<PhaseAbsorption.Cell> LIQUIDPHASE$CELL = new ThreadLocal<>();

    /** 放置前捕获的红石线功率（-1 = 本次落点格不是红石线）。 */
    private static final ThreadLocal<Integer> LIQUIDPHASE$WIRE_POWER =
        ThreadLocal.withInitial(() -> -1);

    /**
     * 落点格是"相材料所在格"（冰/霜冰/黑曜石 / 红石线 / 真实元件方块 / 熔岩源）时，
     * 把这格的内容记下来；真正写入在 {@link #liquidphase$afterPlaceBlock} 的 RETURN 注入里
     * （那时宿主方块已经换好）。
     * <p>
     * 落点格能取到这些格子，前提是 {@link PhaseCellReplaceMixin} 把相材料格对相容宿主物品
     * 放行成"可替换"；熔岩源/水源本来就是原版可替换格。
     */
    @ModifyVariable(method = "placeBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState liquidphase$captureCell(BlockState state, BlockPlaceContext context) {
        LIQUIDPHASE$CELL.remove();
        LIQUIDPHASE$WIRE_POWER.set(-1);
        if (state == null || context == null || !PhaseHelper.hasPhaseProperty(state)) {
            return state;
        }
        BlockPos pos = context.getClickedPos();
        BlockState target = context.getLevel().getBlockState(pos);
        PhaseAbsorption.Cell cell = PhaseAbsorption.cellOf(target);
        if (cell != null) {
            LIQUIDPHASE$CELL.set(cell);
            if (cell.isRedstone() && target.hasProperty(BlockStateProperties.POWER)) {
                // 吸收已存在的线：功率沿用原线（与"新放置"的 0 区分）。
                LIQUIDPHASE$WIRE_POWER.set(target.getValue(BlockStateProperties.POWER));
            }
        }
        return state;
    }

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void liquidphase$afterPlaceBlock(BlockPlaceContext context, BlockState state,
                                             CallbackInfoReturnable<Boolean> cir) {
        PhaseAbsorption.Cell cell = LIQUIDPHASE$CELL.get();
        int power = LIQUIDPHASE$WIRE_POWER.get();
        LIQUIDPHASE$CELL.remove();
        LIQUIDPHASE$WIRE_POWER.set(-1);
        if (!cir.getReturnValue() || cell == null) {
            return;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState placed = level.getBlockState(pos);
        if (!(placed.getBlock() instanceof PhaseCapable) || !PhaseHelper.hasPhaseProperty(placed)) {
            return;
        }
        PhaseAbsorption.absorbCell(level, pos, placed, cell, cell.isRedstone() ? power : -1);
    }

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void liquidphase$place(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        // 客户端必须等服务端握手确认支持相（连原版服务器时永远为 false）。
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return;
        }

        BlockHitResult hit = ((UseOnContextInvoker) context).liquidphase$getHitResult();
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockPos landedPos = context.getClickedPos();

        // (e) 元件装配：普通右键宿主 → 写入元件相；潜行右键 → 原版"放在宿主上面一格"。
        // 与材料/红石粉同构：先看命中格，再看落点格（点旁边的方块让元件"落进"宿主格）。
        // 0.2.5 及以前只认命中格，且要求有玩家；这两处都是"元件比材料少一条组装路径"的来源。
        if (liquidphase$assembleElement(level, context, hitPos)
            || (!landedPos.equals(hitPos) && liquidphase$assembleElement(level, context, landedPos))) {
            cir.setReturnValue(liquidphase$success(level));
            return;
        }

        if (liquidphase$absorbInto(level, context, hitPos)) {
            cir.setReturnValue(liquidphase$success(level));
            return;
        }
        if (!landedPos.equals(hitPos) && liquidphase$absorbInto(level, context, landedPos)) {
            cir.setReturnValue(liquidphase$success(level));
        }
    }

    /**
     * 右键宿主：按手持物品装配中继器/比较器/侦测器（**普通右键**；潜行右键走原版
     * "放在宿主上面一格"，保留需要真实元件方块的情况）。
     * <p>
     * 这条路径与红石粉/材料物品的落点吸收<b>同源</b>：宿主判定问
     * {@link ElementBlocks#hasCavity}（内部就是 {@link PhaseHelper#canAcceptExtra}），
     * 写入问 {@link ElementBlocks#place}。
     * <p>
     * <b>0.2.6 修正的两处不对称</b>（都是"元件比材料/红石粉少一条组装路径"）：
     * <ol>
     *   <li><b>不要求有玩家</b>：{@code getPlayer() == null} 时无法判断潜行，
     *       按"普通右键"处理（与原版 {@code BlockItem.place} 对无玩家来源的语义一致）。
     *       0.2.5 及以前这里直接 {@code player == null → return false}，于是发射器/命令/
     *       其它 mod 放下元件物品时只会落到原版"真元件方块放在宿主上面一格"；</li>
     *   <li><b>也认落点格</b>：由调用方先试命中格、再试落点格，与
     *       {@code liquidphase$absorbInto} 的材料吸收同构——点旁边的方块让元件"落进"
     *       宿主格也能装配（以前只认命中格，材料能做的这个手势元件做不到）。</li>
     * </ol>
     */
    private boolean liquidphase$assembleElement(Level level, BlockPlaceContext context, BlockPos pos) {
        Player player = context.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            return false;
        }
        PhaseExtra.Kind kind = ElementBlocks.kindOfItem(context.getItemInHand());
        if (kind == PhaseExtra.Kind.NONE) {
            return false;
        }
        BlockState target = level.getBlockState(pos);
        if (!(target.getBlock() instanceof PhaseCapable) || !ElementBlocks.hasCavity(level, pos, target)) {
            return false;
        }
        // 朝向：中继器/比较器取水平朝向的对面；侦测器取 getNearestLookingDirection（含上/下），
        // 与原版 ObserverBlock#getStateForPlacement 的双 getOpposite 抵消结果一致。
        // 无玩家时（命令/发射器）getNearestLookingDirection 会 NPE（原版 Entity 空指针），
        // 此时退化为 NORTH——这是唯一可用的确定值，且不会崩。
        Direction facing;
        if (player == null) {
            facing = Direction.NORTH;
        } else if (kind == PhaseExtra.Kind.OBSERVER) {
            facing = context.getNearestLookingDirection();
        } else {
            facing = context.getHorizontalDirection().getOpposite();
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

        // (b) 相容宿主 → 放进相材料本体所在格（吸收该格的相）：走 {@link PhaseCellReplaceMixin}
        // 的"相材料格可替换"放行 + placeBlock 捕获（见上面捕获说明），这里不再拦截——
        // 拦截会绕过原版放置流水线（音效/统计/ getStateForPlacement）。
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
