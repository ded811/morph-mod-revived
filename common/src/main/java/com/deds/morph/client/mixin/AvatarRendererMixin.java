package com.deds.morph.client.mixin;

import com.deds.morph.client.MorphHands;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person hand override surface (the camera entity is never
 * world-extracted, so the dispatcher mixin cannot cover it). These two
 * methods are only invoked by ItemInHandRenderer for the local player's
 * EMPTY hands — the same scope the original's hand override had; all
 * policy lives in {@link MorphHands}.
 *
 * <p>Both injectors name the FULL 5-argument descriptor. On Fabric (both
 * versions) that is the only overload, so the binding is the same one the
 * bare method names always produced; spelling it out means a second overload
 * can never take the injection over. For the same reason this mixin is not in
 * the shared {@code deds_morph.client.mixins.json}: NeoForge 26.2 adds a
 * 6-argument overload and turns this one into a shim nothing calls, so each
 * loader lists the hand mixin in its own config
 * ({@code deds_morph.fabric.mixins.json} on Fabric, the 26.3
 * {@code deds_morph.neoforge.hand.mixins.json} on NeoForge 26.3), and NeoForge
 * 26.2 hooks the 6-argument pair instead, with
 * {@code AvatarRendererNeoForgeMixin} (versions/mc26.2/neoforge/).</p>
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphRightHand(PoseStack poseStack,
            SubmitNodeCollector collector, int lightCoords,
            Identifier skinTexture, boolean hasSleeve, CallbackInfo ci) {
        MorphHands.submitHand((AvatarRenderer<?>) (Object) this, poseStack,
                collector, lightCoords, true, ci);
    }

    @Inject(method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphLeftHand(PoseStack poseStack,
            SubmitNodeCollector collector, int lightCoords,
            Identifier skinTexture, boolean hasSleeve, CallbackInfo ci) {
        MorphHands.submitHand((AvatarRenderer<?>) (Object) this, poseStack,
                collector, lightCoords, false, ci);
    }
}
