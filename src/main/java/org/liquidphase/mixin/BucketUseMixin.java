package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 相容宿主上用桶的右键交互（fork 的 BucketUseMixin，phase 化）：
 * 熔岩桶指向宿主 → 充入熔岩相（功能三开启时即"液体物品放置"，不返还空桶；关闭时返还空桶）；
 * 空桶指向熔岩相宿主 → 收走熔岩（仅功能三关闭；开启时放行给 PhaseCapable.pickupBlock 拒绝，
 * 由左键挖掘负责收取）。
 * <p>
 * 客户端侧同样要等握手确认（{@link org.liquidphase.sync.PhaseSyncState#canMutateWorld}）：
 * 连原版服务器时这段完全不跑，避免客户端单方面预测"相已写入"、随后被真实方块状态覆盖。
 * <p>
 * 目标 = BlockBehaviour 基类（覆盖未覆写交互的方块）+ 各版本自己声明 use/useItemOn 的类
 * （guardian 声明矩阵）。潜行绕过由原版 ServerPlayerGameMode 在调用前处理，无需在此判断。
 * 签名分支：≤1.20.1 用 use(...)，1.21.1 返回 ItemInteractionResult，1.21.10+ 返回 InteractionResult。
 */
@Mixin({
        net.minecraft.world.level.block.state.BlockBehaviour.class,
        CampfireBlock.class,
        CandleBlock.class,
        CeilingHangingSignBlock.class,
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        DecoratedPotBlock.class,
        //?}
        //? if 1.19.4 || 1.20.1 {
        /*ChestBlock.class,
        EnderChestBlock.class,
        FenceBlock.class,
        LightBlock.class,
        StairBlock.class,
        TrapDoorBlock.class,
         *///?}
        //? if 1.21.10 || 1.21.11 || 26.3 {
        CopperGolemStatueBlock.class,
        ShelfBlock.class,
        WeatheringCopperGolemStatueBlock.class,
        //?}
        SignBlock.class,
        WallHangingSignBlock.class
})
public class BucketUseMixin {

    //? if 1.21.10 || 1.21.11 || 26.3 {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void liquidphase$useItemOn(ItemStack stack, BlockState blockState, Level level, BlockPos blockPos, Player player, InteractionHand hand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir) {
        liquidphase$handleUse(level, blockState, blockPos, player, hand, cir);
    }
    //?} elif 1.21.1 {
    /*@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void liquidphase$useItemOn(ItemStack stack, BlockState blockState, Level level, BlockPos blockPos, Player player, InteractionHand hand, BlockHitResult blockHitResult, CallbackInfoReturnable<net.minecraft.world.ItemInteractionResult> cir) {
        liquidphase$handleUse(level, blockState, blockPos, player, hand, cir);
    }
     *///?} else {
    /*@Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void liquidphase$use(BlockState blockState, Level level, BlockPos blockPos, Player player, InteractionHand hand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir) {
        liquidphase$handleUse(level, blockState, blockPos, player, hand, cir);
    }
     *///?}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void liquidphase$handleUse(Level level, BlockState blockState, BlockPos blockPos, Player player, InteractionHand hand,
                                              CallbackInfoReturnable cir) {
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(level)) {
            return;
        }
        if (!(blockState.getBlock() instanceof PhaseCapable)
            || !blockState.hasProperty(PhaseHelper.PHASE)
            || player.getItemInHand(hand).isEmpty()) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        boolean liquidInteraction = LiquidPhaseConfig.get().liquidInteraction;

        if (stack.is(Items.LAVA_BUCKET) && PhaseHelper.fillWithPhase(level, blockPos, blockState, Phase.LAVA)) {
            PhaseHelper.playLavaEmptySound(level, blockPos);
            if (!player.isCreative()) {
                stack.shrink(1);
                if (!liquidInteraction) {
                    player.getInventory().add(Items.BUCKET.getDefaultInstance());
                }
            }
            cir.setReturnValue(liquidphase$success());
        } else if (stack.is(Items.BUCKET) && PhaseHelper.getPhase(blockState) == Phase.LAVA && !liquidInteraction) {
            level.setBlock(blockPos, PhaseHelper.withPhase(blockState, Phase.NONE), Block.UPDATE_ALL);
            if (!blockState.canSurvive(level, blockPos)) {
                level.destroyBlock(blockPos, true);
            }
            PhaseHelper.playLavaFillSound(level, blockPos);
            if (!player.isCreative()) {
                stack.shrink(1);
                player.getInventory().add(Items.LAVA_BUCKET.getDefaultInstance());
            }
            cir.setReturnValue(liquidphase$success());
        }
    }

    //? if !(1.19.4 || 1.20.1 || 1.21.1) {
    private static InteractionResult liquidphase$success() {
        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*private static Object liquidphase$success() {
        //? if 1.21.1 {
        return net.minecraft.world.ItemInteractionResult.SUCCESS;
        //?} else {
        return InteractionResult.SUCCESS;
        //?}
    }
     *///?}
}
