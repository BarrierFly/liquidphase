package org.liquidphase.sync;

/**
 * Cross-version channel identifier construction.
 * <p>
 * Three API eras:
 * <ul>
 *   <li>1.19.4 / 1.20.1: public {@code ResourceLocation(String, String)} constructor;</li>
 *   <li>1.21.1 / 1.21.10: {@code ResourceLocation.fromNamespaceAndPath};</li>
 *   <li>1.21.11 / 26.3: the class was renamed to {@code Identifier}.</li>
 * </ul>
 * The raw source tree must stay valid for 1.21.11, so the live branch uses
 * {@code Identifier} and the older eras live in commented branches.
 */
public final class PhaseIds {

    public static final String NAMESPACE = "liquidphase";
    public static final String HANDSHAKE_PATH = "handshake";

    private PhaseIds() {
    }

    //? if 1.21.11 || 26.3 {
    public static net.minecraft.resources.Identifier id(String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    public static net.minecraft.resources.Identifier handshake() {
        return id(HANDSHAKE_PATH);
    }
    //?} elif 1.21.1 || 1.21.10 {
    /*public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    public static net.minecraft.resources.ResourceLocation handshake() {
        return id(HANDSHAKE_PATH);
    }
     *///?} else {
    /*public static net.minecraft.resources.ResourceLocation id(String path) {
        return new net.minecraft.resources.ResourceLocation(NAMESPACE, path);
    }

    public static net.minecraft.resources.ResourceLocation handshake() {
        return id(HANDSHAKE_PATH);
    }
     *///?}
}
