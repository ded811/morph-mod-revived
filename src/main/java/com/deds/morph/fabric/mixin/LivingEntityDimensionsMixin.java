package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphCrouch;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphVariant;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Hitbox/eye/camera adoption (spec Part B). {@code LivingEntity.getDimensions}
 * is {@code final} but still bytecode-targetable; while a player is morphed
 * (committed — the transition is complete) this returns the MORPH's
 * {@link EntityDimensions} instead of the vanilla 0.6×1.8 box, so
 * {@code refreshDimensions()} rebuilds the AABB and the eye-height field from it
 * (fixing the reported "hitbox + camera never change" bug). Runs on BOTH sides
 * off the synced {@link com.deds.morph.MorphState}, so client + server agree.
 *
 * <p>Only the upright poses (STANDING/CROUCHING/SWIMMING) are overridden;
 * SLEEPING/DYING/elytra defer to vanilla to avoid bed/animation glitches
 * (spec §B.4).</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDimensionsMixin {

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphDimensions(Pose pose,
            CallbackInfoReturnable<EntityDimensions> cir) {
        if (!((Object) this instanceof Player player)) {
            return;
        }
        if (pose != Pose.STANDING && pose != Pose.CROUCHING
                && pose != Pose.SWIMMING) {
            return;
        }
        Optional<MorphVariant> variant = MorphAbilities.committedVariant(player);
        if (variant.isEmpty()) {
            return;
        }
        EntityDimensions dims =
                MorphEntities.dimensionsOf(variant.get(), player.level());
        if (dims != null) {
            // Modern-MC crouch: a tall humanoid morph SHRINKS when crouching so
            // the player fits under a 1.5-tall gap and the camera drops to match
            // (the shrunk eye rides along via refreshDimensions). Short/wide
            // morphs keep their standing box (they just dip visually).
            if (pose == Pose.CROUCHING && MorphCrouch.isHumanoidCrouch(dims)) {
                dims = MorphCrouch.crouch(dims);
            }
            cir.setReturnValue(dims);
        }
    }
}
