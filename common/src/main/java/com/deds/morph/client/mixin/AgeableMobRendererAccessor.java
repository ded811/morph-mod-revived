package com.deds.morph.client.mixin;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.AgeableMobRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the private adult/baby model fields of {@link AgeableMobRenderer}
 * (zombies, most humanoid and animal mobs). Baby rendering there is a MODEL
 * SWAP, not a scale hook: {@code submit()} sets {@code this.model =
 * state.isBaby ? babyModel : adultModel} at DRAW time, and {@code getModel()}
 * returns that shared, last-written field. The morph transformation builds
 * its interim rig at extract time from {@code getModel()}, so a nearby baby
 * (or the previous frame) leaves {@code this.model} pointing at the wrong
 * tree — an adult morph then animates as a baby and snaps to adult at the
 * end (variant playtest bug, 2026-07-22). Selecting the correct model per
 * the form's own {@code isBaby} makes the rig deterministic and race-free.
 */
@Mixin(AgeableMobRenderer.class)
public interface AgeableMobRendererAccessor {

    @Accessor("adultModel")
    EntityModel<?> deds_morph$adultModel();

    @Accessor("babyModel")
    EntityModel<?> deds_morph$babyModel();
}
