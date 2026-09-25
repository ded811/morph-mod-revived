package com.deds.morph.fabric.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code AvoidEntityGoal.toAvoid} (the entity the goal is currently fleeing)
 * so a gametest can deterministically assert the AVOID bridge (spec §A.3) fed a
 * morphed player into the goal — {@code canUse} assigns {@code toAvoid} from the
 * scan (which our {@code @Redirect} augments) before the flee-path check, so this
 * reflects "picked to avoid" independent of vanilla pathfinding RNG.
 */
@Mixin(AvoidEntityGoal.class)
public interface AvoidEntityGoalAccessor {

    @Accessor("toAvoid")
    LivingEntity deds_morph$toAvoid();
}
