package com.deds.morph;

import net.minecraft.core.Holder;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.monster.Enemy;

import java.util.EnumSet;
import java.util.Locale;

/**
 * The 12 passive morph abilities of iChun's Morph, re-derived <b>generically</b>
 * from a modern entity's data/behaviour instead of the original's hardcoded
 * {@code HashMap<Class, ArrayList<Ability>>} table (spec
 * {@code docs/specs/morph/wave2/redesign-abilities-hitbox.md} §A). Any mob —
 * vanilla or modded — gets the right abilities with zero per-mob code because
 * every signal is a real 26.2 property: an entity-type tag, an attribute, a
 * navigation/move-control type, {@code fireImmune()}, {@code isSensitiveToWater()},
 * {@code canBreatheUnderwater()}, {@code canBeAffected(poison)} or the
 * {@code Enemy} interface / {@code MobCategory}.
 *
 * <p>Two signals have <b>no</b> generic 26.2 property and use a small explicit
 * fallback set (spec §A.1/Open Questions): <b>float</b> (chicken slow-fall is
 * inlined in {@code Chicken.aiStep}) → {@code {chicken, parrot}}; and the
 * handful of <b>custom-flight</b> vanilla flyers whose flight is not expressed
 * through {@code FlyingMoveControl}/{@code FlyingPathNavigation}/{@code
 * FLYING_SPEED}/no-gravity (Bat, Phantom, Vex, Blaze, Wither, EnderDragon —
 * javap-verified) → an explicit flyer set the probe is OR-ed with. Ghast +
 * HappyGhast are now caught by the {@code FLYING_SPEED} attribute probe, so they
 * left the explicit set; Vex STAYS (its 26.2 constructor sets no create-time
 * flight signal — it flies via {@code VexMoveControl}).</p>
 *
 * <p>This deliverable uses {@link #deriveAbilities} only to draw each morph's
 * ability icons in the selector; the per-tick effects land in a later wave. The
 * enum name lowercased ({@link #id()}) is exactly the icon file stem
 * ({@code assets/deds_morph/textures/icon/<id>.png}).</p>
 *
 * <p>Recreation of iChun's Morph (original mod by iChun). No {@code net.fabricmc}
 * imports — this is shared server+client code.</p>
 */
public enum MorphAbility {
    CLIMB,
    FLY,
    FLOAT,
    FALL_NEGATE,
    FIRE_IMMUNITY,
    HOSTILE,
    POISON_RESISTANCE,
    STEP,
    SUNBURN,
    SWIM,
    WATER_ALLERGY,
    WITHER_RESISTANCE;

    /** The default player step-height attribute value (26.2 STEP_HEIGHT default);
     *  only a morph whose real step attribute EXCEEDS it grants the step ability. */
    private static final double PLAYER_STEP_HEIGHT = 0.6;

