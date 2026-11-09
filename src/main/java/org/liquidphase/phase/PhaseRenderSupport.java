package org.liquidphase.phase;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared phase rendering predicates (pure logic, no client-only classes, safe on the server).
 * <p>
 * Phase overlays and the redstone shell are an <b>extra layer</b> drawn on top of the host
 * model, not part of the host itself, so they must not take part in vanilla
 * "same-block faces are culled" ({@code skipRendering}).
 * <p>
 * Vanilla implementations ({@code LeavesBlock}, {@code IronBarsBlock},
 * {@code HalfTransparentBlock}, ...) drop a face when the neighbour is the same kind of
 * block. Applying that to a phased state makes two adjacent phased blocks each lose the
 * shared face - the phase appears to have a missing corner, and redstone dust vanishes
 * between two adjacent redstone-phase stairs. That is the "biased culled faces" bug.
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

    /**
     * {@return whether vanilla may cull the face between these two states}
     * <p>
     * Only when <b>neither</b> side carries a phase do we hand the decision back to vanilla;
     * if either side does, return {@code false} so the face is never dropped by that rule.
     */
    public static boolean allowVanillaCulling(BlockState self, BlockState neighbour) {
        return !hasPhase(self) && !hasPhase(neighbour);
    }
}
