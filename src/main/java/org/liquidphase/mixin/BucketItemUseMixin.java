package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 功能三（规划 §5）：手持"液体物品"（水/熔岩，沿用桶的物品 id）右键 = 像放方块一样放液体
 * （消耗 1 个、不返还空桶；对相容宿主写入相/含水；下界水蒸发照旧）；
 * 空桶右键装/倒全部拒绝（收取只走左键挖掘）。
 * 功能三关闭时不拦截，回到原版桶 + PhaseCapable 的熔岩相路径。
 * <p>
 * 返回类型分版本：≤1.21.1 的 {@code BucketItem.use} 返回 {@code InteractionResultHolder}，
 * 1.21.10+ 返回 {@code InteractionResult}。≤1.21.1 必须用对应类型——否则
 * {@code setReturnValue} 之后在注入点被强转回宿主返回类型时直接 CCE。0.1.12 及以前
 * {@code BucketUseMixin} 在块级消费掉右键、这条路径很少被走到而未暴露；0.1.13 起
 * 桶交互完全走原版路径（audit §4.7），这条修复成了前提。
 */
@Mixin(BucketItem.class)
public abstract class BucketItemUseMixin {

    @Shadow
    @Final
    private Fluid content;

    /** 通用结果码：不接管 / 拒绝（PASS）/ 已填充（SUCCESS）。 */
    private static final int liquidphase$NOT_HANDLED = 0;
    private static final int liquidphase$REJECT_PASS = 1;
    private static final int liquidphase$FILLED_SUCCESS = 2;

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void liquidphase$use(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        int code = liquidphase$handleUse(level, player, hand);
        if (code == liquidphase$REJECT_PASS) {
            cir.setReturnValue(InteractionResult.PASS);
        } else if (code == liquidphase$FILLED_SUCCESS) {
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
    //?} else {
    /*@Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void liquidphase$use(Level level, Player player, InteractionHand hand,
                                 CallbackInfoReturnable<net.minecraft.world.InteractionResultHolder<ItemStack>> cir) {
        int code = liquidphase$handleUse(level, player, hand);
        ItemStack stack = player.getItemInHand(hand);
        if (code == liquidphase$REJECT_PASS) {
            cir.setReturnValue(net.minecraft.world.InteractionResultHolder.pass(stack));
        } else if (code == liquidphase$FILLED_SUCCESS) {
            cir.setReturnValue(net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide()));
        }
    }
     *///?}

    private int liquidphase$handleUse(Level level, Player player, InteractionHand hand) {
        if (!LiquidPhaseConfig.get().liquidInteraction) {
            return liquidphase$NOT_HANDLED;
        }
        // 客户端必须等服务端握手确认支持相（连原版服务器时永远为 false）。
        // 不拦的话客户端会自己把水/熔岩摆下去、扣物品，而服务端什么都没发生——
        // 表现就是"放了又弹回来、物品数量对不上"。
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return liquidphase$NOT_HANDLED;
        }
        if (this.content == Fluids.EMPTY) {
            // 空桶：原版"装液体"拒绝（§5.2）。
            return liquidphase$REJECT_PASS;
        }
        if (this.content != Fluids.WATER && this.content != Fluids.LAVA) {
            return liquidphase$NOT_HANDLED;
        }

        // 放置：像放方块一样（射线口径对齐原版 Item.getPlayerPOVHitResult：OUTLINE + 交互距离）。
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        double range = liquidphase$pickRange(player);
        BlockHitResult hit = level.clip(new ClipContext(eye,
            eye.add(player.getViewVector(1.0F).scale(range)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return liquidphase$NOT_HANDLED;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = level.getBlockState(hitPos);
        boolean isLava = this.content == Fluids.LAVA;

        // 目标是容器方块 → 写入相/含水（拿液体物品指向可 log 方块，规划 §3.0.2）。
        if (hitState.getBlock() instanceof LiquidBlockContainer container) {
            boolean filled = isLava
                ? PhaseHelper.fillWithPhase(level, hitPos, hitState, Phase.LAVA)
                : container.placeLiquid(level, hitPos, hitState, Fluids.WATER.getSource(false));
            if (filled) {
                playEmptySound(level, placePos(hit, hitState), isLava);
                consume(player, hand);
                return liquidphase$FILLED_SUCCESS;
            }
        }

        // 否则像方块一样放进可容纳的格（空气/可替换）。
        BlockPos placePos = placePos(hit, hitState);
        BlockState placeState = level.getBlockState(placePos);
        if (!placeState.isAir() && !placeState.canBeReplaced()) {
            return liquidphase$NOT_HANDLED;
        }
        if (!isLava && PhaseHelper.waterEvaporates(level, placePos)) {
            // 下界蒸发（对齐原版 emptyContents 的 WATER_EVAPORATES 行为）。
            level.playSound(null, placePos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F);
            consume(player, hand);
            return liquidphase$FILLED_SUCCESS;
        }
        level.setBlock(placePos, this.content.defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        playEmptySound(level, placePos, isLava);
        consume(player, hand);
        return liquidphase$FILLED_SUCCESS;
    }

    private static BlockPos placePos(BlockHitResult hit, BlockState hitState) {
        return hitState.canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
    }

    private static void playEmptySound(Level level, BlockPos pos, boolean lava) {
        level.playSound(null, pos, lava ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void consume(Player player, InteractionHand hand) {
        //? if 1.21.10 || 1.21.11 || 26.3 {
        player.getItemInHand(hand).consume(1, player);
        //?} else {
        /*if (!player.getAbilities().instabuild) {
            player.getItemInHand(hand).shrink(1);
        }
         *///?}
    }

    private static double liquidphase$pickRange(Player player) {
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        return player.blockInteractionRange();
        //?} else {
        /*return 5.0D;
         *///?}
    }
}
