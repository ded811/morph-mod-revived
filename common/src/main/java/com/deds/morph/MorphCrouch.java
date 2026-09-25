package com.deds.morph;

import net.minecraft.world.entity.EntityDimensions;

/**
 * Shared crouch-shrink geometry for humanoid morphs (spec: modern-MC sneak).
 * A tall biped morph (zombie, skeleton, wither-skeleton, enderman, …) must
 * SHRINK its collision box when crouching so the player fits under a 1.5-tall
 * gap and the camera drops to match — the getDimensions mixin (both sides) and
 * the client eye-height mixin both use these so collision + camera agree.
 *
 * <p>No {@code net.fabricmc} — shared server + client. Recreation of iChun's
 * Morph.</p>
 */
public final class MorphCrouch {

    /** Crouch height factor: a ~1.95-tall zombie morph shrinks to ~1.5 (vanilla
     *  player sneak feel); taller mobs shrink proportionally (wither-skeleton
     *  2.4 → ~1.85, enderman 2.9 → ~2.23). */
    private static final float CROUCH_RATIO = 1.5f / 1.95f;

    /** Below this height / above this width a morph is NOT treated as a crouching
     *  biped (server-safe proxy for "uses a HumanoidModel": tall + narrow). Short
     *  or wide morphs (cow, spider, iron golem, slime) just dip, never shrink. */
    private static final float MIN_HUMANOID_HEIGHT = 1.6f;
    private static final float MAX_HUMANOID_WIDTH = 0.8f;

    private MorphCrouch() {
    }

    /**
     * Whether a morph with these standing dimensions is a tall narrow biped that
     * should crouch-shrink (the collision + camera counterpart of the client's
     * {@code HumanoidModel} crouch pose). Server-safe (no renderer).
     */
    public static boolean isHumanoidCrouch(EntityDimensions standing) {
        return standing.height() >= MIN_HUMANOID_HEIGHT
                && standing.width() <= MAX_HUMANOID_WIDTH;
    }

    /** The shrunk crouch dimensions for a humanoid morph: same width, height and
     *  eye scaled by {@link #CROUCH_RATIO}. */
    public static EntityDimensions crouch(EntityDimensions standing) {
        return EntityDimensions
                .scalable(standing.width(), standing.height() * CROUCH_RATIO)
                .withEyeHeight(standing.eyeHeight() * CROUCH_RATIO);
    }

    /** The crouch eye height for a humanoid morph (matches {@link #crouch}). */
    public static float crouchEyeHeight(EntityDimensions standing) {
        return standing.eyeHeight() * CROUCH_RATIO;
    }
}
