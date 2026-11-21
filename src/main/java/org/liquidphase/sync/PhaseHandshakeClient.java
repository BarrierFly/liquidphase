package org.liquidphase.sync;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client half of the capability handshake (a separate {@link ClientModInitializer} so the
 * server never loads client-only networking classes).
 * <p>
 * On receiving the handshake it flips {@link PhaseSyncState} to "supported"; disconnects and
 * new joins reset it so the next server has to re-announce itself. A vanilla server never
 * sends the packet, so client-side phase features stay off there.
 * <p>
 * Version split: 1.19.4/1.20.1 use the raw {@code FriendlyByteBuf} channel overloads;
 * 1.21.1+ use {@code CustomPacketPayload} (Fabric networking 4.x dropped the channel API).
 */
public final class PhaseHandshakeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        //? if 1.19.4 || 1.20.1 {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PhaseIds.handshake(), (client, handler, buf, responseSender) -> {
                int bits = buf.readVarInt();
                client.execute(() -> {
                    PhaseSyncState.update(PhaseHandshake.hasPhase(bits));
                    if (PhaseHandshake.hasPhase(bits)) {
                        PhaseExtraClientSync.requestLoadedNearPlayer();
                    }
                });
            });
         *///?} else {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PhaseHandshakePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    PhaseSyncState.update(PhaseHandshake.hasPhase(payload.bits()));
                    if (PhaseHandshake.hasPhase(payload.bits())) {
                        PhaseExtraClientSync.requestLoadedNearPlayer();
                    }
                }));
        //?}
        // Reset on both edges: leaving a server, and (re)joining one (single player restarts
        // the integrated server, so the old "supported" flag would otherwise leak through).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> PhaseSyncState.reset());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
            (handler, sender, client) -> PhaseSyncState.reset());
    }
}
