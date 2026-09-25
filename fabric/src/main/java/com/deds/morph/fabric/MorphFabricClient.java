package com.deds.morph.fabric;

import com.deds.morph.Morph;
import com.deds.morph.client.MorphAcquisitions;
import com.deds.morph.client.MorphClient;
import com.deds.morph.client.MorphRadial;
import com.deds.morph.client.MorphSelector;

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
 * Fabric client entrypoint for Morph: {@link MorphClient#init()} (keys, input
 * listeners, the acquisition-effect receiver, all loader-neutral), then the
 * Fabric glue that has no loader-neutral form: the two HUD elements, the
 * crosshair suppression, the level-render hook and the end-of-tick hook.
 *
 * <p>Before the loader split this was all one class,
 * {@code com.deds.morph.fabric.client.MorphClient}. The registrations are the
 * ones it made, in the order it made them, except that the acquisition-effect
 * receiver (now inside {@link MorphClient#init()}) is registered before the
 * HUD elements instead of after them: independent registries, and nothing is
 * drawn or received before every client entrypoint has returned.</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MorphClient.init();

        // addLast appends to the SUBTITLES root layer, drawn after vanilla's
        // subtitles: selector first, radial over it.
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

        // The acquisition effect's world-space pass, in the level renderer's
        // submit collection. The three values are plain reads of what the
        // context holds (the submitFeatures pose stack, the submit node
        // storage, the level render state's camera position), exactly what
        // MorphAcquisitions read off the context itself before the split.
        LevelRenderEvents.COLLECT_SUBMITS.register(context ->
                MorphAcquisitions.submit(context.poseStack(),
                        context.submitNodeCollector(),
                        context.levelState().cameraRenderState.pos));

        ClientTickEvents.END_CLIENT_TICK.register(MorphClient::clientTick);
    }
}
