package org.liquidphase.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseCapable;

/**
 * Client entrypoint.
 * <p>
 * <b>1. Render layers.</b> The redstone dust shell texture is transparent-backed (about 29%
 * opaque pixels). A host that would otherwise draw on the SOLID layer would render those
 * transparent pixels as opaque black, so such hosts must go through cutout (alpha test).
 * <p>
 * The rule is "only upgrade if the current layer is SOLID". Hosts already on CUTOUT /
 * CUTOUT_MIPPED need nothing; <b>translucent</b> hosts (glass panes, stained glass) must stay
 * TRANSLUCENT - forcing them to cutout would turn their half-transparent pixels opaque and
 * break the vanilla look (the shell's opaque dust pixels still draw fine in the translucent
 * layer).
 * <p>
 * Version differences:
 * <ul>
 *   <li>1.19.4/1.20.1/1.21.1: legacy {@code BlockRenderLayerMap.INSTANCE}, and
 *       {@code ItemBlockRenderTypes.getChunkRenderType} returns a {@code RenderType};</li>
 *   <li>1.21.10/1.21.11: {@code client.rendering.v1.BlockRenderLayerMap} with
 *       {@code ChunkSectionLayer};</li>
 *   <li>26.3: the layer is derived from texture transparency, so nothing to register.</li>
 * </ul>
 * <b>2. Handshake receiver</b> - {@link org.liquidphase.sync.PhaseHandshakeClient}.
 */
public final class LiquidPhaseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Capability handshake: a vanilla server never sends it, so client features stay off.
        new org.liquidphase.sync.PhaseHandshakeClient().onInitializeClient();

        // With phases disabled there are no phased states, so never touch vanilla layers.
        if (!LiquidPhaseConfig.get().phaseEnabled) {
            return;
        }
        //? if !(26.3) {
        List<Block> solids = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof PhaseCapable)) {
                continue;
            }
            BlockState state = block.defaultBlockState();
            if (liquidphase$isSolidLayer(state)) {
                solids.add(block);
            }
        }
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*for (Block block : solids) {
            net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(
                block, net.minecraft.client.renderer.RenderType.cutout());
        }
         *///?} else {
        for (Block block : solids) {
            net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(
                block, net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT);
        }
        //?}
        //?}
    }

    //? if !(26.3) {
    //? if 1.19.4 || 1.20.1 || 1.21.1 {
    /*private static boolean liquidphase$isSolidLayer(BlockState state) {
        return net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(state)
            == net.minecraft.client.renderer.RenderType.solid();
    }
     *///?} else {
    private static boolean liquidphase$isSolidLayer(BlockState state) {
        return net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(state)
            == net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID;
    }
    //?}
    //?}
}
