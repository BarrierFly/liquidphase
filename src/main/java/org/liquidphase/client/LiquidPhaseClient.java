package org.liquidphase.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端。红石相现在不是独立方块（而是宿主方块上的 phase 取值），
 * 覆盖层 blockstate 里引用的红石相模型贴图是透明底 cutout，
 * 需要给这些宿主方块登记 cutout 图层。
 * <p>
 * 1.19.4/1.20.1/1.21.1 用旧版 {@code BlockRenderLayerMap.INSTANCE}；
 * 1.21.10/1.21.11 用新版 {@code client.rendering.v1.BlockRenderLayerMap} + {@code ChunkSectionLayer.CUTOUT}；
 * 26.3 按贴图透明度自动推导，不需要注册。
 */
public final class LiquidPhaseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*for (net.minecraft.world.level.block.Block block
            : org.liquidphase.redstone.RedstoneRenderBlocks.redstoneHosts()) {
            net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
                block, net.minecraft.client.renderer.RenderType.cutout());
        }
        *///?} elif 1.21.10 || 1.21.11 {
        for (net.minecraft.world.level.block.Block block
            : org.liquidphase.redstone.RedstoneRenderBlocks.redstoneHosts()) {
            net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                block, net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT);
        }
        //?}
    }
}
