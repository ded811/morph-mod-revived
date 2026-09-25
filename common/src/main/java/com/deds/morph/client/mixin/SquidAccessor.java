package com.deds.morph.client.mixin;

import net.minecraft.world.entity.animal.squid.Squid;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code Squid}'s private {@code tentacleSpeed}/{@code rotateSpeed}
 * (javap-verified field names, both {@code private float}) so the never-ticked
 * squid morph dummy can run the vanilla-exact client-visible replication of
 * {@code Squid.aiStep} — the tentacle power-stroke/recovery cycle and the body
 * roll bursts — in {@code MorphDummies.advanceAnimation} (wave 6 round 2, item
 * "squid orientation"). GlowSquid extends Squid, so it is covered.
 *
 * <p>Recreation of iChun's Morph; all credit for the original design to iChun.</p>
 */
@Mixin(Squid.class)
public interface SquidAccessor {

    @Accessor("tentacleSpeed")
    float deds_morph$tentacleSpeed();

    @Accessor("tentacleSpeed")
    void deds_morph$setTentacleSpeed(float value);

    @Accessor("rotateSpeed")
    float deds_morph$rotateSpeed();

    @Accessor("rotateSpeed")
    void deds_morph$setRotateSpeed(float value);
}
