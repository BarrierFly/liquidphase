package org.liquidphase.sync;

//? if !(1.19.4 || 1.20.1) {

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client capability handshake payload (the {@code CustomPacketPayload} era).
 * <p>
 * {@code bits} is the capability mask (see {@link PhaseHandshake}):
 * <ul>
 *   <li>bit 0: server {@code phaseEnabled};</li>
 *   <li>bit 1: server {@code liquidInteraction}.</li>
 * </ul>
 * The client only enables client-side phase features after receiving this, so a vanilla
 * server (which never sends it) keeps the client on vanilla behaviour.
 */
public record PhaseHandshakePayload(int bits) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PhaseHandshakePayload> TYPE =
        new CustomPacketPayload.Type<>(PhaseIds.handshake());

    public static final StreamCodec<FriendlyByteBuf, PhaseHandshakePayload> CODEC =
        StreamCodec.<FriendlyByteBuf, PhaseHandshakePayload>of(
            (buf, payload) -> buf.writeVarInt(payload.bits()),
            buf -> new PhaseHandshakePayload(buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//?}
