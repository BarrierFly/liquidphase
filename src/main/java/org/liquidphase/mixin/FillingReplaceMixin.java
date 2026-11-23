package org.liquidphase.mixin;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 填充相放置的**接受层**（audit v2 用户实测缺口，0.1.16 修）：向相容宿主格放填充相材料
 * （冰/黑曜石/红石粉等）的尝试，在放置管线里算"可替换"——任意来源（玩家、发射器、
 * 下落方块、任意 mod）的放置尝试都会被放行，改写由 {@code FillingPlacementMixin}
 * 在 {@code Level.setBlock} 统一完成。与 {@link PhaseCellReplaceMixin}（相材料格接受
 * 宿主）同一架构、同一注入点、方向互补：那边是"宿主进材料格"，这边是"材料进宿主格"。
 * <p>
 * 门控（0.1.17 起）**就是** {@code PhaseHelper.canAcceptPhase}（可替换填充相规则）本身，
 * 不再复写一份内联门控——这是防漂移设计：接受层/改写层/交互层的判定必须同源，
 * 自检 {@code checkAbsorptionGateConsistency} 会逐路径对账。手里是相材料物品
 * （冰/霜冰/黑曜石的 BlockItem 或红石粉）。其余物品（含同种宿主方块）不受影响，
 * 原版可替换语义不变；流体重载 {@code canBeReplaced(BlockState, Fluid)} 不在本注入点
 * （水流/熔岩走容器路径）。
 * <p>
 * ⚠️ **元件物品故意不在这里放行**：元件装配走 {@code BlockItemMixin#place} 头部的拦截
 * （先于 {@code context.canPlace()}），宿主格必须保持"不可替换"，否则放下时会覆盖宿主。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class FillingReplaceMixin {

    @Inject(method = "canBeReplaced(Lnet/minecraft/world/item/context/BlockPlaceContext;)Z",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$hostAcceptsFillingPlacement(BlockPlaceContext context,
                                                         CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        if (!(self.getBlock() instanceof PhaseCapable)) {
            return;
        }
        Phase filling = PhaseAbsorption.fillingOfItem(context.getItemInHand());
        if (filling == null
            || !PhaseHelper.canAcceptPhase(self, context.getLevel(), context.getClickedPos(), filling)) {
            return;
        }
        // 客户端预测必须等服务端握手；连原版服务器时该格真的不可替换，预测会闪一下弹回。
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(context.getLevel())) {
            return;
        }
        // 材料方块自身的 canSurvive（如红石粉需要支撑）仍由原版 canPlace 把关。
        cir.setReturnValue(true);
    }
}
