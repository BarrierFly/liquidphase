package org.liquidphase.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseCapable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让红石线所在格在「要放进去的是相容宿主方块」时算作可替换。
 * <p>
 * <b>为什么必须在这里</b>：{@code BlockItem.place} 第一步就是 {@code context.canPlace()}，
 * 它问的是 {@code level.getBlockState(pos).canBeReplaced(context)}——也就是下面这个重载。
 * 红石线默认返回 false，所以放置流程在进入 {@code getStateForPlacement} 之前就 {@code FAIL}
 * 了。更隐蔽的是：**自己搜索落点的物品**（{@code ScaffoldingBlockItem.updatePlacementContext}
 * 是原版唯一一个）在搜索循环里也调这个方法，红石线一格会被直接跳过、方块落到别处。
 * 只在 {@code BlockItem.place} 入口上做手脚覆盖不到这种路径。
 * <p>
 * <b>判定对象只有两样</b>：这一格是不是红石线、这个放置上下文要放进去的方块是不是
 * {@link PhaseCapable}。{@code context.getItemInHand()} 读的是<b>放置上下文自己携带的
 * 待放物品</b>（玩家右键时来自手上，发射器/自动放置时来自被投放的物品，两者走的都是
 * 同一个字段），<b>与玩家实体、{@code InteractionHand}、是否潜行无关</b>；
 * {@code getPlayer()} 为 {@code null} 时行为完全一样。
 * <p>
 * <b>为什么不用流体那个重载</b>：{@code canBeReplaced(BlockState, Fluid)} 决定的是
 * 「水/熔岩能不能流进这一格」（{@code FluidState.canBeReplacedWith}），与放置无关，
 * 那边保持原版语义不动。
 * <p>
 * 只在目标是红石线时放宽；红石线自身对其它方块（石头、雪层、藤蔓等）仍然是不可替换，
 * 因为它们不是 {@link PhaseCapable}，这个钩子对它们直接放行到原版逻辑。
 */
@Mixin(net.minecraft.world.level.block.state.BlockBehaviour.class)
public abstract class RedstoneWireReplaceMixin {

    @Inject(
        method = "canBeReplaced(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/item/context/BlockPlaceContext;)Z",
        at = @At("HEAD"), cancellable = true)
    private void liquidphase$wireIsReplaceableForHost(BlockState state, BlockPlaceContext context,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (state == null || !state.is(Blocks.REDSTONE_WIRE) || context == null) {
            return;
        }
        ItemStack placing = context.getItemInHand();
        if (!(placing.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof PhaseCapable)) {
            return;
        }
        cir.setReturnValue(true);
    }
}