    /** Lowercase id, identical to this ability's icon file stem
     *  ({@code textures/icon/<id>.png}) and the original ability keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The abilities a morph of this entity would grant, derived purely from the
     * dummy {@link LivingEntity}'s modern properties (spec §A.2). Pure and
     * side-effect free; identical on both sides given the same reconstructed
     * dummy. The dummy must have its attributes initialised (a real spawned or
     * {@code EntityType.create}-built mob does).
     */
    public static EnumSet<MorphAbility> deriveAbilities(LivingEntity dummy) {
        EntityType<?> type = dummy.getType();
        Holder.Reference<EntityType<?>> holder = type.builtInRegistryHolder();
        EnumSet<MorphAbility> out = EnumSet.noneOf(MorphAbility.class);

        if (type.fireImmune()) {
            out.add(FIRE_IMMUNITY);
        }
        // Fire-immune undead (wither skeleton) do NOT burn in daylight.
        if (holder.is(EntityTypeTags.BURN_IN_DAYLIGHT) && !type.fireImmune()) {
            out.add(SUNBURN);
        }
        // Tag covers undead; canBeAffected covers spiders (poison-immune) and
        // any modded mob that overrides poison immunity — the faithful generic
        // superset of the original's per-class poisonResistance.
        if (holder.is(EntityTypeTags.IGNORES_POISON_AND_REGEN)
                || !dummy.canBeAffected(
                        new MobEffectInstance(MobEffects.POISON, 1))) {
            out.add(POISON_RESISTANCE);
        }
        if (holder.is(EntityTypeTags.INVERTED_HEALING_AND_HARM)
                || dummy.isInvertedHealAndHarm()) {
            out.add(WITHER_RESISTANCE);
        }
        if (holder.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
            out.add(FALL_NEGATE);
        }
        // Aquatic swim: the AQUATIC tag (fish/squid/dolphin/guardian/turtle/
        // axolotl), modded fish/squid by class, plus the one water-dwelling
        // undead (drowned). NOTE: the CAN_BREATHE_UNDER_WATER tag cannot gate
        // SWIM on its own — it CONTAINS #minecraft:undead, so EVERY land undead
        // (plain zombie/skeleton) is in it and canBreatheUnderwater() (which
        // reads the tag) returns true for them. Gating on those wrongly gave a
        // plain zombie swim + dolphin's-grace (verified against the 26.2 tag
        // data). Subtracting #undead makes the tag usable, and that is what
        // closes the two audit MISSES (2026-07-29 ability audit): frog and
        // copper_golem genuinely breathe underwater in 26.2 but are not in
        // #aquatic, so they had no SWIM at all. (armor_stand is in the tag too
        // but is not acquirable — acquireTarget takes only Player/Mob.)
        if (holder.is(EntityTypeTags.AQUATIC)
                || (holder.is(EntityTypeTags.CAN_BREATHE_UNDER_WATER)
                        && !holder.is(EntityTypeTags.UNDEAD))
                || dummy instanceof WaterAnimal
                || dummy instanceof Squid
                || type == EntityTypes.DROWNED) {
            out.add(SWIM);
        }
        if (dummy.isSensitiveToWater()) {
            out.add(WATER_ALLERGY);
        }
        if (dummy instanceof Enemy
                || type.getCategory() == MobCategory.MONSTER) {
            out.add(HOSTILE);
        }
        if (dummy.getAttributeValue(Attributes.STEP_HEIGHT) > PLAYER_STEP_HEIGHT) {
            out.add(STEP);
        }
        if (dummy instanceof Mob mob) {
            // WALL-CLIMB ONLY (playtest bug 1, 2026-07-29). This used to be
            // OR-ed with EntityTypeTags.ARTHROPOD "as a fallback"
            // (wave-2 spec §A.1 #1) — but #minecraft:arthropod is
            // {bee, endermite, silverfish, spider, cave_spider}, and only the
            // two spiders climb. That handed a wall-climb to endermite,
            // silverfish and bee, none of which can climb anything in vanilla
            // (the user reported the endermite). iChun's table agrees: only
            // EntitySpider and EntityCaveSpider get AbilityClimb, and
            // EntitySilverfish gets AbilityHostile alone
            // (Morph legacy, morph/common/ability/AbilityHandler.java:58,71,75).
            // WallClimberNavigation is the exact 26.2 signal: a jar-wide scan of
            // net/minecraft/world/entity finds exactly two referencing classes —
            // the navigation itself and Spider (CaveSpider inherits Spider's
            // createNavigation) — so the probe alone is complete for vanilla and
            // still "just works" for any modded wall-climber.
            if (mob.getNavigation() instanceof WallClimberNavigation) {
                out.add(CLIMB);
            }
            // Generic flyer probe (no #minecraft: flying tag exists in 26.2):
            //  - FlyingMoveControl / FlyingPathNavigation → Bee, Allay, Parrot
            //  - FLYING_SPEED attribute → Ghast, HappyGhast, Allay, Bee, Parrot
            //    (+ any modded flyer that registers it — "just works")
            //  - isNoGravity() → Vex (constructor flag, restored in MorphEntities)
            //  - isCustomFlyer → the genuinely signal-less vanilla flyers
            if (mob.getMoveControl() instanceof FlyingMoveControl
                    || mob.getNavigation() instanceof FlyingPathNavigation
                    || dummy.getAttribute(Attributes.FLYING_SPEED) != null
                    || dummy.isNoGravity()
                    || isCustomFlyer(type)) {
                out.add(FLY);
            }
        }
        if (isFloater(type)) {
            out.add(FLOAT);
        }
        return out;
    }

    /**
     * The genuinely signal-less vanilla flyers — no {@code FlyingMoveControl}/
     * {@code FlyingPathNavigation}, no {@code FLYING_SPEED} attribute, no
     * constructor no-gravity flag / zero-GRAVITY attribute (custom
     * {@code MoveControl}s / direct motion in {@code aiStep}, javap-verified this
     * session). Everything else that flies is caught by the generic probe:
     * Ghast/HappyGhast via {@code FLYING_SPEED}; Allay/Bee/Parrot via
     * {@code FlyingMoveControl}+{@code FlyingPathNavigation}. Phantom and <b>Vex</b>
     * have ZERO create-time signal (Vex flies via {@code VexMoveControl} — its
     * 26.2 constructor sets NEITHER the no-gravity flag NOR a GRAVITY attribute,
     * contrary to older sources), so both stay here.
     */
    private static boolean isCustomFlyer(EntityType<?> type) {
        return type == EntityTypes.BAT
                || type == EntityTypes.PHANTOM
                || type == EntityTypes.VEX
                || type == EntityTypes.BLAZE
                || type == EntityTypes.WITHER
                || type == EntityTypes.ENDER_DRAGON;
    }

    /**
     * The slow-fall "float" ability has no generic 26.2 property (chicken
     * slow-fall is inlined in {@code Chicken.aiStep}). Explicit fallback set per
     * spec §A.1/Open Questions.
     */
    private static boolean isFloater(EntityType<?> type) {
        return type == EntityTypes.CHICKEN || type == EntityTypes.PARROT;
    }
}
