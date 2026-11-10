package org.liquidphase.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint.
 * <p>
 * The redstone shell texture is transparent-backed (about 29% opaque pixels), so phased
 * states must draw on a cutout layer instead of SOLID (SOLID drops transparent pixels
 * entirely). That is handled by {@code PhaseRenderLayerMixin} on
 * {@code ItemBlockRenderTypes.getChunkRenderType}, <b>not</b> here: the
 * {@code BlockRenderLayerMap} table is rebuilt by the vanilla resource reload, so mappings
 * written during {@code onInitializeClient} get overwritten back to SOLID (verified in a real
 * client run). 26.3 derives the layer from texture transparency and needs no handling.
 * <p>
 * This entrypoint only installs the capability handshake receiver.
 */
public final class LiquidPhaseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Capability handshake: a vanilla server never sends it, so client features stay off.
        new org.liquidphase.sync.PhaseHandshakeClient().onInitializeClient();
    }
}
