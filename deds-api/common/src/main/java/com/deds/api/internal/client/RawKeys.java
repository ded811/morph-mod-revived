package com.deds.api.internal.client;

import com.deds.api.client.ClientKeys;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/**
 * The only part of the key machinery that differs between Minecraft versions:
 * reading a binding's raw device state, and mapping a {@link ClientKeys}
 * default onto the running version's {@code InputConstants}. Kept this small
 * on purpose, because it is the one key-machinery file (and the one file in
 * deds-api/common) with a per-version overlay.
 *
 * <p>This is the canonical (26.2, GLFW) version.
 * {@code versions/mc26.3/deds-api/common/src/main/java/com/deds/api/internal/client/RawKeys.java}
 * is the 26.3 (SDL) one. Before the loader split the whole Fabric client
 * entrypoint was overlaid on 26.3 for these few lines; the logic of both
 * versions is unchanged.</p>
 */
final class RawKeys {

    private RawKeys() {
    }

    /**
     * The RAW physical down-state of a mapping's CURRENT binding, straight
     * from GLFW — immune to {@code KeyMapping.releaseAll()}/{@code setAll()}
     * screen-transition churn on every platform (macOS included).
     * KEYSYM via {@code InputConstants.isKeyDown}, MOUSE via
     * {@code glfwGetMouseButton} (both javap-verified); the rare SCANCODE
     * binding has no raw query, so it falls back to the mapping's own state.
     */
    static boolean isDown(Minecraft minecraft, RawKeyMapping mapping) {
        InputConstants.Key bound = mapping.boundKey();
        if (bound.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        if (bound.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(minecraft.getWindow(),
                    bound.getValue());
        }
        if (bound.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(minecraft.getWindow().handle(),
                    bound.getValue()) == GLFW.GLFW_PRESS;
        }
        return mapping.isDown();
    }

    /**
     * The vanilla input type for a default. ClientKeys.UNBOUND (-1) equals
     * InputConstants.UNKNOWN's GLFW code — but ONLY under KEYSYM, so UNBOUND
     * always routes to KEYSYM regardless of the requested type (see
     * {@link KeyDispatcher#newMapping}). Mouse-button defaults are GLFW
     * codes: left 0, right 1, middle 2.
     */
    static InputConstants.Type typeFor(ClientKeys.InputType type,
            int defaultCode) {
        return defaultCode == ClientKeys.UNBOUND
                || type != ClientKeys.InputType.MOUSE
                ? InputConstants.Type.KEYSYM : InputConstants.Type.MOUSE;
    }

    /**
     * The vanilla key code for a default: unchanged on 26.2, where
     * ClientKeys.UNBOUND (-1) already is the unbound key's GLFW code.
     */
    static int codeFor(int defaultCode) {
        return defaultCode;
    }
}
