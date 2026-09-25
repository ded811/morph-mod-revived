package com.deds.morph;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generic AI-relationship primitive (spec Part A §A.2). Lets the goal/sensor
 * bridges treat a morphed player as its morph TYPE for fear/hunt, keyed off data
 * we already have ({@link MorphAbilities#committedVariant} + the
 * {@link MorphEntities} dummy). Shared (no {@code net.fabricmc}).
 *
 * <p>The <b>class wall</b> ({@link #matchesClass}) uses {@code EntityType.getBaseClass()}
 * with no dummy; the <b>selector wall</b> ({@link #matchesSelector}) runs the mob's
 * own {@code TargetingConditions.Selector} against a real instance of the morph
 * (the cached dummy), swallowing exceptions as non-match (a modded selector might
 * touch live world state on a never-ticked dummy — spec Open Questions).</p>
 */
public final class MorphView {

    /** Per-variant probe dummy (level-independent class/type checks); cached so a
     *  selector probe does not rebuild an entity per candidate per tick. An empty
     *  entry remembers a variant whose dummy cannot be built, so it is not
     *  retried (and its stack trace logged) every tick. Cleared with the server
     *  state: a dummy holds its level, and a singleplayer world closed and
     *  another opened used to keep the old world in memory. */
    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            Map<MorphVariant, Optional<LivingEntity>>> DUMMY_CACHE = new ConcurrentHashMap<>();

    /** Mobs the hunt bridge aimed at a morphed player, and that player's UUID.
     *  When the player changes form, these let go (see {@link #releaseHunters}). */
    private static final Map<Mob, UUID> HUNTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MorphView() {
    }

    // Config accessors (mixins live in another package and cannot see the
    // package-private Morph.config(); centralize the AI-relationship gates here).

    /** Master gate for the AI-relationship bridges (avoid + hunt + brain-fear). */
    public static boolean aiRelationships() {
        return Morph.config().aiRelationships();
    }

    /** The hunt-half gate (mobs actively target a matching morph). */
    public static boolean aiRelationshipsHunt() {
        return Morph.config().aiRelationshipsHunt();
    }

    /** Max target distance (blocks), capped by the goal's own follow distance. */
    public static int aiRelationshipRange() {
        return Morph.config().aiRelationshipRange();
    }

    /** Morphed {@link ServerPlayer}s whose bounding box intersects {@code box}. */
    public static List<ServerPlayer> morphedPlayersIn(ServerLevel level, AABB box) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (box.intersects(player.getBoundingBox())
                    && MorphAbilities.committedVariant(player).isPresent()) {
                out.add(player);
            }
        }
        return out;
    }

    /** The morph's entity type, or null when the player is not morphed. */
    public static EntityType<?> morphType(Player player) {
        return MorphAbilities.committedVariant(player)
                .map(MorphEntities::typeOf).orElse(null);
    }

    /** The morph's concrete entity class (the class-wall probe), or null.
     *  NB: {@code EntityType.getBaseClass()} returns {@code Entity.class} in 26.2
     *  (not the concrete class), so use the cached dummy's runtime class instead. */
    public static Class<? extends Entity> morphClass(Player player) {
        LivingEntity dummy = dummyOf(player);
        return dummy == null ? null : dummy.getClass();
    }

    /** True if a scan for {@code key} would match this morph (class wall). */
    public static boolean matchesClass(Class<?> key, Player player) {
        Class<? extends Entity> morphClass = morphClass(player);
        return morphClass != null && key.isAssignableFrom(morphClass);
    }

    /** The cached probe dummy for a morphed player, or null. */
    public static LivingEntity dummyOf(Player player) {
        Optional<MorphVariant> variant = MorphAbilities.committedVariant(player);
        if (variant.isEmpty()) {
            return null;
        }
        // One probe per dimension: keyed by variant alone, two players wearing
        // the same form in different dimensions rebuilt it on every call.
        LivingEntity dummy = DUMMY_CACHE
                .computeIfAbsent(player.level().dimension(), d -> new ConcurrentHashMap<>())
                .computeIfAbsent(variant.get(),
                        v -> Optional.ofNullable(MorphEntities.create(v, player.level())))
                .orElse(null);
        if (dummy != null) {
            // Vanilla selectors may read the candidate's position (a guardian's
            // range floor), so the probe stands where the player stands.
            dummy.snapTo(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }
        return dummy;
    }

    /** Drops the probe dummies and hunter records (server start and stop). */
    public static void clearCaches() {
        DUMMY_CACHE.clear();
        HUNTERS.clear();
    }

    /** Records that the hunt bridge aimed {@code mob} at {@code player}. */
    public static void recordHunter(Mob mob, Player player) {
        HUNTERS.put(mob, player.getUUID());
    }

    /**
     * The player changed form: every mob the hunt bridge set on them lets go.
     * A wolf or golem that hunted a sheep- or zombie-shaped player also became
     * persistently angry at that player (vanilla, on any player target), and
     * kept chasing them for up to 40 seconds after they changed back. A mob that
     * still matches the new form simply picks the player up again.
     */
    public static void releaseHunters(Player player) {
        UUID id = player.getUUID();
        List<Mob> hunters = new ArrayList<>();
        synchronized (HUNTERS) {
            HUNTERS.entrySet().removeIf(entry -> {
                if (id.equals(entry.getValue())) {
                    hunters.add(entry.getKey());
                    return true;
                }
                return false;
            });
        }
        for (Mob mob : hunters) {
            if (mob.getLastHurtByMob() == player) {
                continue; // the player really hit it: that anger is not ours to forgive
            }
            if (mob.getTarget() == player) {
                mob.setTarget(null);
            }
            if (mob instanceof NeutralMob neutral) {
                EntityReference<LivingEntity> anger = neutral.getPersistentAngerTarget();
                if (anger != null && id.equals(anger.getUUID())) {
                    neutral.stopBeingAngry();
                }
            }
        }
    }

    /** True if the goal's own species selector accepts this morph (selector wall).
     *  A null selector = no species filter = match; a throwing selector = non-match. */
    public static boolean matchesSelector(TargetingConditions.Selector selector,
            Player player, ServerLevel level) {
        if (selector == null) {
            return true;
        }
        LivingEntity dummy = dummyOf(player);
        if (dummy == null) {
            return false;
        }
        try {
            return selector.test(dummy, level);
        } catch (Exception e) {
            return false; // side-effecting/modded selector on a never-ticked dummy
        }
    }
}
