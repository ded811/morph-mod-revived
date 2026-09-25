package com.deds.api.internal.client;

import com.deds.api.client.ClientKeys;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

import org.lwjgl.sdl.SDLMouse;

/**
 * The 26.3 version of
 * {@code deds-api/common/src/main/java/com/deds/api/internal/client/RawKeys.java}:
 * the only part of the key machinery that differs between Minecraft versions.
 * 26.3's window layer is SDL, not GLFW. This is the logic the whole-file 26.3
 * overlay of the Fabric client entrypoint carried before the loader split,
 * unchanged.
 */
final class RawKeys {

    private RawKeys() {
    }

    /**
     * The RAW physical down-state of a mapping's CURRENT binding, straight
     * from the platform layer — immune to {@code KeyMapping.releaseAll()}/
     * {@code setAll()} screen-transition churn on every platform.
     *
     * <p>26.3: the window layer is SDL, not GLFW. KEYBOARD bindings read
     * {@code InputConstants.isKeyDown(int)} (SDL's keyboard state, by
     * scancode). MOUSE bindings are SDL-numbered on 26.3 -
     * {@code InputConstants.MOUSE_BUTTON_LEFT} 1, {@code MIDDLE} 2,
     * {@code RIGHT} 3, extra buttons from 4 - and buttons 1..5 read SDL's
     * button mask ({@code SDL_GetMouseState}), the SDL twin of
     * {@code glfwGetMouseButton}. Not {@code MouseHandler.isLeftPressed()}
     * and friends: those only update while no screen is open, so a button
     * released inside a screen would read stuck-down after it closes. Any
     * other button falls back to the mapping's own state.</p>
     */
    static boolean isDown(Minecraft minecraft, RawKeyMapping mapping) {
        InputConstants.Key bound = mapping.boundKey();
        if (bound.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        if (bound.getType() == InputConstants.Type.KEYBOARD) {
            return InputConstants.isKeyDown(bound.getValue());
        }
        if (bound.getType() == InputConstants.Type.MOUSE) {
            int button = bound.getValue();
            if (button >= SDLMouse.SDL_BUTTON_LEFT && button <= SDLMouse.SDL_BUTTON_X2) {
                return (SDLMouse.SDL_GetMouseState(null, null)
                        & (1 << (button - 1))) != 0;
            }
            return mapping.isDown();
        }
        return mapping.isDown();
    }

    /**
     * The vanilla input type for a default. ClientKeys.UNBOUND (-1) is the
     * API's "no key"; on 26.3 the unbound key is KEYBOARD 0
     * (InputConstants.UNKNOWN), so UNBOUND always routes to KEYBOARD
     * regardless of the requested type (see {@link KeyDispatcher#newMapping}).
     */
    static InputConstants.Type typeFor(ClientKeys.InputType type,
            int defaultCode) {
        boolean unbound = defaultCode == ClientKeys.UNBOUND;
        return unbound || type != ClientKeys.InputType.MOUSE
                ? InputConstants.Type.KEYBOARD : InputConstants.Type.MOUSE;
    }

    /**
     * The vanilla key code for a default. UNBOUND is translated to the 26.3
     * unbound code. Every other default passes through unchanged: they are
     * the running version's InputConstants values (KEY_*, MOUSE_BUTTON_*),
     * which on 26.3 are SDL scancodes and SDL button numbers (left 1, middle
     * 2, right 3). javac inlines those constants, so a consumer built against
     * 26.3 already hands over 26.3 numbers.
     */
    static int codeFor(int defaultCode) {
        boolean unbound = defaultCode == ClientKeys.UNBOUND;
        return unbound ? InputConstants.UNKNOWN.getValue() : defaultCode;
    }
}
