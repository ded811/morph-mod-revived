package com.deds.api.registry;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

import java.util.function.Supplier;

/**
 * Registers {@link MobEffect}s for one mod (namespace fixed to the owning
 * mod's id) — Ded's API v1.2, the mirror of {@code items()}. Same boundary
 * rules as {@link ItemRegistrar}: mod code never writes a registry directly.
 *
 * <p>The returned handle is an {@link EffectHandle}, not a plain
 * {@link RegistryHandle}: the modern effect surface is {@code Holder}-typed
 * everywhere ({@code MobEffectInstance} takes {@code Holder<MobEffect>},
 * {@code LivingEntity.hasEffect/getEffect/removeEffect} take holders), so the
 * handle exposes the registration-time {@link Holder} alongside the raw
 * effect.</p>
 */
public interface EffectRegistrar {

    /**
     * Registers a {@link MobEffect} under {@code <modid>:<name>}. The factory
     * runs once, at registration time (vanilla {@code MobEffect} constructors
     * are protected, so mods pass their own subclass).
     */
    EffectHandle register(String name, Supplier<? extends MobEffect> factory);

    /** A registered effect: the plain handle plus the {@link Holder} the
     *  modern {@code MobEffectInstance}/query surface requires. */
    interface EffectHandle extends RegistryHandle<MobEffect> {

        /** The registration-time holder for this effect. */
        Holder<MobEffect> holder();
    }
}
