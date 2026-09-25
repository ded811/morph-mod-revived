package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphAbilities;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The {@code hostile} ability's target-cancel hook (spec §A.1 #6 / §A.3). Fabric
 * has no {@code LivingSetAttackTargetEvent}, so this mixin on
 * {@code Mob.setTarget(LivingEntity)} is the closest 1:1 to the original
 * {@code EventHandler.onLivingSetAttackTarget}: when a hostile mob tries to
 * target a player morphed as a hostile mob, {@link MorphAbilities} decides
 * (per {@code hostileAbilityMode} 0–4 + {@code hostileAbilityDistanceCheck})
 * whether to cancel the retarget. Server-side only. Mode 0 (the default) is ON
 * here - every hostile mob ignores a hostile-shaped player - unlike the
 * original, where 0 meant off; only {@code abilities=false} turns it off.
 */
@Mixin(Mob.class)
public abstract class MobSetTargetMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void deds_morph$cancelHostileTarget(LivingEntity target,
            CallbackInfo ci) {
        if (target == null) {
            return;
        }
        Mob mob = (Mob) (Object) this;
        if (MorphAbilities.shouldCancelTarget(mob, target)) {
            ci.cancel();
        }
    }
}
