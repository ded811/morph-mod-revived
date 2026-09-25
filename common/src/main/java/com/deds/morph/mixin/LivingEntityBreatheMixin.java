package com.deds.morph.mixin;

import com.deds.morph.MorphAbility;
import com.deds.morph.MorphAbilities;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The {@code swim} ability's underwater-breathing half (spec §A.1 #10). Vanilla
 * {@code LivingEntity.baseTick} decides air loss by calling
 * {@code canBreatheUnderwater()} (javap-verified: baseTick invokes the method,
 * not the tag directly), so overriding it to {@code true} while a player wears a
 * SWIM morph makes vanilla never decrement their air underwater — the fix for
 * "fish morph shows air bubbles underwater". The LAND suffocation of a
 * strictly-aquatic morph is unaffected (that path is out of water, where this
 * method is not consulted). Guarded to players.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBreatheMixin {

    @Inject(method = "canBreatheUnderwater", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphBreathesUnderwater(
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Player player
                && MorphAbilities.activeAbilities(player)
                        .contains(MorphAbility.SWIM)) {
            cir.setReturnValue(true);
        }
    }
}
