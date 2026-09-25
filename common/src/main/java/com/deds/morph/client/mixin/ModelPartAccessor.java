package com.deds.morph.client.mixin;

import net.minecraft.client.model.geom.ModelPart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * Read-only access to {@link ModelPart}'s private geometry lists. Needed to
 * deep-copy a renderer's live model tree (cubes are immutable and shared;
 * only the mutable pose fields are per-copy) and to sample cube extents for
 * the transformation's part-size lerp and "budding" spawn points.
 *
 * <p>The baked children map is an insertion-ordered
 * {@code Object2ObjectArrayMap} built once per bake from the same mesh, so
 * its iteration order is deterministic and identical between the shared
 * renderer tree and any copy of it — the part-pairing order the morph
 * transformation relies on. TODO(deds-api): lift — "walk/copy another
 * entity's model tree" is a client API candidate.</p>
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {

    @Accessor("cubes")
    List<ModelPart.Cube> deds_morph$cubes();

    @Accessor("children")
    Map<String, ModelPart> deds_morph$children();
}
