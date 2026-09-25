package com.deds.morph.mixin;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code TargetingConditions.selector} (private, javap-verified) so the
 * TARGET bridge can run a goal's own species selector against a morph probe dummy
 * (spec §A.4 selector wall).
 */
@Mixin(TargetingConditions.class)
public interface TargetingConditionsAccessor {

    @Accessor("selector")
    TargetingConditions.Selector deds_morph$selector();
}
