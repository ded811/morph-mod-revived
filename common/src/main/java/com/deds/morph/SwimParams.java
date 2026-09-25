package com.deds.morph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.squid.Squid;

/**
 * The {@code swim} ability's PARAMETERS (wave-9 item 2, "ability parameters").
 *
 * <p><b>Original.</b> iChun's abilities carried constructor state plus a
 * {@code parse(String[])} fed by the pipe-arg mapping format
 * ({@code "swim|false,1.2,0.4,true"}). {@code AbilitySwim} took
 * {@code (canSurviveOutOfWater, swimSpeed, landSpeed, canMaintainDepth)}
 * ({@code O:morph/common/ability/AbilitySwim.java:26-71}) and <b>hard-clamps
 * {@code swimSpeed} to 1.22F in BOTH the 4-arg constructor ({@code :53-56}) and
 * {@code parse} ({@code :66-69})</b>. The only vanilla mob given arguments was
 * the squid: {@code swim(false, 1.2f, 0.4f, true)}
 * ({@code O:morph/common/ability/AbilityHandler.java:67}); the iron golem got
 * {@code swim(true)} and everything else the no-arg
 * {@code (false, 1f, 1f, false)}.</p>
 *
 * <p><b>Ours.</b> {@link MorphAbility} is a stateless enum, so the parameters
 * live here and are derived generically from the morph's dummy — the same
 * "no per-mob table" rule the whole ability system follows. The derivation
 * reproduces the squid's shipped numbers exactly and extends them to the rest of
 * the strictly-aquatic family, which is the set that already drives our
 * land-drown rule (SPEC deviation D9-4).</p>
 *
 * @param canSurviveOutOfWater true for an air-breathing swimmer (dolphin,
 *                             turtle, axolotl, iron golem); false for a mob that
 *                             suffocates on land (fish, squid)
 * @param swimSpeed            in-water motion multiplier, CLAMPED to 1.22
 * @param landSpeed            out-of-water motion multiplier, applied only once
 *                             {@link #LAND_SLOWDOWN_AIR} air is left
 * @param canMaintainDepth     neutral buoyancy: hold depth instead of bobbing
 */
public record SwimParams(boolean canSurviveOutOfWater, float swimSpeed,
        float landSpeed, boolean canMaintainDepth) {

    /** The original's hard clamp ({@code AbilitySwim.java:53-56, :66-69}). */
    public static final float MAX_SWIM_SPEED = 1.22f;

    /**
     * The original's land-slowdown air gate ({@code AbilitySwim.java:143}):
     * {@code air < 285}, i.e. the slowdown only bites ~15 ticks after leaving
     * the water, so stepping out of a pond does not stick you instantly.
     */
    public static final int LAND_SLOWDOWN_AIR = 285;

    /** The no-arg original: {@code (false, 1f, 1f, false)}
     *  ({@code AbilitySwim.java:32-39}). Used for a morph with no SWIM ability. */
    public static final SwimParams NONE =
            new SwimParams(false, 1f, 1f, false);

    /** {@code swim(true)} — the original's air-breathing swimmer (iron golem). */
    public static final SwimParams AIR_BREATHER =
            new SwimParams(true, 1f, 1f, false);

    /** {@code swim(false, 1.2f, 0.4f, true)} — the original's squid
     *  ({@code AbilityHandler.java:67}). */
    public static final SwimParams AQUATIC =
            new SwimParams(false, 1.2f, 0.4f, true);

    public SwimParams {
        if (swimSpeed > MAX_SWIM_SPEED) {
            swimSpeed = MAX_SWIM_SPEED; // original clamp, both ctor and parse
        }
    }

    /**
     * The parameters a morph of this dummy grants. Strictly aquatic
     * ({@link WaterAnimal} / {@link Squid}, the exact predicate
     * {@code MorphAbilities.isStrictlyAquatic} already uses for the land-drown
     * rule) → {@link #AQUATIC}; any other SWIM-bearing morph → an
     * {@link #AIR_BREATHER}; a morph with no SWIM ability → {@link #NONE}.
     */
    public static SwimParams derive(LivingEntity dummy) {
        if (!MorphAbility.deriveAbilities(dummy).contains(MorphAbility.SWIM)) {
            return NONE;
        }
        return (dummy instanceof WaterAnimal || dummy instanceof Squid)
                ? AQUATIC : AIR_BREATHER;
    }

    /**
     * Whether the out-of-water slowdown applies at this air level — the
     * original's {@code landSpeed != 1f && air < 285} gate
     * ({@code AbilitySwim.java:143}), minus the creative-flight check the caller
     * owns. Pure, so the gate is headlessly gametestable.
     */
    public boolean slowsOnLand(int air) {
        return !canSurviveOutOfWater && landSpeed != 1f
                && air < LAND_SLOWDOWN_AIR;
    }

    /**
     * The original's SOFT multiply ({@code AbilitySwim.java:100-107, 145-152}):
     * a component is scaled only while it is still inside
     * {@code (-factor, factor)}, so the factor is an accelerator that stops
     * boosting (and a brake that stops braking) once you are past it, never a
     * flat multiplier.
     */
    public static double softScale(double component, float factor) {
        return component > -factor && component < factor
                ? component * factor : component;
    }
}
