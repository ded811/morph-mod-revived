package com.deds.morph.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code NearestAttackableTargetGoal.targetType} to mixins on its
 *  subclasses, where an inherited {@code @Shadow} does not resolve. */
@Mixin(NearestAttackableTargetGoal.class)
public interface NearestAttackableTargetGoalAccessor {

    @Accessor("targetType")
    Class<? extends LivingEntity> deds_morph$targetType();
}
