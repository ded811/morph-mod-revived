package com.deds.morph.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code TargetGoal.mob} (protected). {@code NearestAttackableTargetGoal}
 * extends {@code TargetGoal}, so its mixin (whose {@code this} is a
 * {@code TargetGoal}) reads the goal's owning mob through this accessor —
 * inherited-field {@code @Shadow} does not resolve to the superclass in this
 * dev/no-refmap setup.
 */
@Mixin(TargetGoal.class)
public interface TargetGoalAccessor {

    @Accessor("mob")
    Mob deds_morph$mob();
}
