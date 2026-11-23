package org.liquidphase.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 右键调元件档位（0.2.5）。
 * <p>
 * 目标：宿主本身**没有使用键交互**时，右键它就能调所含中继器/比较器的档位
 * （中继器 1→2→3→4→1、比较器 compare/subtract）。宿主自己已经有使用键交互的
 * （箱子、告示牌、活板门……）保持原样。
 * <p>
 * <b>为什么挂在服务端 {@code ServerPlayerGameMode#useItemOn} 的 RETURN 上，而不是状态级
 * {@code useWithoutItem}：</b>三个 API 时代的回落规则不一样——
 * <ul>
 *   <li>1.21.10+：块级 {@code useItemOn} 默认返回 {@code TRY_WITH_EMPTY_HAND}，服务端会
 *       接着调 {@code useWithoutItem}；</li>
 *   <li>1.21.1：只有返回 {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} 且是主手才回落；手持物品时
 *       {@code ItemStack.useOn} 分支一旦不消费就直接返回 {@code PASS}，根本不会调
 *       {@code useWithoutItem}；</li>
 *   <li>1.20.1 及以前：只有 {@code BlockState.use}，没有"物品 / 空手"的区分点。</li>
 * </ul>
 * 判定放在"整个 useItemOn 已经返回、且没有消费这次点击"这个唯一共同位置：
 * 只要物品自身/宿主没有消费点击，就轮到调档。这与原版"物品不消费时回落到方块默认交互"
 * 的语义一致，也不会和"手持方块右键放置""手持元件右键装配"抢点击
 * （那两条路径都会返回消费，注入点直接放行）。
 * <p>
 * 客户端不做任何预测：调档只写旁路表，由服务端广播 {@code extra_update} 后客户端重画，
 * 因此不需要配套的客户端 mixin，也不会在连原版服务器时单方面改行为。
 */
@Mixin(net.minecraft.server.level.ServerPlayerGameMode.class)
public abstract class ElementTuningMixin {

    @Shadow
    @Final
    private ServerPlayer player;

    @Inject(method = "useItemOn", at = @At("RETURN"), cancellable = true)
    private void liquidphase$tuneElement(ServerPlayer player, Level level, ItemStack stack,
                                         net.minecraft.world.InteractionHand hand, BlockHitResult hitResult,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        if (result != null && result.consumesAction()) {
            // 物品/宿主已经消费了这次点击（放置、装配、开箱……）→ 不抢。
            return;
        }
        if (hitResult == null) {
            return;
        }
        BlockPos pos = hitResult.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!PhaseHelper.hasPhaseProperty(state)) {
            return;
        }
        if (!ElementBlocks.tryTune(level, pos, state, this.player)) {
            return;
        }
        //? if 1.21.10 || 1.21.11 || 26.3 {
        cir.setReturnValue(InteractionResult.SUCCESS);
        //?} else {
        /*cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide()));
         *///?}
    }
}
