package com.deds.morph.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code LivingEntity.getHurtSound(DamageSource)} is {@code protected}
 * (javap-verified against the merged 26.2 jar:
 * {@code (Lnet/minecraft/world/damagesource/DamageSource;)Lnet/minecraft/sounds/SoundEvent;}),
 * so this {@link Invoker} exposes it — the morph hurt sound (wave-9 item 8) asks
 * a morph's never-spawned dummy for its OWN voice.
 *
 * <p>Declared on {@code LivingEntity} and invoked on a subclass instance, so the
 * call dispatches virtually to whatever override the morph's real class has
 * ({@code Zombie} → {@code ZOMBIE_HURT}, {@code Monster} → {@code HOSTILE_HURT},
 * {@code LivingEntity} → {@code GENERIC_HURT}). The morph/mech/hurt-sound
 * gametest asserts a zombie morph yields {@code ZOMBIE_HURT}, which is exactly
 * the assertion that would fail if this bound non-virtually.</p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityHurtSoundInvoker {

    @Invoker("getHurtSound")
    SoundEvent deds_morph$getHurtSound(DamageSource source);
}
