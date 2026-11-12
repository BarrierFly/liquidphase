package org.liquidphase.phase;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared phase rendering predicates (pure logic, no client-only classes, safe on the server).
 * <p>
 * Phase overlays and the redstone shell are an <b>extra layer</b> drawn on top of the host
 * model. They are generated without {@code cullface} (see
 * {@code tools/gen_phase_blockstates.py} / {@code tools/gen_redstone_assets.py}): their
 * geometry is inset 0.25px or pushed 0.01px outside the block boundary, so the vanilla
 * "does the neighbour cover the block-boundary face" test would cull faces they actually
 * cover. Unculled quads land in the {@code getQuads(null)} list and are always drawn.
 * <p>
 * Because of that, the host's own face culling ({@code BlockState.skipRendering}) needs no
 * intervention: the phase layers never take part in it, and the host's own faces are the
 * host's business. Forcing {@code skipRendering} to {@code false} for phased states only
 * changed vanilla behaviour (phased stairs/fences stopped culling against their neighbours)
 * without fixing any phase rendering - so there is deliberately no mixin for it.
 * <p>
 * Occlusion itself stays the host's own shape (the overlay is inset 0.25px, the shell is
 * mostly transparent pixels): solid phases already get a full-cube collision shape from
 * {@code BlockBehaviourMixin}, while their visual occlusion intentionally follows the host
 * so neighbours do not lose faces the inset overlay does not actually cover.
 */
public final class PhaseRenderSupport {

    private PhaseRenderSupport() {
    }

    /** {@return whether the state carries a phase (i.e. {@code phase != none}, redstone included)} */
    public static boolean hasPhase(BlockState state) {
        return state.hasProperty(PhaseHelper.PHASE) && state.getValue(PhaseHelper.PHASE) != Phase.NONE;
    }

    /** {@return the same state with the phase cleared (the host state); unchanged if it has no phase} */
    public static BlockState hostSibling(BlockState state) {
        return state.hasProperty(PhaseHelper.PHASE) ? state.setValue(PhaseHelper.PHASE, Phase.NONE) : state;
    }
}
