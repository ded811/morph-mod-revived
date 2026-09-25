package com.deds.morph;

import com.deds.api.Deds;
import com.deds.api.id.BId;
import com.deds.morph.api.Ability;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-authoritative passive-ability system (spec
 * {@code docs/specs/morph/wave2/redesign-abilities-hitbox.md} Part A). Recreates
 * the original {@code TickHandlerServer.serverTick} ability loop generically:
 * every morphed player's <b>committed</b> ability set (derived once per variant
 * by {@link MorphAbility#deriveAbilities} via {@link MorphEntities}) is applied
 * at transition end, ticked each server tick, and torn down on demorph — all
 * keyed off the already-synced {@link Morph#STATE} + the server transition lock
 * ({@link Morph#isMorphing}), so no new synced state is needed.
 *
 * <p><b>Lifecycle (per {@link com.deds.api.event.ServerEvents#PLAYER_TICK_END}).</b>
 * While {@link Morph#isMorphing} the committed set is frozen (abilities "apply at
 * transition end", per the frozen contract — they must not fire mid-morph). When
 * the transition clears, {@link #commit} diffs the newly-derived set against the
 * applied one: {@link #kill} the removed, {@link #apply} the added, then
 * {@link #refreshBox snap the collision box} (Part B). Every tick the active set
 * is {@link #tickActive ticked}. Everything is idempotent and fully reversible —
 * demorph derives the empty set, which kills all applied effects.</p>
 *
 * <p>Shared code (no {@code net.fabricmc}); reads on the CLIENT resolve the
 * committed variant through a {@link CommittedResolver} the client installs, so
 * the common mixins ({@code fireImmune}, {@code getDimensions}, …) agree on both
 * sides. Recreation of iChun's Morph; all credit for the original to iChun.</p>
 */
public final class MorphAbilities {

    /** Transient step-height modifier id (spec §A.1 #8 — no {@code stepHeight}
     *  field in 26.2, so the step ability is a transient {@code STEP_HEIGHT}
     *  attribute modifier removed on {@link #kill}). */
    private static final Identifier STEP_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(Morph.MOD_ID, "morph_step");

    /** Chicken/parrot slow-fall terminal velocity (original {@code AbilityFloat});
     *  public so the client-side applicator ({@code MorphAbilitiesClient}) clamps
     *  the LOCAL player to the exact same value (movement is client-authoritative). */
    public static final double FLOAT_TERMINAL = -0.1141748;

    /** Spider wall-climb upward motion (original {@code AbilityClimb} 0.1176);
     *  public for the client-side applicator (see {@link #FLOAT_TERMINAL}). */
    public static final double CLIMB_SPEED = 0.1176;

    /** Transient MOVEMENT_SPEED penalty a strider morph carries on land (mirrors
     *  vanilla {@code Strider.SUFFOCATING_MODIFIER}: ~0.66x, removed on lava). */
    private static final Identifier STRIDER_SLOW_ID =
            Identifier.fromNamespaceAndPath(Morph.MOD_ID, "strider_land_slow");

    /** Transient WAYPOINT_TRANSMIT_RANGE modifier that hides a non-player morph
     *  from other players' locator bars (wave 4 Part 2). OUR own id — never
     *  vanilla's — so it coexists with the sneak-/item-hide modifiers. */
    private static final Identifier LOCATOR_HIDE_ID =
            Identifier.fromNamespaceAndPath(Morph.MOD_ID, "morph_locator_hide");

    /** Tolerance for the per-tick box-invariant compare (wave 5 item B1). */
    private static final float BOX_EPSILON = 1.0e-4f;

    /**
     * Per-player committed morph + its applied ability set, plus the {@code owner}
     * entity the set was actually applied to.
     *
     * <p>{@code owner} exists because {@link #COMMITTED} is keyed by UUID and so
     * SURVIVES the entity swap that {@code PlayerList.respawn} performs (it builds
     * a brand-new {@link ServerPlayer} — and even copies the old entity id, so
     * {@code getId()} is NOT an identity discriminator). Without it {@code commit()}
     * saw {@code variantChanged == false} on the new object and early-returned
     * before {@code refreshBox()}, leaving the vanilla player box (wave 5 item B2).
     * A {@link WeakReference} so a stale entry can never pin a logged-out player.</p>
     */
    private record Committed(Optional<MorphVariant> variant,
            EnumSet<MorphAbility> abilities,
            WeakReference<ServerPlayer> owner,
            Map<BId, Ability.Instance> customs) {
    }

    private static final Committed NONE = new Committed(Optional.empty(),
            EnumSet.noneOf(MorphAbility.class), new WeakReference<>(null),
            Map.of());

    /**
     * How many consecutive throws from a third-party {@link Ability.Instance}
     * before Morph stops calling it for the rest of the session (wave-9 item 1
     * risk (a)). The original had NO such guard — a contributor's exception
     * would have taken the server tick with it; being better than equal here was
     * a deliberate call.
     */
    private static final int CUSTOM_FAILURE_LIMIT = 5;

    /** Consecutive-failure counters for third-party abilities, by ability id.
     *  Session-scoped, never persisted. */
    private static final Map<BId, Integer> CUSTOM_FAILURES =
            new ConcurrentHashMap<>();

    /** Server-only per-player applied state (transient; keyed by UUID). */
    private static final Map<UUID, Committed> COMMITTED = new ConcurrentHashMap<>();

    /** The last {@link ServerPlayer} OBJECT ticked per UUID (weak — never pins a
     *  logged-out player). Detects the entity swap a respawn/rejoin performs even
     *  when {@link #COMMITTED} has no entry yet — dying mid-FIRST-morph otherwise
     *  left the transition lock set on the new entity, gating {@code commit()} AND
     *  the box heal for the lock's remainder (~4 s of wrong hitbox; wave-5 review
     *  finding 6). */
    private static final Map<UUID, WeakReference<ServerPlayer>> LAST_ENTITY =
            new ConcurrentHashMap<>();

    /** Per-variant "strictly aquatic" (drowns on land) cache — fish + squids
     *  (the original's swim {@code canSurviveOutOfWater=false}); level-independent. */
    private static final Map<MorphVariant, Boolean> STRICTLY_AQUATIC =
            new ConcurrentHashMap<>();

    /** Per-player out-of-water air clock for strictly-aquatic morphs: a
     *  monotonically-draining counter re-asserted each tick so it wins over the
     *  player's own air recovery (fish-out-of-water suffocation). */
    private static final Map<UUID, Integer> LAND_AIR = new ConcurrentHashMap<>();

    /** Per-variant "is a snow golem"/"is a strider" caches (movement/render
     *  behaviors gated by an explicit vanilla type via dummy {@code instanceof},
     *  which also catches modded subclasses); level-independent, built once. */
    private static final Map<MorphVariant, Boolean> IS_SNOW_GOLEM =
            new ConcurrentHashMap<>();
    private static final Map<MorphVariant, Boolean> IS_STRIDER =
            new ConcurrentHashMap<>();

    /** Resolves a client-side player's committed morph variant (installed by the
     *  client entrypoint; server never consults it). */
    public interface CommittedResolver {
        Optional<MorphVariant> committed(Player player);
    }

    private static volatile CommittedResolver clientResolver;

    private MorphAbilities() {
    }

    /** Installs the client committed-variant resolver (client init only). */
    public static void setClientResolver(CommittedResolver resolver) {
        clientResolver = resolver;
    }

    // ------------------------------------------------------------------
    // per-player server tick (ServerEvents.PLAYER_TICK_END)
    // ------------------------------------------------------------------

    /**
     * The per-morphed-player ability tick. Commits a newly-completed morph
     * transition (diff + apply/kill + hitbox snap), then ticks the active set.
     * A no-op for unmorphed players once their teardown has run.
     *
     * <p>The committed <b>variant</b> is tracked whenever the worn morph changes
     * (so the hitbox adopts the morph regardless of the {@code abilities} config,
     * matching the original's unconditional {@code forceSetSize}); only the
     * ability <b>set</b> respects the master gate — {@code abilities=0} commits
     * an empty set, killing any applied effects while still adopting the box.</p>
     */
    public static void tick(ServerPlayer player) {
        Committed applied = COMMITTED.getOrDefault(player.getUUID(), NONE);

        // ENTITY-SWAP DETECTION (wave 5 item B2 + review finding 6). A respawn or
        // rejoin replaces the ServerPlayer OBJECT while the UUID persists. Detect
        // the swap against the last entity that TICKED for this UUID — NOT against
        // COMMITTED (a player who dies mid-FIRST-morph has no COMMITTED entry yet,
        // so an owner-only check would miss the swap and leave the transition lock
        // gating commit() + the box heal for the lock's remainder). On a swap:
        // treat applied as NONE so the full commit() re-runs on the new object
        // (re-apply()s every ability AND refreshes the box — this also restores
        // the STEP_HEIGHT `morph_step` transient modifier, which
        // ServerPlayer.restoreFrom silently drops on every death: it calls
        // assignBaseValues/assignPermanentModifiers, never assignAllValues), and
        // clear the lock (a death/rejoin legitimately ends any in-flight
        // transformation — item B4).
        WeakReference<ServerPlayer> lastRef = LAST_ENTITY.get(player.getUUID());
        ServerPlayer last = lastRef != null ? lastRef.get() : null;
        if (last != player) {
            LAST_ENTITY.put(player.getUUID(), new WeakReference<>(player));
            if (last != null) {
                killCustoms(applied.customs);
                COMMITTED.remove(player.getUUID()); // killed: never kill them again
                applied = NONE;
                Morph.clearTransitionLock(player);
                // A fish/squid morph that suffocated on land must not respawn
                // already out of air (the counter is keyed by UUID).
                LAND_AIR.remove(player.getUUID());
            }
        }
        // Belt-and-braces for the GC edge (old entity collected between logout and
        // rejoin, so LAST_ENTITY reads null): a COMMITTED entry whose weak owner no
        // longer matches still forces the full re-commit.
        if (applied != NONE && applied.owner.get() != player) {
            killCustoms(applied.customs);
            COMMITTED.remove(player.getUUID()); // killed: never kill them again
            applied = NONE;
            Morph.clearTransitionLock(player);
            LAND_AIR.remove(player.getUUID());
        }

        // Commit a completed transition (never mid-morph — abilities apply at
        // transition end so they do not fire while Morph.isMorphing is true).
        if (!Morph.isMorphing(player)) {
            applied = commit(player, applied);
        }

        // Assert the box invariant EVERY tick (wave 5 item B1), rather than trying
        // to enumerate the events that recreate a player. The old heal compared
        // WIDTH ONLY, and every humanoid morph is exactly player-width (zombie
        // 0.6x1.95, creeper 0.6x1.7, enderman 0.6x2.9) — so it never fired for the
        // whole humanoid family. Compare width, HEIGHT and EYE height, and run it
        // whether or not a morph is applied so a stale morph box on a demorphed
        // player heals too. refreshDimensions() is the only thing that re-reads
        // getDimensions(pose) into the AABB + eyeHeight (which is why crouching
        // "fixed" it). Idempotent once matched.
        if (!Morph.isMorphing(player)) {
            EntityDimensions want = player.getDimensions(player.getPose());
            if (Math.abs(player.getBbWidth() - want.width()) > BOX_EPSILON
                    || Math.abs(player.getBbHeight() - want.height()) > BOX_EPSILON
                    || Math.abs(player.getEyeHeight() - want.eyeHeight())
                            > BOX_EPSILON) {
                refreshBox(player);
            }
        }

        tickActive(player, applied.abilities);
        // Third-party abilities tick beside the built-ins (wave-9 item 1). The
        // original ticked its Ability objects only while getParent() != null;
        // the map holding an instance IS the parent link here.
        tickCustom(applied.customs());

        // Movement/render fidelity behaviors gated by the morph TYPE, not an
        // ability (behavior wave). striderTick runs unconditionally so it also
        // cleans up its modifier on demorph / gate-off.
        striderTick(player);
        snowGolemTrailTick(player);
        // Wave 4 Part 2: hide a non-player morph from other players' locator bars.
        locatorBarTick(player);
    }

    private static Committed commit(ServerPlayer player, Committed applied) {
        Optional<MorphVariant> desiredVariant = Morph.STATE.get(player).current();
        MorphEntities.Profile profile = desiredVariant
                .map(v -> MorphEntities.profileOf(v, player.level()))
                .orElse(null);
        // Master gate: abilities=0 commits an empty set (box still adopts).
        // copyOf(EnumSet) handles the empty case and keeps our own copy so the
        // per-variant cached set is never mutated.
        EnumSet<MorphAbility> desired = (Morph.config().abilities() && profile != null)
                ? EnumSet.copyOf(profile.abilities())
                : EnumSet.noneOf(MorphAbility.class);

        boolean variantChanged = !desiredVariant.equals(applied.variant);
        if (!variantChanged && desired.equals(applied.abilities)) {
            return applied; // steady state — nothing to commit
        }

        for (MorphAbility ability : applied.abilities) {
            if (!desired.contains(ability)) {
                kill(player, ability);
            }
        }
        for (MorphAbility ability : desired) {
            if (!applied.abilities.contains(ability)) {
                apply(player, ability, profile);
            }
        }
        // STEP's value belongs to the form, not the ability: camel (1.5) to
        // horse (1.0), or adult to baby, kept the old height because STEP never
        // left the set.
        if (variantChanged && desired.contains(MorphAbility.STEP)
                && applied.abilities.contains(MorphAbility.STEP)) {
            apply(player, MorphAbility.STEP, profile);
        }

        Committed committed = new Committed(desiredVariant, desired,
                new WeakReference<>(player),
                commitCustom(player, applied.customs, profile));
        if (desiredVariant.isEmpty() && desired.isEmpty()) {
            COMMITTED.remove(player.getUUID()); // fully demorphed — drop the entry
        } else {
            COMMITTED.put(player.getUUID(), committed);
        }
        // Snap the collision box + eye height only when the worn morph itself
        // changed (Part B): the getDimensions mixin now returns the morph box,
        // refreshDimensions rebuilds the AABB + eyeHeight field from it.
        if (variantChanged) {
            // A morph change (incl. demorph) drops any mounted passenger — a
            // rider seated on the OLD morph must not carry over (behavior §5).
            if (player.isVehicle()) {
                player.ejectPassengers();
            }
            // ...and mobs hunting the OLD form let go of the player.
            MorphView.releaseHunters(player);
            refreshBox(player);
        }
        return committed;
    }

    // ------------------------------------------------------------------
    // third-party abilities (wave-9 item 1 — com.deds.morph.api)
    // ------------------------------------------------------------------

    /**
     * Diffs the third-party ability set exactly as {@link #commit} diffs the
     * built-in one, and honours the original's lifecycle contract verbatim
     * ({@code O:morph/api/Ability.java:20-23, 71-81}):
     *
     * <ul>
     *   <li>an ability ENTERING the set gets a fresh per-player instance
     *       ({@code clone()} + {@code setParent()});</li>
     *   <li>an ability LEAVING the set has {@code kill()} called exactly once;</li>
     *   <li>an ability present in BOTH the old and the new morph keeps its SAME
     *       instance and is <b>not</b> killed — "This will NOT be called if the
     *       parent morphs into another morph that has this type of ability."</li>
     * </ul>
     */
    private static Map<BId, Ability.Instance> commitCustom(ServerPlayer player,
            Map<BId, Ability.Instance> applied, MorphEntities.Profile profile) {
        List<Ability> desired = (Morph.config().abilities() && profile != null)
                ? profile.custom() : List.of();
        if (desired.isEmpty() && applied.isEmpty()) {
            return Map.of();
        }
        Map<BId, Ability.Instance> next = new java.util.LinkedHashMap<>();
        for (Ability ability : desired) {
            Ability.Instance kept = applied.get(ability.id());
            if (kept != null) {
                next.put(ability.id(), kept); // carried over — NO kill()
                continue;
            }
            Ability.Instance fresh = guard(ability.id(),
                    () -> ability.createInstance(player));
            if (fresh != null) {
                next.put(ability.id(), fresh);
            }
        }
        for (Map.Entry<BId, Ability.Instance> gone : applied.entrySet()) {
            if (!next.containsKey(gone.getKey())) {
                guard(gone.getKey(), () -> {
                    gone.getValue().kill();
                    return null;
                });
            }
        }
        return Map.copyOf(next);
    }

    /** Ends third-party instances whose player object is gone (death, relog,
     *  End exit, server stop): the Ability contract promises kill() when an
     *  instance leaves, and the old ones used to be dropped unkilled. */
    private static void killCustoms(Map<BId, Ability.Instance> customs) {
        for (Map.Entry<BId, Ability.Instance> entry : customs.entrySet()) {
            guard(entry.getKey(), () -> {
                entry.getValue().kill();
                return null;
            });
        }
    }

    /** Ticks the third-party instances, each isolated from the server tick. */
    private static void tickCustom(Map<BId, Ability.Instance> customs) {
        for (Map.Entry<BId, Ability.Instance> entry : customs.entrySet()) {
            guard(entry.getKey(), () -> {
                entry.getValue().tick();
                return null;
            });
        }
    }

    /**
     * Runs a third-party callback, swallowing any throw and DISABLING that
     * ability id after {@link #CUSTOM_FAILURE_LIMIT} consecutive failures — a
     * misbehaving contributor must never take the server tick down (wave-9 item
     * 1 risk (a); the original had no such guard).
     */
    private static <T> T guard(BId id, java.util.function.Supplier<T> body) {
        if (CUSTOM_FAILURES.getOrDefault(id, 0) >= CUSTOM_FAILURE_LIMIT) {
            return null;
        }
        try {
            T result = body.get();
            CUSTOM_FAILURES.remove(id);
            return result;
        } catch (Exception broken) {
            int failures = CUSTOM_FAILURES.merge(id, 1, Integer::sum);
            Deds.LOGGER.warn("[deds_morph] third-party ability {} threw ({}/{})",
                    id, failures, CUSTOM_FAILURE_LIMIT, broken);
            if (failures >= CUSTOM_FAILURE_LIMIT) {
                Deds.LOGGER.error("[deds_morph] disabling third-party ability {}"
                        + " for this session after {} consecutive failures",
                        id, failures);
            }
            return null;
        }
    }

    /** The third-party abilities currently applied to {@code player} — the
     *  selector's icon pass and gametests read this. */
    public static List<Ability> activeCustomAbilities(Player player) {
        if (!Morph.config().abilities()) {
            return List.of();
        }
        if (player instanceof ServerPlayer) {
            List<Ability> out = new ArrayList<>();
            for (BId id : COMMITTED.getOrDefault(player.getUUID(), NONE)
                    .customs().keySet()) {
                Ability ability = com.deds.morph.api.AbilityRegistry.get(id);
                if (ability != null) {
                    out.add(ability);
                }
            }
            return List.copyOf(out);
        }
        return clientCommitted(player)
                .map(v -> MorphEntities.profileOf(v, player.level()).custom())
                .orElseGet(List::of);
    }

    /**
     * The {@code swim} parameters of the player's committed morph (wave-9 item
     * 2), or {@link SwimParams#NONE}. Read by the CLIENT applicator — swim
     * motion is client-authoritative (Playbook §1).
     */
    public static SwimParams swimParams(Player player) {
        return committedVariant(player)
                .map(v -> MorphEntities.profileOf(v, player.level()).swim())
                .orElse(SwimParams.NONE);
    }

    // ------------------------------------------------------------------
    // transient-state lifecycle (cross-world reset + admin unstick)
    // ------------------------------------------------------------------

    /**
     * Drops ALL transient per-player ability state ({@link #COMMITTED} +
     * {@link #LAND_AIR}). Wired (via {@link Morph#resetServerState}) to server
     * START and STOP so no per-UUID/tick entry survives a world exit into a
     * different world in the same JVM — the other half of the cross-world
     * morph-lock bug that {@code Morph.transitionEnd} caused. The per-variant
     * {@code instanceof} caches ({@link #STRICTLY_AQUATIC}, {@link #IS_SNOW_GOLEM},
     * {@link #IS_STRIDER}) are world-independent identity lookups and are kept.
     */
    public static void clearTransientState() {
        for (Committed committed : COMMITTED.values()) {
            killCustoms(committed.customs); // the Ability contract: kill() when it leaves
        }
        COMMITTED.clear();
        LAND_AIR.clear();
        LAST_ENTITY.clear();
        CUSTOM_FAILURES.clear(); // a new world gets a clean slate per ability
    }

    /**
     * Force-resets ONE player's applied ability state (admin unstick, called by
     * {@link Morph#clear}): kills every applied ability effect (flight, step +
     * swim residue, …) and drops the player's transient entries, so the next tick
     * starts from a clean slate. Safe to call on an unmorphed player (a no-op).
     */
    public static void resetPlayer(ServerPlayer player) {
        Committed applied = COMMITTED.remove(player.getUUID());
        LAND_AIR.remove(player.getUUID());
        if (applied != null) {
            for (MorphAbility ability : applied.abilities) {
                kill(player, ability);
            }
            // Third-party abilities leave the set too (wave-9 item 1): an admin
            // unstick is a set-exit, so every instance gets its one kill().
            for (Map.Entry<BId, Ability.Instance> custom
                    : applied.customs().entrySet()) {
                guard(custom.getKey(), () -> {
                    custom.getValue().kill();
                    return null;
                });
            }
        }
    }

    // ------------------------------------------------------------------
    // apply / tick / kill per ability (spec §A.1 26.2 effect column)
    // ------------------------------------------------------------------

    private static void apply(ServerPlayer player, MorphAbility ability,
            MorphEntities.Profile profile) {
        switch (ability) {
            case STEP -> {
                AttributeInstance attr = player.getAttribute(Attributes.STEP_HEIGHT);
                if (attr != null) {
                    attr.removeModifier(STEP_MODIFIER_ID); // before the baby check
                }
                if (profile == null || profile.baby()) {
                    return; // baby morphs skip step (original baby check)
                }
                if (attr != null) {
                    double base = player.getAttributeBaseValue(Attributes.STEP_HEIGHT);
                    attr.addTransientModifier(new AttributeModifier(STEP_MODIFIER_ID,
                            profile.stepHeight() - base,
                            AttributeModifier.Operation.ADD_VALUE));
                }
            }
            case FLY -> {
                if (flightAllowed(player)) {
                    Abilities abilities = player.getAbilities();
                    abilities.mayfly = true;
                    player.onUpdateAbilities();
                }
            }
            case FIRE_IMMUNITY -> player.clearFire();
            default -> {
                // CLIMB/FLOAT/FALL_NEGATE/SWIM/WATER_ALLERGY/SUNBURN/POISON_/
                // WITHER_RESISTANCE/HOSTILE are pure per-tick or marker abilities.
            }
        }
    }

    private static void tickActive(ServerPlayer player,
            EnumSet<MorphAbility> abilities) {
        for (MorphAbility ability : abilities) {
            tickOne(player, ability);
        }
    }

    private static void tickOne(ServerPlayer player, MorphAbility ability) {
        switch (ability) {
            case FIRE_IMMUNITY -> player.clearFire();
            case POISON_RESISTANCE -> {
                if (player.hasEffect(MobEffects.POISON)) {
                    player.removeEffect(MobEffects.POISON);
                }
            }
            case WITHER_RESISTANCE -> {
                if (player.hasEffect(MobEffects.WITHER)) {
                    player.removeEffect(MobEffects.WITHER);
                }
            }
            case FALL_NEGATE -> player.fallDistance = -0.5; // never accrue fall dmg
            case FLOAT -> {
                // Slow-fall is applied CLIENT-side (MorphAbilitiesClient) at the
                // mob's EXACT terminal velocity, because player movement is
                // client-authoritative (a server setDeltaMovement never reaches
                // the client — that was the "wrong/inconsistent speed" bug). This
                // server clamp remains only as a harmless backup for any
                // server-authoritative motion (remote/headless/mock entities).
                if (!player.getAbilities().flying && !player.isFallFlying()) {
                    Vec3 m = player.getDeltaMovement();
                    if (m.y < FLOAT_TERMINAL) {
                        player.setDeltaMovement(m.x, FLOAT_TERMINAL, m.z);
                    }
                }
                player.resetFallDistance();
            }
            case FLY -> {
                if (flightAllowed(player) && !player.getAbilities().mayfly) {
                    player.getAbilities().mayfly = true;
                    player.onUpdateAbilities();
                }
                if (player.getAbilities().flying) {
                    player.resetFallDistance();
                    flightExhaustion(player);
                }
            }
            case CLIMB -> {
                if (player.horizontalCollision) {
                    player.resetFallDistance();
                    Vec3 m = player.getDeltaMovement();
                    player.setDeltaMovement(m.x,
                            player.isShiftKeyDown() ? 0.0 : CLIMB_SPEED, m.z);
                }
            }
            case SWIM -> swimTick(player);
            case WATER_ALLERGY -> {
                if (player.isInWaterOrRain()
                        && player.level() instanceof ServerLevel level) {
                    player.hurtServer(level, player.damageSources().drown(), 1.0f);
                }
            }
            case SUNBURN -> sunburnTick(player);
            case HOSTILE -> {
                // A mob that ALREADY targeted you keeps attacking (setTarget only
                // fires on RE-target), so periodically clear existing targets too.
                if (player.tickCount % 10 == 0
                        && player.level() instanceof ServerLevel level) {
                    clearNearbyTargets(player, level);
                }
            }
            case STEP -> {
                // STEP is a standing attribute modifier — nothing to tick.
            }
        }
    }

    /**
     * The {@code swim} ability (spec §A.1 #10). In water: infinite air; the
     * swim-speed boost is {@code LivingEntityWaterDragMixin} ORing the ability
     * into vanilla's dolphin's-grace water-drag check on both sides (wave 6 item
     * A — no potion effect, so no icon anywhere). Out of water: a
     * strictly-aquatic morph (fish/squid) suffocates like a fish out of water —
     * its air drains (bubbles empty) and it takes drowning damage. Air-breathers
     * (dolphin/turtle/axolotl/iron-golem-swim) survive on land unaffected.
     */
    private static void swimTick(ServerPlayer player) {
        if (player.isInWater()) {
            player.setAirSupply(player.getMaxAirSupply());
            LAND_AIR.remove(player.getUUID());
            // The swim-speed boost is NO LONGER a hidden DOLPHINS_GRACE effect
            // (the inventory effect panel has no showIcon filter, so it leaked) —
            // LivingEntityWaterDragMixin ORs the ability into vanilla's single
            // drag check instead (wave 6 item A). Nothing to apply per tick.
            return;
        }
        if (!isStrictlyAquatic(player) || player.isSpectator()
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        // Not gated on isCreative(): a creative player is damage-immune anyway
        // (the drown hurtServer no-ops), so this only drains the (cosmetic) air
        // bubbles for them, while a survival fish both drains and takes damage.
        // Re-assert a monotonically-draining counter each tick so it wins over
        // the player's own +air recovery in baseTick; damage on empty like a fish.
        int air = LAND_AIR.getOrDefault(player.getUUID(),
                player.getMaxAirSupply()) - 1;
        if (air <= -20) {
            air = 0;
            player.hurtServer(level, player.damageSources().drown(), 2.0f);
        }
        LAND_AIR.put(player.getUUID(), air);
        player.setAirSupply(air);
    }

    /**
     * Flight HUNGER DRAIN (wave 10; {@code O:morph/common/ability/AbilityFly
     * .java:58-78}) — the half of the {@code ability-fly} row that had been
     * claimed but never written.
     *
     * <p>Verbatim arithmetic: {@code i = round(sqrt(dx² + dz²) · 100)} over the
     * per-tick position delta; {@code i > 0} costs
     * {@code 0.035 · i · 0.01} exhaustion, or {@code 0.125 · i · 0.01} when in
     * water AND this morph is slowed by water; hovering ({@code i == 0}) costs a
     * flat {@code 0.002}. Only while actually flying and not in creative
     * ({@code :58}).</p>
     *
     * <p>The original read {@code posX - lastTickPosX} on the server and
     * {@code motionX} on the client; 26.2's equivalent of the former is
     * {@code getX() - xOld}, the delta {@code Entity.setOldPosAndRot} refreshes
     * every tick from the client's move packets (Playbook §2) — and it is the
     * only correct source, because a ServerPlayer's {@code deltaMovement} is
     * bookkeeping (Playbook §1). Hunger itself is server-authoritative
     * ({@code Player.causeFoodExhaustion} no-ops client-side), so unlike the
     * water slowdown this half stays here.</p>
     */
    private static void flightExhaustion(ServerPlayer player) {
        if (player.getAbilities().instabuild) {
            return; // original: `&& !player.capabilities.isCreativeMode`
        }
        double dx = player.getX() - player.xOld;
        double dz = player.getZ() - player.zOld;
        int i = Math.round((float) (Math.sqrt(dx * dx + dz * dz) * 100.0));
        if (i <= 0) {
            player.causeFoodExhaustion(0.002f);
            return;
        }
        float rate = player.isInWater() && flySlowdownInWater(player)
                ? 0.125f : 0.035f;
        player.causeFoodExhaustion(rate * i * 0.01f);
    }

    /**
     * The original's {@code AbilityFly.slowdownInWater} constructor parameter,
     * DERIVED (wave 10, deviation D10-6).
     *
     * <p>iChun's table maps exactly five flyers and splits them two ways
     * ({@code O:morph/common/ability/AbilityHandler.java:56-78}): bat is
     * {@code fly(true)}; blaze, ghast, ender dragon and wither are
     * {@code fly(false)} (and the no-arg constructor defaults to {@code true},
     * {@code O:AbilityFly.java:20-23}). Those five are separated
     * <b>exactly</b> by {@code EntityType.fireImmune()} — bat is not fire
     * immune, the other four are — which is a signal we already derive, so the
     * parameter falls out of the committed ability set with no new state:
     * a morph that is FIRE_IMMUNE is not slowed by water. Same
     * generic-derivation trade as {@link SwimParams} (D9-4): a modded flyer
     * gets a plausible answer instead of none.</p>
     */
    public static boolean flySlowdownInWater(EnumSet<MorphAbility> active) {
        return !active.contains(MorphAbility.FIRE_IMMUNITY);
    }

    private static boolean flySlowdownInWater(Player player) {
        return flySlowdownInWater(activeAbilities(player));
    }

    /** Clears the target of nearby hostile mobs that are attacking a hostile
     *  morph (the "already targeted" half of the hostile ability). */
    private static void clearNearbyTargets(ServerPlayer player,
            ServerLevel level) {
        if (!activeAbilities(player).contains(MorphAbility.HOSTILE)) {
            return;
        }
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(24.0),
                m -> m.getTarget() == player)) {
            if (shouldCancelTarget(mob, player)) {
                mob.setTarget(null);
            }
        }
    }

    /** Whether the committed morph is strictly aquatic (drowns on land): a
     *  fish ({@link WaterAnimal}) or a squid ({@link Squid}/GlowSquid). Cached
     *  per variant (built once from a dummy). */
    private static boolean isStrictlyAquatic(ServerPlayer player) {
        Optional<MorphVariant> variant = committedVariant(player);
        if (variant.isEmpty()) {
            return false;
        }
        return STRICTLY_AQUATIC.computeIfAbsent(variant.get(), v -> {
            LivingEntity dummy = MorphEntities.create(v, player.level());
            return dummy instanceof WaterAnimal || dummy instanceof Squid;
        });
    }

    /** Whether the committed morph is (a subclass of) {@code cls}, cached per
     *  variant (built once from a dummy). Empty when unmorphed. */
    private static boolean isMorphInstanceOf(Player player, Class<?> cls,
            Map<MorphVariant, Boolean> cache) {
        Optional<MorphVariant> variant = committedVariant(player);
        if (variant.isEmpty()) {
            return false;
        }
        return cache.computeIfAbsent(variant.get(),
                v -> cls.isInstance(MorphEntities.create(v, player.level())));
    }

    /** True while the player is morphed as a strider (client + server; drives the
     *  land-slow modifier and the client lava-surface clamp). */
    public static boolean isStrider(Player player) {
        return isMorphInstanceOf(player, Strider.class, IS_STRIDER);
    }

    // ------------------------------------------------------------------
    // movement/render fidelity behaviors gated by morph TYPE (behavior wave)
    // ------------------------------------------------------------------

    /**
     * Snow-golem trail (spec §5): a snow-golem morph leaves a snow-layer trail at
     * its feet, gated by MOB_GRIEFING and the {@code SNOW_GOLEM_MELTS} environment
     * attribute (which encodes the warm-biome gate) — the faithful 26.2
     * replacement for the original 1.6.4 biome-temperature check.
     */
    private static void snowGolemTrailTick(ServerPlayer player) {
        if (!Morph.config().abilities() || player.isSpectator()
                || !isMorphInstanceOf(player, SnowGolem.class, IS_SNOW_GOLEM)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)
                || level.environmentAttributes().getValue(
                        EnvironmentAttributes.SNOW_GOLEM_MELTS, player.position())) {
            return;
        }
        BlockPos feet = player.blockPosition();
        BlockState snow = Blocks.SNOW.defaultBlockState();
        if (level.getBlockState(feet).isAir() && snow.canSurvive(level, feet)) {
            level.setBlockAndUpdate(feet, snow);
        }
    }

    /**
     * Strider land-slow (spec §5): a strider morph moves slowly on land (mirrors
     * vanilla {@code Strider.SUFFOCATING_MODIFIER}) and at normal speed on lava.
     * Runs unconditionally so the transient modifier is also removed on demorph or
     * when the {@code abilities} gate is off. (No-burn is already covered by the
     * strider type's {@code fireImmune()} → FIRE_IMMUNITY ability.)
     */
    private static void striderTick(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        boolean wantSlow = Morph.config().abilities() && isStrider(player)
                && !player.isInLava();
        boolean has = speed.hasModifier(STRIDER_SLOW_ID);
        if (wantSlow && !has) {
            speed.addTransientModifier(new AttributeModifier(STRIDER_SLOW_ID,
                    -0.34, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!wantSlow && has) {
            speed.removeModifier(STRIDER_SLOW_ID);
        }
    }

    /**
     * Locator-bar hide (wave 4 Part 2). The 26.2 locator bar is driven solely by
     * {@code Attributes.WAYPOINT_TRANSMIT_RANGE} — a {@link LivingEntity} transmits
     * iff its value is {@code > 0} ({@code Player.createAttributes} sets {@code 6.0E7},
     * mobs default {@code 0}). While the player wears a NON-player morph we add a
     * {@code -1.0 ADD_MULTIPLIED_TOTAL} transient modifier (same shape vanilla uses
     * for sneak-/item-hide, clamping the value to 0); {@code LivingEntity}'s
     * per-tick {@code refreshDirtyAttributes → onAttributeUpdated} then untracks the
     * waypoint and sends the packet for free — no mixin.
     *
     * <p>Runs unconditionally (like {@code striderTick}) so it self-heals: it
     * removes its OWN modifier on demorph / player-morph / gate change. Transient
     * modifiers are never saved to NBT, so no stale hide survives a relog — the next
     * tick re-asserts from the persisted {@link Morph#STATE} via
     * {@link #committedVariant}.</p>
     */
    private static void locatorBarTick(ServerPlayer player) {
        AttributeInstance range =
                player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE);
        if (range == null) {
            return;
        }
        boolean hide = committedVariant(player)
                .map(v -> !v.isPlayer()) // morphed AND not a player morph
                .orElse(false);
        boolean has = range.hasModifier(LOCATOR_HIDE_ID);
        if (hide && !has) {
            range.addTransientModifier(new AttributeModifier(LOCATOR_HIDE_ID,
                    -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!hide && has) {
            range.removeModifier(LOCATOR_HIDE_ID);
        }
    }

    private static void kill(ServerPlayer player, MorphAbility ability) {
        switch (ability) {
            case STEP -> {
                AttributeInstance attr = player.getAttribute(Attributes.STEP_HEIGHT);
                if (attr != null) {
                    attr.removeModifier(STEP_MODIFIER_ID);
                }
            }
            case FLY -> {
                Abilities abilities = player.getAbilities();
                // Creative keeps flight, and so does a spectator: taking it made
                // a spectator fall through the world and die in the void.
                if (!abilities.instabuild && !player.isSpectator()) {
                    abilities.mayfly = false;
                    abilities.flying = false;
                    player.onUpdateAbilities();
                }
            }
            case FIRE_IMMUNITY -> player.clearFire();
            case SWIM -> {
                // No removeEffect(DOLPHINS_GRACE) here: the mod no longer applies
                // the effect (wave 6 item A — the boost is the water-drag mixin),
                // and a REAL dolphin-gifted/potion instance must survive demorph.
                player.setAirSupply(player.getMaxAirSupply());
                LAND_AIR.remove(player.getUUID());
            }
            default -> {
                // Per-tick abilities leave no residue; the mixins stop consulting
                // the set the instant it is re-committed.
            }
        }
    }

    /**
     * Undead daylight burn (spec §A.1 #9), replicating vanilla
     * {@code Mob.isSunBurnTick} + the zombie helmet rule EXACTLY: ignite only in
     * bright daylight with sky visible AT THE EYE block, brightness &gt; 0.5, and
     * the same {@code random*30 < (brightness-0.4)*2} roll; rain/water/powder-snow
     * suppress it; a HELMET is damaged instead of igniting; creative/spectator and
     * baby morphs are skipped.
     */
    private static void sunburnTick(ServerPlayer player) {
        MorphEntities.Profile profile = COMMITTED
                .getOrDefault(player.getUUID(), NONE).variant
                .map(v -> MorphEntities.profileOf(v, player.level())).orElse(null);
        if (profile != null && profile.baby()) {
            return; // babies do not burn (original baby check)
        }
        if (player.isCreative() || player.isSpectator()
                || !(player.level() instanceof ServerLevel level)
                || !level.isBrightOutside()) {
            return;
        }
        float brightness = player.getLightLevelDependentMagicValue();
        boolean wet = player.isInWaterOrRain() || player.isInPowderSnow;
        if (brightness <= 0.5f || wet
                || player.getRandom().nextFloat() * 30.0f
                        >= (brightness - 0.4f) * 2.0f) {
            return;
        }
        BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(),
                player.getZ());
        if (!level.canSeeSky(eye)) {
            return;
        }
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!helmet.isEmpty()) {
            // Vanilla: a helmet blocks the burn and takes durability instead.
            if (helmet.isDamageableItem()) {
                helmet.hurtAndBreak(player.getRandom().nextInt(2), level, player,
                        item -> player.onEquippedItemBroken(item, EquipmentSlot.HEAD));
            }
            return;
        }
        player.igniteForSeconds(8.0f);
    }

    // ------------------------------------------------------------------
    // hitbox trigger + flight gate
    // ------------------------------------------------------------------

    /**
     * Recomputes the player's AABB + eye-height field from the (now morph-)
     * dimensions the {@code getDimensions} mixin returns (Part B — snap the box
     * at transition end). NB: vanilla does NOT nudge a player out of blocks here
     * (fudgePositionAfterSizeChange is skipped for players), so a tall form taken
     * under a low ceiling can suffocate until the player moves.
     */
    private static void refreshBox(ServerPlayer player) {
        player.refreshDimensions();
    }

    /**
     * Whether flight is currently permitted (spec §A.3
     * {@code disableEarlyGameFlight}). Mode 0 (default) = always; modes 1 (after
     * Nether) / 2 (after Wither) require progression flags not yet ported, so
     * they currently gate flight OFF (documented deviation — SPEC.md).
     */
    private static boolean flightAllowed(ServerPlayer player) {
        return Morph.config().disableEarlyGameFlight() == 0;
    }

    // ------------------------------------------------------------------
    // read side (common mixins consult these, both sides)
    // ------------------------------------------------------------------

    /**
     * The player's currently-active passive abilities. Server: the committed
     * set (changes only at transition end). Client: derived from the resolved
     * committed variant. Empty when unmorphed or the master gate is off.
     */
    public static EnumSet<MorphAbility> activeAbilities(Player player) {
        if (!Morph.config().abilities()) {
            return EnumSet.noneOf(MorphAbility.class);
        }
        if (player instanceof ServerPlayer) {
            return COMMITTED.getOrDefault(player.getUUID(), NONE).abilities;
        }
        Optional<MorphVariant> variant = clientCommitted(player);
        return variant.map(v -> MorphEntities.profileOf(v, player.level()).abilities())
                .orElseGet(() -> EnumSet.noneOf(MorphAbility.class));
    }

    /** The player's committed morph variant (drives the hitbox mixin, both sides). */
    public static Optional<MorphVariant> committedVariant(Player player) {
        if (player instanceof ServerPlayer) {
            return COMMITTED.getOrDefault(player.getUUID(), NONE).variant;
        }
        return clientCommitted(player);
    }

    /** True while {@code player}'s morph grants fire immunity (fireImmune mixin). */
    public static boolean isFireImmune(Player player) {
        return activeAbilities(player).contains(MorphAbility.FIRE_IMMUNITY);
    }

    /** True while the player's morph negates fall damage (causeFallDamage mixin). */
    public static boolean negatesFall(Player player) {
        EnumSet<MorphAbility> active = activeAbilities(player);
        return active.contains(MorphAbility.FALL_NEGATE)
                || active.contains(MorphAbility.FLOAT);
    }

    private static Optional<MorphVariant> clientCommitted(Player player) {
        CommittedResolver resolver = clientResolver;
        return resolver != null
                ? resolver.committed(player)
                : Morph.STATE.get(player).current();
    }

    // ------------------------------------------------------------------
    // hostile targeting (spec §A.1 #6 / §A.3 hostileAbilityMode) — setTarget hook
    // ------------------------------------------------------------------

    /**
     * Whether {@code mob} acquiring {@code target} should be cancelled by the
     * hostile ability (port of {@code EventHandler.onLivingSetAttackTarget}).
     * Applies only when both the mob and the target player's morph are hostile.
     *
     * <p>Gated by {@code hostileAbilityMode}: <b>0 (DEFAULT) = ignore any hostile
     * morph</b> — hostile mobs leave you alone when you wear a hostile shape (the
     * user-observed default "walk among them" behaviour; the master {@code abilities}
     * config is the real off-switch). 1 = same as 0; 2 = only different-type mobs
     * ignore you; 3 = only same-type mobs ignore you; 4 = range-gated (ignore only
     * beyond {@code hostileAbilityDistanceCheck} blocks).</p>
     */
    public static boolean shouldCancelTarget(Mob mob, LivingEntity target) {
        if (mob.level().isClientSide() || !Morph.config().abilities()) {
            return false;
        }
        if (!(target instanceof Player player)) {
            return false;
        }
        if (!(mob instanceof Enemy)
                && mob.getType().getCategory() != MobCategory.MONSTER) {
            return false; // only hostile mobs ignore hostile morphs
        }
        Optional<MorphVariant> variant = committedVariant(player);
        if (variant.isEmpty()
                || !activeAbilities(player).contains(MorphAbility.HOSTILE)) {
            return false;
        }
        boolean sameType = variant.get().type().equals(typeId(mob.getType()));
        return switch (Morph.config().hostileAbilityMode()) {
            case 2 -> !sameType;            // different-type mobs ignore you
            case 3 -> sameType;             // same-type mobs ignore you
            case 4 -> mob.distanceTo(player)
                    >= Morph.config().hostileAbilityDistanceCheck();
            default -> true;                // 0 (default) / 1: ignore any hostile morph
        };
    }

    private static BId typeId(EntityType<?> type) {
        return BId.of(EntityType.getKey(type).toString());
    }
}
