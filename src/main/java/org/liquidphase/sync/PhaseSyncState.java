package org.liquidphase.sync;

import net.minecraft.world.level.Level;

/**
 * Whether the current server supports phases (a plain static flag; the server loads this
 * class too, so it must not reference any client-only type).
 * <p>
 * Three situations:
 * <ul>
 *   <li><b>Single player</b>: the integrated server shares the process, so the handshake
 *       still runs. Before it arrives the flag is {@code false} and client-side features
 *       briefly fall back to vanilla; world data is unaffected.</li>
 *   <li><b>Modded server</b>: the handshake announces phase support and the flag turns
 *       {@code true}.</li>
 *   <li><b>Vanilla server</b>: the handshake never arrives, the flag stays {@code false},
 *       and every client-side phase feature (fluid ray, local instant-mine, liquid-item
 *       placing) falls back to vanilla so the client never predicts state the server does
 *       not have.</li>
 * </ul>
 */
public final class PhaseSyncState {

    private static volatile boolean serverSupportsPhases;

    private PhaseSyncState() {
    }

    /** {@return whether the connected server confirmed phase support (default {@code false})} */
    public static boolean serverSupportsPhases() {
        return serverSupportsPhases;
    }

    /** Reset on disconnect/reconnect so one server's state never leaks into the next. */
    public static void reset() {
        serverSupportsPhases = false;
    }

    static void update(boolean supported) {
        serverSupportsPhases = supported;
    }

    /**
     * {@return whether phase logic that <b>mutates world state</b> may run on {@code level}}
     * <p>
     * Always true on the logical server (its config is authoritative). On the logical client
     * only true after the server confirmed support; otherwise the client would predict block
     * states the server does not have (writing a redstone phase into a wire tile, swapping a
     * bucket for a liquid item, ...) and the real state would snap back.
     */
    public static boolean canMutateWorld(Level level) {
        return !level.isClientSide() || serverSupportsPhases;
    }
}
