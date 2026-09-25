package com.deds.morph.neoforge.mixin;

import com.deds.morph.client.MorphRadial;
import com.deds.morph.client.MorphSelector;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SubtitleOverlay;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Draws Morph's selector strip and favourites radial where Fabric API draws
 * them on Fabric. {@code MorphFabricClient} adds both with
 * {@code HudElementRegistry.addLast}, which appends them to the SUBTITLES
 * root layer; Fabric API renders that layer from a {@code @WrapMethod} around
 * {@code SubtitleOverlay.extractRenderState}: vanilla's subtitles first, then
 * the added elements in the order they were added. This is the same wrapper
 * on the same method, calling the same two renderers in the same order.
 *
 * <p>Why the subtitle overlay and not a NeoForge HUD layer: vanilla only
 * calls it while the HUD is visible (or an in-game UI screen is open), so the
 * selector disappears with F1 as on Fabric, and with no screen open vanilla
 * defers the call until after the toasts and the F3 screen, so the selector
 * is drawn over them as on Fabric. NeoForge keeps both of those gates on its
 * subtitle layer. {@code RegisterGuiLayersEvent.registerAboveAll} would do
 * neither (drawn with F1, and under toasts and F3). NeoForge does not patch
 * {@code SubtitleOverlay}.</p>
 *
 * <p>{@code @WrapMethod}, not an inject at TAIL: vanilla's method has three
 * returns (javap, both versions) and TAIL would bind only the last of them.
 * NeoForge's recompile happens to merge them into one, but the wrapper is
 * right whatever the method's shape.</p>
 */
@Mixin(SubtitleOverlay.class)
public abstract class SubtitleOverlayHudMixin {

    @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V")
    private void deds_morph$drawMorphHud(GuiGraphicsExtractor graphics,
            Operation<Void> original) {
        original.call(graphics);
        DeltaTracker deltaTracker = Minecraft.getInstance().getDeltaTracker();
        MorphSelector.render(graphics, deltaTracker);
        MorphRadial.render(graphics, deltaTracker);
    }
}
