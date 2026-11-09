package org.liquidphase.sync;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client capability handshake.
 * <p>
 * <b>Why it exists.</b> Feature 3 (empty-bucket left-click on liquids, liquid-item placing,
 * the fluid crosshair ray) is client driven but authoritative on the server. A vanilla
 * server has no phases and no "liquid item" semantics, so a modded client acting on its own
 * would desync: the client thinks it mined something, the server never saw it.
 * <p>
 * So the client side must confirm the server first:
 * <ul>
 *   <li>On <b>JOIN</b> the server sends {@link PhaseHandshakePayload}
 *       (raw {@code FriendlyByteBuf} channel on 1.19.4/1.20.1);</li>
 *   <li>the client flips {@link PhaseSyncState} on receipt and resets on disconnect;</li>
 *   <li>a vanilla server never sends it, so the client stays off and feature 3 falls back
 *       to vanilla behaviour.</li>
 * </ul>
 * <b>Single player.</b> The integrated server and client share a process, so the packet still
 * goes through. Before it arrives (the login frame) the client treats the server as
 * unsupported; the server-side phase logic never reads this state, so neither single player,
 * a modded server, nor a vanilla server can affect the block-state sync.
 */
public final class PhaseHandshake {

    public static final int BIT_PHASE_ENABLED = 1;
    public static final int BIT_LIQUID_INTERACTION = 1 << 1;

    private PhaseHandshake() {
    }

    /** {@return the capability bit mask for the given config values} */
    public static int bits(boolean phaseEnabled, boolean liquidInteraction) {
        int bits = 0;
        if (phaseEnabled) {
            bits |= BIT_PHASE_ENABLED;
        }
        if (liquidInteraction) {
            bits |= BIT_LIQUID_INTERACTION;
        }
        return bits;
    }

    /** {@return whether the mask announces phases} */
    public static boolean hasPhase(int bits) {
        return (bits & BIT_PHASE_ENABLED) != 0;
    }

    /** {@return whether the mask announces feature 3} */
    public static boolean hasLiquidInteraction(int bits) {
        return (bits & BIT_LIQUID_INTERACTION) != 0;
    }

    /** {@return the bit mask for the current config} */
    public static int currentBits() {
        return bits(org.liquidphase.LiquidPhaseConfig.get().phaseEnabled,
            org.liquidphase.LiquidPhaseConfig.get().liquidInteraction);
    }

    /**
     * Server wiring (called once from {@code LiquidPhase.onInitialize}).
     * <p>
     * 1.21.1+ must register the payload type before sending it (an unregistered type throws),
     * then JOIN sends the mask to each arriving player. 1.19.4/1.20.1 use the raw buffer
     * channel and need no registration.
     */
    public static void registerServer() {
        //? if 1.19.4 || 1.20.1 {
        /*// Raw FriendlyByteBuf channels need no payload registration.
         *///?} elif 26.3 {
        /*net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
            .register(PhaseHandshakePayload.TYPE, PhaseHandshakePayload.CODEC);
         *///?} else {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
            .register(PhaseHandshakePayload.TYPE, PhaseHandshakePayload.CODEC);
        //?}
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
            (handler, sender, server) -> sendTo(handler.player));
    }

    private static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        int bits = currentBits();
        //? if 1.19.4 || 1.20.1 {
        /*net.minecraft.network.FriendlyByteBuf buf =
            new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(bits);
        // 原版客户端没有注册这个通道：canSend 为 false 时必须跳过，
        // 否则 Fabric 会往一个对端不认识的通道发包（记录警告/被丢弃）。
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                player, PhaseIds.handshake())) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, PhaseIds.handshake(), buf);
        }
         *///?} else {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                player, PhaseHandshakePayload.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, new PhaseHandshakePayload(bits));
        }
        //?}
    }
}
