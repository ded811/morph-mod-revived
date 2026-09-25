package com.deds.morph.fabric.client;

import com.deds.api.client.ClientInput;
import com.deds.api.client.ClientKeys;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.resources.Identifier;

/**
 * Fabric client entrypoint for Morph. Keys go through Ded's API
 * ({@link ClientKeys}); the mouse wheel + grabbed-mouse aim go through
 * {@link ClientInput}; the HUD layers and per-tick client hooks are loader glue
 * confined to this package.
 *
 * <p>Wave 2 (selector GUI overhaul): the browse keys now ship BOUND to their
 * original defaults {@code [} / {@code ]} via the {@link ClientKeys} default-key
 * overload (still rebindable in the vanilla controls screen). The remaining
 * selector inputs (Enter/Esc/Delete/Backspace/grave + LMB/RMB + Shift) are
 * raw-polled in {@link MorphSelector#clientTick}, matching the original which
 * polled the keyboard/mouse directly. TODO(deds-api): lift — HUD overlay +
 * client-input surfaces are API v1.1 candidates.</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
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

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(Morph.MOD_ID, "selector"),
                MorphSelector::render);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(Morph.MOD_ID, "radial"),
                MorphRadial::render);

        // Hide the vanilla crosshair while the favourites radial is open (the
        // original cancels the CROSSHAIRS overlay when renderCrosshairInRadialMenu
        // is 0, the default) so no stray "cursor" shows over the ring.
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR,
                previous -> (HudElement) (graphics, deltaTracker) -> {
                    if (!MorphRadial.isShowing()) {
                        previous.extractRenderState(graphics, deltaTracker);
                    }
                });

        // Acquisition suck-in effect: S2C trigger, per-tick motion, and a
        // world-space pass in the level renderer's submit collection.
        Morph.ACQUIRE_FX.listenOnClient(MorphAcquisitions::begin);
        LevelRenderEvents.COLLECT_SUBMITS.register(MorphAcquisitions::submit);

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            MorphDummies.clientTick(minecraft);
            // After MorphDummies (which refreshes the client committed-variant
            // state the applicator reads): clamp the local player's motion for
            // the client-authoritative abilities (float/climb).
            MorphAbilitiesClient.clientTick(minecraft);
            MorphAcquisitions.clientTick(minecraft);
            MorphSelector.clientTick(minecraft);
        });
    }
}
