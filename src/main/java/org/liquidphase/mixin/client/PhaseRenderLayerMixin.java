package org.liquidphase.mixin.client;

//? if !(26.3) {

import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.phase.PhaseRenderSupport;
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
 * 26.3 没有 {@code ItemBlockRenderTypes}，由贴图透明度自动推导图层，不需要本 mixin。
 */
@Mixin(net.minecraft.client.renderer.ItemBlockRenderTypes.class)
public abstract class PhaseRenderLayerMixin {

    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*@Inject(method = "getChunkRenderType", at = @At("RETURN"), cancellable = true)
    private static void liquidphase$phasedLayer(BlockState state,
                                                CallbackInfoReturnable<net.minecraft.client.renderer.RenderType> cir) {
        if (!PhaseRenderSupport.hasPhase(state)) {
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
        if (!PhaseRenderSupport.hasPhase(state)) {
            return;
        }
        if (cir.getReturnValue() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) {
            cir.setReturnValue(net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT);
        }
    }
    //?}
}

//?}
