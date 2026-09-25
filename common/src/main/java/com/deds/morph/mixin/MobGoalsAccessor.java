package com.deds.morph.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code Mob.goalSelector} (protected, javap-verified) so a gametest can
 * reach a mob's goals and read {@code AvoidEntityGoal.toAvoid} for the AVOID-bridge
 * assertion (spec §A.6). Test-support only; no production caller.
 */
@Mixin(Mob.class)
public interface MobGoalsAccessor {

    @Accessor("goalSelector")
    GoalSelector deds_morph$goalSelector();
}
