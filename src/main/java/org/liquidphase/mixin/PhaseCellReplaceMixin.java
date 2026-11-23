package org.liquidphase.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseAbsorption;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让"相材料所在格"在「要放进去的是相容宿主方块」时算作可替换。
 * <p>
 * <b>为什么必须在这一层</b>：{@code BlockState.canBeReplaced(BlockPlaceContext)} 是唯一
 * 的状态级分发点——它 {@code invokevirtual getBlock().canBeReplaced(state, context)}。
 * 注入在 {@code BlockBehaviour}（块级）上的钩子会被宿主的覆写遮蔽：
 * {@code ScaffoldingBlock}、{@code SlabBlock}、{@code SnowLayerBlock}、{@code VineBlock}
 * 等十几个类都自己覆写了 {@code canBeReplaced(state, context)}，所以块级注入对它们不生效。
 * 状态级方法不会被覆写，是唯一可靠的挂点。这个判定在 {@code BlockPlaceContext} 构造时就
 * 被读取（{@code replaceClicked}），所以 {@code getClickedPos()} 会直接指向相材料格——
 * {@code BlockItemMixin} 的捕获才能拿到正确的落点。
 * <p>
 * <b>谁会走这里</b>：
 * <ul>
 *   <li>{@code BlockItem.place} 第一步的 {@code context.canPlace()}
 *       （{@code canPlace()} → {@code state.canBeReplaced(context)}）；</li>
 *   <li>自己搜索落点的物品：{@code ScaffoldingBlockItem.updatePlacementContext}；</li>
 *   <li>下落方块：{@code FallingBlockEntity.tick()} 落位前也调它（其空物品上下文由
 *       {@code FallingBlockIntoWireMixin} 换成宿主自己的物品）。</li>
 * </ul>
 * <p>
 * <b>判定依据</b>：这一格是相材料格（{@link PhaseAbsorption#cellOf}——冰/霜冰/黑曜石、
 * 红石线、真实元件方块、熔岩源<b>都在同一个判定里</b>），并且这次放置要放进去的方块是
 * {@link PhaseCapable}。其它方块（石头、红石粉、雪层……）不受影响：它们不是相容宿主，
 * 这里直接放行到原版逻辑；红石线自身的 {@code canBeReplaced()}（无参）也保持 {@code false}。
 * <p>
 * ⚠️ 0.2.6 起这里<b>不再</b>手写 {@code wire / material / element} 三分支：
 * 那正是"只对现有填充相打补丁"的来源（新加一种填充要改四个调用点，漏一个就少一条组装路径）。
 * 现在一律问 {@link PhaseAbsorption#cellOf}，新增种类只需改那份唯一判定。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PhaseCellReplaceMixin {

    @Inject(method = "canBeReplaced(Lnet/minecraft/world/item/context/BlockPlaceContext;)Z",
            at = @At("HEAD"), cancellable = true)
    private void liquidphase$phaseCellIsReplaceableForHost(BlockPlaceContext context,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (context == null) {
            return;
        }
        BlockState self = (BlockState) (Object) this;
        if (PhaseAbsorption.cellOf(self) == null) {
            return;
        }
        // Client-side prediction must wait for the server handshake: on a vanilla server the
        // material cell is genuinely unreplaceable, and predicting otherwise leaves a ghost block
        // until the server corrects it.
        if (!org.liquidphase.sync.PhaseSyncState.canMutateWorld(context.getLevel())) {
            return;
        }
        ItemStack placing = context.getItemInHand();
        if (!(placing.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof PhaseCapable)) {
            return;
        }
        // 相材料格对相容宿主物品放行；写入哪个相由 placeBlock 捕获按格子内容决定
        //（线格继承功率；材料格走 fillWithPhase 的接受判定）。元素物品不走这里：
        // 它要让宿主格保持"不可替换"，否则放下时会覆盖宿主（见 BlockItemMixin 的注释）。
        if (PhaseHelper.hasPhaseProperty(item.getBlock().defaultBlockState())) {
            cir.setReturnValue(true);
        }
    }
}
