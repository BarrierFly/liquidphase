package org.liquidphase.mixin;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseRenderSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 含相状态一律按 {@code RenderShape.MODEL} 参与方块模型渲染。
 * <p>
 * <b>为什么必须改这一处</b>：区块构建器只在"该渲染方块模型"时才去问方块模型。
 * 判据随版本不同：
 * <ul>
 *   <li>1.19.4 / 1.20.1：{@code ChunkRenderDispatcher} 用 {@code != RenderShape.INVISIBLE}，
 *       所以 {@code ENTITYBLOCK_ANIMATED} 的方块（箱子等）本来就会渲染模型；</li>
 *   <li>1.21.1 起：{@code SectionCompiler} 用 {@code == RenderShape.MODEL}，
 *       {@code INVISIBLE} 与 {@code ENTITYBLOCK_ANIMATED} 都会被整格跳过。</li>
 * </ul>
 * 这批宿主全都能含相，但它们的原生 RenderShape 不是 MODEL：
 * <ul>
 *   <li>{@code barrier}、{@code light}：{@code INVISIBLE}（全版本）；</li>
 *   <li>告示牌 / 悬挂告示牌全家：{@code INVISIBLE}（全版本）；</li>
 *   <li>{@code decorated_pot}：{@code INVISIBLE}（1.20.1–1.21.1）；</li>
 *   <li>{@code chest} / {@code trapped_chest} / {@code ender_chest} / {@code conduit}：
 *       {@code ENTITYBLOCK_ANIMATED}（1.19.4–1.21.1；1.21.10 起改成 MODEL）。</li>
 * </ul>
 * 于是给它们写入 {@code phase=redstone_15}（或冰/黑曜石相）后**界面上什么都不显示**——
 * 相的数据在，模型从没被请求过。
 * <p>
 * 改成 MODEL 不会让宿主消失：这些方块的可见几何来自方块实体渲染器，那条路径
 * （{@code hasBlockEntity} → {@code BlockEntityRenderDispatcher}）与 RenderShape 无关，
 * 会照常收集。它们自己的方块模型只有 particle 贴图、没有 elements，所以最终多出来的
 * 只有本 mod 的相覆盖层 / 红石外壳。
 * <p>
 * 注入点在 {@code RETURN}（而不是 HEAD 再回调一次）：HEAD 里再问
 * {@code getRenderShape()} 会无限递归，而块级 {@code getRenderShape(state)} 是
 * protected、mixin 拿不到。不带相的状态原值就返回，完全走原版。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PhaseRenderShapeMixin {

    @Inject(method = "getRenderShape", at = @At("RETURN"), cancellable = true)
    private void liquidphase$phasedRenderShape(CallbackInfoReturnable<RenderShape> cir) {
        BlockState self = (BlockState) (Object) this;
        if (cir.getReturnValue() != RenderShape.MODEL && PhaseRenderSupport.hasPhase(self)) {
            cir.setReturnValue(RenderShape.MODEL);
        }
    }
}
