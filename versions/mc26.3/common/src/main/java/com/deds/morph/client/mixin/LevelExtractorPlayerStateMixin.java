package com.deds.morph.client.mixin;

import com.deds.morph.client.MorphDummies;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 only (versions/mc26.3; registered in deds_morph.version.mixins.json).
 *
 * <p>26.3 builds a per-frame {@code PlayerRenderState} for the LOCAL player in
 * {@code LevelExtractor.extractPlayerState}: it extracts the player through
 * the entity render dispatcher and REQUIRES an {@code AvatarRenderState}
 * back - "Expected an AvatarRenderState for the local player" otherwise -
 * for the first-person hands and items and the screen effects (portal,
 * nausea, underwater). Morph's {@code EntityRenderDispatcherMixin} swaps a
 * morphed player's extraction for the morph mob's, so on 26.3 a morphed
 * local player crashed the render frame.</p>
 *
 * <p>This call, and only this one, extracts with the swap switched off, so
 * the player-state gets the real player's avatar state. The world render of
 * the player - what other players see, and the player in third person - is
 * a separate extraction and still shows the morph.</p>
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorPlayerStateMixin {

    // WrapOperation, not Redirect: another mod wrapping the same call still works.
    @WrapOperation(method = "extractPlayerState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;"
                            + "extractEntity(Lnet/minecraft/world/entity/Entity;F)"
                            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState deds_morph$extractRealPlayerState(LevelExtractor self,
            Entity entity, float partialTickTime,
            Operation<EntityRenderState> original) {
        return MorphDummies.rawExtract(
                () -> original.call(self, entity, partialTickTime));
    }
}
