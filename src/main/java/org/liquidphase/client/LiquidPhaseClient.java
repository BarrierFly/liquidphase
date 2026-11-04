package org.liquidphase.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端：为红石相组合方块指定 cutout 图层（贴图是透明底 + 不透明红石线）。
 * 26.3 不需要注册：方块层由 BakedQuad 依据贴图透明度自动推导。
 */
public final class LiquidPhaseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*for (net.minecraft.world.level.block.Block block : org.liquidphase.redstone.RedstoneBlocks.COMPOSITE_TO_HOST.keySet()) {
            net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
                block, net.minecraft.client.renderer.RenderType.cutout());
        }
        *///?} elif 1.21.10 || 1.21.11 {
        for (net.minecraft.world.level.block.Block block : org.liquidphase.redstone.RedstoneBlocks.COMPOSITE_TO_HOST.keySet()) {
            net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                block, net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT);
        }
        //?}
    }
}
