package com.deds.morph.neoforge;

import com.deds.morph.Morph;
import com.deds.morph.client.MorphAcquisitions;
import com.deds.morph.client.MorphClient;
import com.deds.morph.client.MorphRadial;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.Minecraft;

/**
 * NeoForge client entrypoint for Morph, the twin of {@code MorphFabricClient}:
 * {@link MorphClient#init()} (keys, input listeners, the acquisition-effect
 * receiver, all loader-neutral), then the NeoForge glue for the four things
 * that have no loader-neutral form, each at the point Fabric's glue uses:
 * <ul>
 * <li><b>The selector and radial HUD</b>: not here. Fabric API draws
 *     {@code HudElementRegistry.addLast} elements inside a wrapper around
 *     {@code SubtitleOverlay.extractRenderState}, after vanilla's subtitles,
 *     so they are hidden with F1 and drawn over toasts and the F3 screen.
 *     NeoForge's {@code registerAboveAll} differs on both counts, so
 *     {@code SubtitleOverlayHudMixin} (deds_morph.neoforge.mixins.json)
 *     wraps the same method instead.</li>
 * <li><b>The crosshair</b>, hidden while the favourites radial shows:
 *     {@code wrapLayer} on NeoForge's crosshair layer (the same
 *     {@code minecraft:crosshair} id as Fabric's), which keeps the layer's
 *     own "HUD visible" gate around the original.</li>
 * <li><b>The acquisition effect's world-space pass</b>:
 *     {@code SubmitCustomGeometryEvent}, posted inside
 *     {@code LevelRenderer.submitFeatures} with the same pose stack, submit
 *     node collector and level render state Fabric's
 *     {@code LevelRenderEvents.COLLECT_SUBMITS} hands over. (It fires just
 *     before the gizmo submits instead of after them; Morph submits no
 *     outline, so nothing can tell.)</li>
 * <li><b>The end-of-tick hook</b>: {@code ClientTickEvent.Post}, the end of
 *     every client tick like Fabric's {@code END_CLIENT_TICK}, except that
 *     NeoForge does not post it before the first resource load finishes
 *     (no world, so no Morph tick has anything to do then).</li>
 * </ul>
 *
 * <p><b>When {@link MorphClient#init()} runs.</b> Not in this constructor: at
 * {@code RegisterKeyMappingsEvent}, {@link EventPriority#HIGHEST}, on Morph's
 * own bus. Fabric runs every "main" entrypoint before any "client" one, and
 * {@code init()} relies on that ({@code Morph.ACQUIRE_FX} is set by Morph's
 * {@code onInitialize}). A NeoForge mod constructor gives no such promise:
 * a newer Ded's API, which another Ded's mod may bring along (NeoForge keeps
 * the newest copy of every nested deds_api), runs {@code onInitialize} inside
 * NeoForge's registry events, after every mod is constructed. That event
 * fires once, on the main thread, after every registry event whichever API
 * version runs Morph's {@code onInitialize}, and HIGHEST puts the keys ahead
 * of Ded's API's own LOWEST listener, which turns the queued bindings into
 * real key mappings. The other three hooks below only register listeners,
 * which is safe at construction.</p>
 */
@Mod(value = Morph.MOD_ID, dist = Dist.CLIENT)
public final class MorphNeoForgeClient {

    public MorphNeoForgeClient(IEventBus modBus) {
        modBus.addListener(EventPriority.HIGHEST, RegisterKeyMappingsEvent.class,
                event -> MorphClient.init());

        modBus.addListener(RegisterGuiLayersEvent.class, event ->
                event.wrapLayer(VanillaGuiLayers.CROSSHAIR,
                        previous -> (GuiLayer) (graphics, deltaTracker) -> {
                            if (!MorphRadial.isShowing()) {
                                previous.render(graphics, deltaTracker);
                            }
                        }));

        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, event ->
                MorphAcquisitions.submit(event.getPoseStack(),
                        event.getSubmitNodeCollector(),
                        event.getLevelRenderState().cameraRenderState.pos));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> MorphClient.clientTick(Minecraft.getInstance()));
    }
}
