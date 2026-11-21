package org.liquidphase.mixin.client;

//? if !(26.3) {

import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 渲染层（1.19.4–1.21.11）：带相状态一律走 cutout。
 * <p>
 * <b>为什么不能只在客户端初始化时 {@code BlockRenderLayerMap.putBlock}：</b>
 * 那张表在资源重载时会被原版重建，初始化阶段写进去的映射会被覆盖回 SOLID。
 * 实测：初始化时 {@code oak_leaves} 确实被识别为 SOLID 并写入了 CUTOUT，
 * 但进入世界后 {@code getChunkRenderType} 仍返回 SOLID，结果透明底的红石外壳
 * 被 SOLID 层直接丢弃透明像素——整格宿主"看不到外壳"就是这个原因。
 * <p>
 * 规则：
 * <ul>
 *   <li>带相（含红石相）且原图层是 SOLID → 返回 CUTOUT（alpha 测试，透明像素透出宿主）；</li>
 *   <li>原图层已经是 CUTOUT / CUTOUT_MIPPED → 保持原值；</li>
 *   <li>原图层是 TRANSLUCENT（玻璃板/染色玻璃）→ 保持原值（改 cutout 会让半透明像素变实）。</li>
 * </ul>
 * <b>{@code getMovingBlockRenderType} 同样要改</b>：下落方块（{@code FallingBlockEntity}）
 * 与活塞推动中的方块走那条路径，它按 {@code TYPE_BY_BLOCK} 的原始图层返回
 * {@code RenderType.solid()}（1.19.4–1.21.10）/ {@code solidMovingBlock()}（1.21.11），
 * 同样丢弃透明像素，于是"含红石相的下落方块"渲染成一坨不透明方块。
 * <p>
 * 26.3 没有 {@code ItemBlockRenderTypes}，由贴图透明度自动推导图层，不需要本 mixin。
 */
@Mixin(net.minecraft.client.renderer.ItemBlockRenderTypes.class)
public abstract class PhaseRenderLayerMixin {

    // ---- 区块图层 ----

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getChunkRenderType", at = @At("RETURN"), cancellable = true)
    private static void liquidphase$phasedLayer(BlockState state,
                                                CallbackInfoReturnable<net.minecraft.client.renderer.RenderType> cir) {
        if (!state.hasProperty(PhaseHelper.FILLING)) {
            return;
        }
        if (cir.getReturnValue() == net.minecraft.client.renderer.RenderType.solid()) {
            cir.setReturnValue(net.minecraft.client.renderer.RenderType.cutout());
        }
    }
     *///?} else {
    @Inject(method = "getChunkRenderType", at = @At("RETURN"), cancellable = true)
    private static void liquidphase$phasedLayer(BlockState state,
                                                CallbackInfoReturnable<net.minecraft.client.renderer.chunk.ChunkSectionLayer> cir) {
        if (!state.hasProperty(PhaseHelper.FILLING)) {
            return;
        }
        if (cir.getReturnValue() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) {
            cir.setReturnValue(net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT);
        }
    }
    //?}

    // ---- 移动方块图层（下落方块 / 活塞推动） ----

    //? if 1.19.4 || 1.20.1 || 1.21.1 || 1.21.10 {
    /*@Inject(method = "getMovingBlockRenderType", at = @At("RETURN"), cancellable = true)
    private static void liquidphase$phasedMovingLayer(BlockState state,
                                                      CallbackInfoReturnable<net.minecraft.client.renderer.RenderType> cir) {
        if (!state.hasProperty(PhaseHelper.FILLING)) {
            return;
        }
        // 树叶在"树叶不裁切"图形设置下原版就给 solid()；带相时必须 alpha 测试，
        // 否则透明底外壳的像素会被整片丢弃。
        if (cir.getReturnValue() == net.minecraft.client.renderer.RenderType.solid()) {
            cir.setReturnValue(net.minecraft.client.renderer.RenderType.cutout());
        }
    }
     *///?} else {
    @Inject(method = "getMovingBlockRenderType", at = @At("RETURN"), cancellable = true)
    private static void liquidphase$phasedMovingLayer(BlockState state,
                                                      CallbackInfoReturnable<net.minecraft.client.renderer.rendertype.RenderType> cir) {
        if (!state.hasProperty(PhaseHelper.FILLING)) {
            return;
        }
        if (cir.getReturnValue() == net.minecraft.client.renderer.rendertype.RenderTypes.solidMovingBlock()) {
            cir.setReturnValue(net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock());
        }
    }
    //?}
}

//?}
