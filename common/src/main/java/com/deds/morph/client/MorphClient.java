package com.deds.morph.client;

import com.deds.api.client.ClientInput;
import com.deds.api.client.ClientKeys;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

/**
 * The loader-neutral half of Morph's client bootstrap. Keys go through Ded's
 * API ({@link ClientKeys}); the mouse wheel + grabbed-mouse aim go through
 * {@link ClientInput}. The HUD layers, the crosshair suppression, the
 * level-render hook and the end-of-tick hook are loader glue and live in each
 * loader's client entrypoint ({@code com.deds.morph.fabric.MorphFabricClient}
 * on Fabric, {@code com.deds.morph.neoforge.MorphNeoForgeClient} on NeoForge),
 * which calls {@link #init()} first and routes its end-of-tick hook to
 * {@link #clientTick(Minecraft)}.
 *
 * <p>Wave 2 (selector GUI overhaul): the browse keys now ship BOUND to their
 * original defaults {@code [} / {@code ]} via the {@link ClientKeys} default-key
 * overload (still rebindable in the vanilla controls screen). The remaining
 * selector inputs are rebindable keys too (Enter/Esc/Delete/grave); only
 * LMB/RMB, Shift and the favourites wheel's grave are raw-polled, matching the
 * original which polled the keyboard/mouse directly. Backspace is not bound. TODO(deds-api): lift — HUD overlay +
 * client-input surfaces are API v1.1 candidates.</p>
 */
public final class MorphClient {

    private MorphClient() {
    }

    /**
     * Everything the client bootstrap does that names no loader, in the order
     * the single Fabric entrypoint always did it. The acquisition-effect
     * listener used to be registered after the HUD elements; it now comes
     * before them, which cannot matter: the HUD registry and the S2C receiver
     * are independent, and nothing is drawn or received before the client
     * entrypoints have all returned.
     */
    public static void init() {
        // Let the shared hitbox mixin resolve a client player's committed morph
        // variant (snap-at-end) from the client transition tracker.
        MorphAbilities.setClientResolver(MorphDummies::committedVariant);

        // Original defaults: selector up = '[' (GLFW 91), down = ']' (93).
        ClientKeys.register(Morph.MOD_ID, "selector_prev",
                InputConstants.KEY_LBRACKET, MorphSelector::prev);
        ClientKeys.register(Morph.MOD_ID, "selector_next",
                InputConstants.KEY_RBRACKET, MorphSelector::next);

        // Selector action keys — rebindable in the vanilla controls screen under
        // the "Morph Mod Revived" category (defaults match the original selector).
        // LMB/RMB stay wired as additional raw mouse triggers in
        // MorphSelector.clientTick; these are the keyboard equivalents.
        ClientKeys.register(Morph.MOD_ID, "selector_select",
                InputConstants.KEY_RETURN, MorphSelector::selectKey);
        ClientKeys.register(Morph.MOD_ID, "selector_cancel",
                InputConstants.KEY_ESCAPE, MorphSelector::cancelKey);
        ClientKeys.register(Morph.MOD_ID, "selector_remove",
                InputConstants.KEY_DELETE, MorphSelector::removeKey);
        ClientKeys.register(Morph.MOD_ID, "selector_favourite",
                InputConstants.KEY_GRAVE, MorphSelector::favouriteKey);

        // Mouse wheel scrolls the strip (and cancels the hotbar scroll while
        // open); raw grabbed-mouse motion aims the favourites radial.
        ClientInput.addScrollListener(MorphSelector::onScroll);
        ClientInput.addMotionListener(MorphRadial::onMotion);

        // Acquisition suck-in effect: S2C trigger here; the per-tick motion
        // runs in clientTick and the world-space pass is fed by the loader's
        // level-render hook (MorphAcquisitions.submit).
        Morph.ACQUIRE_FX.listenOnClient(MorphAcquisitions::begin);
    }

    /**
     * The per-client-tick work, called by the loader glue at the END of every
     * client tick (Fabric: {@code ClientTickEvents.END_CLIENT_TICK}; NeoForge:
     * {@code ClientTickEvent.Post}). The order is load-bearing and unchanged.
     */
    public static void clientTick(Minecraft minecraft) {
        MorphDummies.clientTick(minecraft);
        // After MorphDummies (which refreshes the client committed-variant
        // state the applicator reads): clamp the local player's motion for
        // the client-authoritative abilities (float/climb).
        MorphAbilitiesClient.clientTick(minecraft);
        MorphAcquisitions.clientTick(minecraft);
        MorphSelector.clientTick(minecraft);
    }
}
