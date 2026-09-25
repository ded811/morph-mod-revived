package com.deds.morph.fabric.client.mixin;

import com.deds.morph.fabric.client.MorphHands;

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
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "renderRightHand", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphRightHand(PoseStack poseStack,
            SubmitNodeCollector collector, int lightCoords,
            Identifier skinTexture, boolean hasSleeve, CallbackInfo ci) {
        MorphHands.submitHand((AvatarRenderer<?>) (Object) this, poseStack,
                collector, lightCoords, true, ci);
    }

    @Inject(method = "renderLeftHand", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphLeftHand(PoseStack poseStack,
            SubmitNodeCollector collector, int lightCoords,
            Identifier skinTexture, boolean hasSleeve, CallbackInfo ci) {
        MorphHands.submitHand((AvatarRenderer<?>) (Object) this, poseStack,
                collector, lightCoords, false, ci);
    }
}
