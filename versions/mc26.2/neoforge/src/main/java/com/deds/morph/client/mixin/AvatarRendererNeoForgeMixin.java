package com.deds.morph.client.mixin;

import com.deds.morph.client.MorphHands;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first-person morph hand on NeoForge for Minecraft 26.2 ONLY: the
 * counterpart of the shared {@code AvatarRendererMixin}, which Fabric (both
 * versions) and NeoForge 26.3 use.
 *
 * <p>NeoForge 26.2 adds a 6-argument
 * {@code renderRightHand/renderLeftHand(..., Avatar)} and turns vanilla's
 * 5-argument pair into a deprecated shim that only forwards to it; nothing in
 * Minecraft or NeoForge calls the shim ({@code ItemInHandRenderer} calls the
 * 6-argument methods directly). The shared 5-argument hook would therefore
 * APPLY and never RUN: the vanilla arm, silently. This hooks the 6-argument
 * descriptors instead (their last parameter is the renderer's type variable,
 * erased to {@link Avatar}), with the same body. Never both on 26.2: a
 * third-party call of the shim would then enter {@link MorphHands} twice.</p>
 *
 * <p>Lives only in {@code versions/mc26.2/neoforge/}: NeoForge 26.3 has only
 * the vanilla pair, so its hand config lists the shared mixin, and no
 * NeoForge 26.3 build ever sees this class. Listed by the 26.2
 * {@code deds_morph.neoforge.hand.mixins.json}, beside it.</p>
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererNeoForgeMixin {

    @Inject(method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/resources/Identifier;Z"
            + "Lnet/minecraft/world/entity/Avatar;)V",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphRightHand(PoseStack poseStack,
            SubmitNodeCollector collector, int lightCoords,
            Identifier skinTexture, boolean hasSleeve, Avatar avatar,
            CallbackInfo ci) {
        MorphHands.submitHand((AvatarRenderer<?>) (Object) this, poseStack,
                collector, lightCoords, true, ci);
    }

    @Inject(method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
            + "Lnet/minecraft/resources/Identifier;Z"
            + "Lnet/minecraft/world/entity/Avatar;)V",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphLeftHand(PoseStack poseStack,
            SubmitNodeCollector collector, int lightCoords,
            Identifier skinTexture, boolean hasSleeve, Avatar avatar,
            CallbackInfo ci) {
        MorphHands.submitHand((AvatarRenderer<?>) (Object) this, poseStack,
                collector, lightCoords, false, ci);
    }
}
