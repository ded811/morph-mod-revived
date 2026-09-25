package com.deds.api.internal.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

/**
 * A {@link KeyMapping} that exposes its CURRENT binding ({@code key} is
 * {@code protected}, javap-verified) so the held-state loop can poll the
 * physical device directly. Held bindings must NOT poll
 * {@link KeyMapping#isDown()}: opening any screen calls
 * {@code KeyMapping.releaseAll()} ({@code Gui.setScreen}, javap-verified),
 * which would fabricate a release edge the player never made — and the
 * restore on close ({@code MouseHandler.grabMouse} → {@code setAll()}) is
 * gated behind {@code InputQuirks.RESTORE_KEY_STATE_AFTER_MOUSE_GRAB}
 * (false on macOS) and skips MOUSE-bound keys entirely. Raw GLFW state has
 * none of those quirks — exactly the original TrailMix mechanism
 * ({@code TickHandlerClient.isPressed}: raw {@code Keyboard.isKeyDown} /
 * {@code Mouse.isButtonDown}).
 *
 * <p>Built by {@link KeyDispatcher#newMapping} with the plain vanilla 4-argument
 * constructor, so a loader that adds binding features of its own (conflict
 * contexts, modifiers) treats it as an ordinary vanilla mapping. Public only
 * so a loader backend can register it with that loader's key registry; it is
 * not API. It used to be a private nested class of the Fabric client
 * entrypoint ({@code DedsApiFabricClient}) and is unchanged apart from its
 * visibility.</p>
 */
public final class RawKeyMapping extends KeyMapping {

    public RawKeyMapping(String name, InputConstants.Type type, int code,
            KeyMapping.Category category) {
        super(name, type, code, category);
    }

    /** The CURRENT binding (follows controls-screen rebinds). */
    InputConstants.Key boundKey() {
        return this.key;
    }
}
