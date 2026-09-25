package com.deds.morph;

import com.deds.api.Deds;
import com.deds.morph.api.Ability;
import com.deds.morph.api.AbilityRegistry;
import com.deds.morph.mixin.LivingEntityHurtSoundInvoker;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared (server + client, NO {@code net.fabricmc}) morph-entity helper: builds
 * a display/probe-only "dummy" {@link LivingEntity} for a {@link MorphVariant}
 * and caches the derived, level-independent {@link Profile} (passive abilities,
 * collision dimensions, step height) per variant.
 *
 * <p>The dummy builder was lifted verbatim out of
 * {@code com.deds.morph.client.MorphDummies} (which now delegates here)
 * so the SERVER ability/hitbox system can construct the same never-spawned mob
 * the client renders — {@link MorphAbility#deriveAbilities} needs a real
 * attribute-initialised {@code LivingEntity}, and the hitbox mixin needs the
 * morph's {@link EntityDimensions}. Both are pure functions of the variant, so
 * the {@link Profile} is cached by {@link MorphVariant} and the transient dummy
 * discarded — deriving abilities / reading dimensions is then a map lookup.</p>
 *
 * <p>Recreation of iChun's Morph. All credit for the original design to iChun.</p>
 */
public final class MorphEntities {

    /**
     * Derived, level-independent facts about a morph, cached per variant.
     *
     * @param abilities        the passive abilities this morph grants (spec §A.2)
     * @param dimensions       the morph's collision box + eye height (null when the
     *                         variant's entity type could not be built)
     * @param stepHeight       the morph's {@code STEP_HEIGHT} attribute value, for
     *                         the {@code step} ability's transient modifier
     * @param baby             whether the variant is a baby (skips step/sunburn,
     *                         per the original's baby checks)
     * @param passengerOffsets the morph's PASSENGER attachment seats (local frame),
     *                         one per rider slot — a horse has one, a happy ghast up
     *                         to four; empty when the morph has no passenger point.
     *                         The ride-position mixin seats rider {@code i} at
     *                         {@code passengerOffsets.get(min(i, size-1))}.
     * @param swim             the {@code swim} ability's parameters (wave-9 item 2);
     *                         {@link SwimParams#NONE} for a morph without SWIM
     * @param hurtSound        the morph's own hurt sound (wave-9 item 8), resolved
     *                         once here so a hit is a map lookup; null when the
     *                         variant cannot be built (falls back to vanilla)
     * @param custom           third-party abilities from
     *                         {@link com.deds.morph.api.AbilityRegistry} that apply
     *                         to this morph (wave-9 item 1); empty for every
     *                         vanilla morph unless another mod registered one
     */
    public record Profile(EnumSet<MorphAbility> abilities,
            EntityDimensions dimensions, float stepHeight, boolean baby,
            List<Vec3> passengerOffsets, SwimParams swim, SoundEvent hurtSound,
            List<Ability> custom) {

        /** Result for an unbuildable variant: no abilities, no dimension override. */
        static final Profile BROKEN = new Profile(
                EnumSet.noneOf(MorphAbility.class), null, 0.6f, false, List.of(),
                SwimParams.NONE, null, List.of());

        /** A PLAYER morph (wave 4 §1.3): field-for-field identical to
         *  {@link #BROKEN} — no abilities, {@code dimensions=null} (defers to the
         *  vanilla 0.6×1.8 player box + 1.62 eye), no step, not a baby, no ride
         *  seats, no morph voice — named separately for clarity at the
         *  {@code isPlayer} short-circuit. */
        static final Profile PLAYER_PROFILE = new Profile(
                EnumSet.noneOf(MorphAbility.class), null, 0.6f, false, List.of(),
                SwimParams.NONE, null, List.of());

        /** Number of rider slots (seats), at least one so any mountable morph can
         *  carry a rider even if it exposes no explicit PASSENGER attachment. */
        public int seatCount() {
            return Math.max(1, passengerOffsets.size());
        }
    }

    /** Client-local IDs for never-world-added dummies (negative = safe). 26.2
     *  THROWS on {@code Entity.getId()} before an id is assigned and renderers
     *  ask during extraction, so every dummy gets a collision-free negative id. */
    private static final AtomicInteger NEXT_DUMMY_ID =
            new AtomicInteger(-424242000);

    /** Upper bound on PASSENGER seats we enumerate per morph (a happy ghast has
     *  four; the cap just bounds the probe loop for any modded outlier). */
    private static final int MAX_SEATS = 16;

    /** Per-variant derived-fact cache (values are level-independent). */
    private static final Map<MorphVariant, Profile> PROFILES =
            new ConcurrentHashMap<>();

    private MorphEntities() {
    }

    /**
     * Builds a display/probe-only living entity for the given morph variant.
     * When the variant carries NBT (non-default) it is applied via {@code load}
     * BEFORE the dummy id is assigned, so variant discriminators (sheep colour,
     * slime size, cat variant, …) are reflected in both its render and its
     * probed dimensions. The normalized {@code data} has {@code Pos/Motion/UUID/…}
     * stripped, so {@code load} never repositions or clobbers the client-local
     * id. Never added to the world; null when the type is unknown, not living,
     * or its factory throws.
     */
    public static LivingEntity create(MorphVariant variant, Level level) {
        if (variant.isPlayer()) {
            // minecraft:player is built with createNothing (factory → null), so a
            // probe would already return null via the non-LivingEntity branch; skip
            // the pointless registry+spawn attempt. Client player dummies are built
            // separately in MorphDummies (RemotePlayer). Wave 4 §1.3.
            return null;
        }
        try {
            Identifier ident = Identifier.fromNamespaceAndPath(
                    variant.type().namespace(), variant.type().path());
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(ident)) {
                return null;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(ident);
            Entity entity = type.create(level, EntitySpawnReason.LOAD);
            if (!(entity instanceof LivingEntity living)) {
                return null;
            }
            // Generic safeguard for constructor-set no-gravity flyers: our
            // identity normalization strips "NoGravity", so load() below would run
            // readAdditionalSaveData -> setNoGravity(getBooleanOr("NoGravity",
            // false)) and CLOBBER a constructor's setNoGravity(true) to false.
            // Capture + restore preserves it for any (modded) flyer that sets the
            // flag in its constructor. (NB: vanilla Vex does NOT use this flag in
            // 26.2 — it flies via VexMoveControl and is handled by the explicit
            // flyer set in MorphAbility; this restore is a defensive generic net.)
            boolean pristineNoGravity = living.isNoGravity();
            // Apply the variant discriminators BEFORE assigning the dummy id
            // (copy so the frozen identity tag is never mutated). This also
            // refreshes the dummy's dimensions for size-bearing mobs (slime).
            if (!variant.isDefaultVariant()) {
                ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING,
                        level.registryAccess(), variant.data().copy());
                living.load(in);
            }
            if (pristineNoGravity) {
                living.setNoGravity(true); // undo load's default-false clobber
            }
            living.setId(nextDummyId());
            return living;
        } catch (Exception e) {
            Deds.LOGGER.warn("[deds_morph] failed to create morph dummy {}",
                    variant, e);
            return null;
        }
    }

    /**
     * The cached {@link Profile} for a variant, built once from a transient
     * dummy. Identical on both sides for the same variant. Returns
     * {@link Profile#BROKEN} (cached) when the variant cannot be built, so a
     * broken/modded variant is probed at most once.
     */
    /** The cached PLAYER-morph profile instance (§1.3) — exposed so a gametest can
     *  assert {@code profileOf(playerVariant) == playerProfile()} (reference
     *  identity), which guards the {@code isPlayer} short-circuit (deleting it
     *  would return the distinct {@code BROKEN} instead). */
    public static Profile playerProfile() {
        return Profile.PLAYER_PROFILE;
    }

    public static Profile profileOf(MorphVariant variant, Level level) {
        if (variant.isPlayer()) {
            return Profile.PLAYER_PROFILE; // player box/eye/abilities (§1.3)
        }
        Profile cached = PROFILES.get(variant);
        if (cached != null) {
            return cached;
        }
        Profile built = buildProfile(variant, level);
        PROFILES.putIfAbsent(variant, built);
        return built;
    }

    /**
     * The morph's collision dimensions (box + eye height), or null to defer to
     * the vanilla player dimensions (unbuildable variant).
     */
    public static EntityDimensions dimensionsOf(MorphVariant variant, Level level) {
        return profileOf(variant, level).dimensions();
    }

    private static Profile buildProfile(MorphVariant variant, Level level) {
        LivingEntity dummy = create(variant, level);
        if (dummy == null) {
            return Profile.BROKEN;
        }
        try {
            return probeProfile(variant, level, dummy);
        } catch (RuntimeException broken) {
            // A modded mob whose attributes / attachments / navigation throw on
            // a never-ticked copy would otherwise throw from getDimensions on
            // every tick of every player wearing it, server and client alike.
            Deds.LOGGER.warn("[deds_morph] could not read {} as a morph; it will "
                    + "behave as unbuildable", variant, broken);
            return Profile.BROKEN;
        }
    }

    private static Profile probeProfile(MorphVariant variant, Level level,
            LivingEntity dummy) {
        EnumSet<MorphAbility> abilities = MorphAbility.deriveAbilities(dummy);
        // Variant-aware box: the loaded dummy's real bb (slime size, baby scale)
        // scaled with the dummy's eye height — the exact source the original's
        // forceSetSize/eyeHeight adoption used, expressed as an EntityDimensions.
        EntityDimensions dimensions = EntityDimensions
                .scalable(dummy.getBbWidth(), dummy.getBbHeight())
                .withEyeHeight(dummy.getEyeHeight());
        float stepHeight = (float) dummy.getAttributeValue(Attributes.STEP_HEIGHT);
        // ALL of the morph's PASSENGER attachment seats (where riders sit) —
        // scalable() above drops the mob's EntityAttachments, so capture them here
        // for the ride-position mixin. getNullable returns null past the last seat
        // (a horse has one, a happy ghast up to four), so it safely enumerates the
        // list without the throw-on-empty risk of getClamped. Local-frame offsets;
        // the mixin rotates each by yBodyRot and seats rider i at seat i.
        List<Vec3> passengerOffsets = new ArrayList<>();
        for (int i = 0; i < MAX_SEATS; i++) {
            Vec3 seat = dummy.getAttachments()
                    .getNullable(EntityAttachment.PASSENGER, i, 0f);
            if (seat == null) {
                break;
            }
            passengerOffsets.add(seat);
        }
        // Wave-9 item 8: resolve the morph's own hurt voice ONCE, from the same
        // transient dummy (the original asked the live morph instance per hit —
        // ObfHelper.invokeGetHurtSound, O:EventHandler.java:676/681 — with the
        // 1.6.4 no-arg getHurtSound(); ours passes a GENERIC source, see
        // MorphSounds' deviation note).
        SoundEvent hurtSound = null;
        try {
            hurtSound = ((LivingEntityHurtSoundInvoker) dummy)
                    .deds_morph$getHurtSound(level.damageSources().generic());
        } catch (Exception broken) {
            Deds.LOGGER.warn("[deds_morph] could not resolve a hurt sound for {}",
                    variant, broken);
        }
        return new Profile(abilities, dimensions, stepHeight, dummy.isBaby(),
                List.copyOf(passengerOffsets), SwimParams.derive(dummy),
                hurtSound, AbilityRegistry.resolve(dummy));
    }

    /**
     * Resolves a variant's {@link EntityType} from its {@link MorphVariant#type}
     * id without building a dummy, or null when the id is unregistered. Used by
     * the mount check's tag probe (which needs the type, not an instance).
     */
    public static EntityType<?> typeOf(MorphVariant variant) {
        Identifier ident = Identifier.fromNamespaceAndPath(
                variant.type().namespace(), variant.type().path());
        return BuiltInRegistries.ENTITY_TYPE.containsKey(ident)
                ? BuiltInRegistries.ENTITY_TYPE.getValue(ident) : null;
    }

    /**
     * A distinct, collision-free negative id for a never-world-added dummy, from
     * the shared counter (wave 4 §1.4). {@code Entity.getId()} throws for id 0 and
     * a duplicate id would collide, and several dummies are alive at once (the mob
     * dummy here + the client's world/transition/selector-preview player dummies),
     * so every dummy — mob or {@code MorphPlayerDummy} — must pull a unique id here.
     */
    public static int nextDummyId() {
        return NEXT_DUMMY_ID.decrementAndGet();
    }
}

