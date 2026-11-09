package org.liquidphase.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseCapable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让红石线所在格在「要放进去的是相容宿主方块」时算作可替换。
 * <p>
 * <b>为什么必须在这一层</b>：{@code BlockState.canBeReplaced(BlockPlaceContext)} 是唯一
 * 的状态级分发点——它 {@code invokevirtual getBlock().canBeReplaced(state, context)}。
 * 注入在 {@code BlockBehaviour}（块级）上的钩子会被宿主的覆写遮蔽：
 * {@code ScaffoldingBlock}、{@code SlabBlock}、{@code SnowLayerBlock}、{@code VineBlock}
 * 等十几个类都自己覆写了 {@code canBeReplaced(state, context)}，所以块级注入对它们不生效。
 * 状态级方法不会被覆写，是唯一可靠的挂点。
 * <p>
 * <b>谁会走这里</b>：
 * <ul>
 *   <li>{@code BlockItem.place} 第一步的 {@code context.canPlace()}
 *       （{@code canPlace()} → {@code state.canBeReplaced(context)}）；</li>
 *   <li>自己搜索落点的物品：{@code ScaffoldingBlockItem.updatePlacementContext}；</li>
 *   <li>下落方块：{@code FallingBlockEntity.tick()} 落位前也调它。</li>
 * </ul>
 * <p>
 * <b>判定依据</b>：这一格是红石线；并且这次放置要放进去的方块是 {@link PhaseCapable}。
 * 待放方块的来源有两个，都不涉及玩家实体：
 * <ol>
 *   <li>放置上下文带着物品（{@code context.getItemInHand()}）——普通物品放置；</li>
 *   <li>放置上下文<b>不带物品</b>（{@code ItemStack.EMPTY}）——下落方块用一个空的
 *       {@code DirectionalPlaceContext} 询问"这一格能不能被我落进去"，它要放的方块
 *       在实体自己的字段里，上下文拿不到。</li>
 * </ol>
 * 第 2 种情况没法从上下文反推，因此对"空物品"的上下文只做范围受限的放宽：
 * 仅当红石线上方没有支撑、也就是说这格本来就该让下落方块穿过去时——
 * 但这条判据不可靠，所以这里改用更保守的口径：<b>空物品上下文一律维持原版</b>，
 * 下落方块能否落进线格交给 {@code BlockItemMixin} 的落位钩子处理（见那边的说明）。
 * <p>
 * 其它方块（石头、红石粉、雪层……）不受影响：它们不是 {@link PhaseCapable}，
 * 这里直接放行到原版逻辑；红石线自身的 {@code canBeReplaced()}（无参）也保持 {@code false}。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class RedstoneWireReplaceMixin {

    @Inject(method = "canBeReplaced(Lnet/minecraft/world/item/context/BlockPlaceContext;)Z",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$wireIsReplaceableForHost(BlockPlaceContext context,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (context == null) {
            return;
        }
        BlockState self = (BlockState) (Object) this;
        if (!self.is(Blocks.REDSTONE_WIRE)) {
            return;
        }
        // Client-side prediction must wait for the server handshake: on a vanilla server the
        // wire tile is genuinely unreplaceable, and predicting otherwise leaves a ghost block
        // until the server corrects it.
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(context.getLevel())) {
            return;
        }
        ItemStack placing = context.getItemInHand();
        if (!(placing.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof PhaseCapable)) {
            return;
        }
        cir.setReturnValue(true);
    }
}
