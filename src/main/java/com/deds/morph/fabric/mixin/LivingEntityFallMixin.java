package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphAbilities;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The {@code fallNegate} / {@code float} abilities' authoritative fall-damage
 * cut-off (spec §A.1 #3/#4). Cancelling {@code causeFallDamage} (returning
 * {@code false} = no damage taken) is more robust than merely zeroing
 * {@code fallDistance} each tick — it wins even against a single big fall in one
 * tick. Guarded to morphed players whose committed set negates fall damage.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallMixin {

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void deds_morph$negateFall(double fallDistance, float multiplier,
            DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Player player
                && MorphAbilities.negatesFall(player)) {
            cir.setReturnValue(false);
        }
    }
}
