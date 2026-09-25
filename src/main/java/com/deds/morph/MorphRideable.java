package com.deds.morph;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PlayerRideable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mounting a rideable morph (spec §5): a morph of a saddle-rideable family (or
 * any {@code PlayerRideable}, or the explicit happy-ghast fallback) can carry a
 * passenger who SITS (does not steer). The rider becomes a vanilla passenger of
 * the morphed player, seated at the morph's PASSENGER attachment
 * (see {@code LivingEntityRidingPositionMixin}).
 */
public final class MorphRideable {

    /** Per-variant "is mountable" cache (level-independent tag/class probe). */
    private static final Map<MorphVariant, Boolean> MOUNTABLE =
            new ConcurrentHashMap<>();

    /** Explicitly NON-mountable despite being {@code PlayerRideable}: llamas
     *  (players can't ride them in vanilla — they carry chests, not riders). */
    private static final Set<EntityType<?>> EXCLUDED =
            Set.of(EntityTypes.LLAMA, EntityTypes.TRADER_LLAMA);

    /** Server-thread guard set only around our {@code startRiding} call, read by
     *  {@code EntityStartRidingMixin} to lift vanilla's player-vehicle reject
     *  ({@code !vehicle.getType().canSerialize()} — the Player entity type is
     *  non-serializable, so a player can never normally be a vehicle). */
    private static final ThreadLocal<Boolean> MOUNTING =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MorphRideable() {
    }

    /** True while {@link #mount} is driving a {@code startRiding} onto a morph. */
    public static boolean isMounting() {
        return MOUNTING.get();
    }

    /**
     * Whether a morph of this variant can be ridden. Decided by TYPE for every
     * vanilla rideable — the {@code CAN_EQUIP_SADDLE} tag (horse family/pig/strider/
     * camel/…) OR the explicit {@code happy_ghast} (harness rideable, which is NOT
     * in that tag). Only a genuinely-unknown (modded) type falls through to a
     * {@code PlayerRideable} dummy probe.
     *
     * <p>The type-first order is deliberate: the previous order probed a dummy via
     * {@link MorphEntities#create} for happy ghast (it isn't in the saddle tag)
     * BEFORE the explicit check, so the mount interaction — including the CLIENT
     * prediction, which runs this on the interaction thread — built a full happy
     * ghast entity every time, whereas a horse short-circuited on the tag. That
     * asymmetric per-click entity construction was the happy-ghast-only unreliability;
     * now a happy ghast resolves by type exactly like a horse, no dummy built.
     * Llama is excluded (vanilla players can't ride llamas).</p>
     */
    public static boolean isMountable(MorphVariant variant, Level level) {
        return MOUNTABLE.computeIfAbsent(variant, v -> {
            EntityType<?> type = MorphEntities.typeOf(v);
            if (type == null || EXCLUDED.contains(type)) {
                return false;
            }
            // Type-only fast paths (no entity instantiated): saddle family + the
            // explicit harness-rideable happy ghast.
            if (type == EntityTypes.HAPPY_GHAST
                    || type.builtInRegistryHolder()
                            .is(EntityTypeTags.CAN_EQUIP_SADDLE)) {
                return true;
            }
            // Last resort for modded rideables outside the tag: probe a dummy.
            return MorphEntities.create(v, level) instanceof PlayerRideable;
        });
    }

    /**
     * Seats {@code rider} on {@code victim} (server). Carries up to the morph's
     * seat count — one for a horse/pig, up to four for a happy ghast (each rider
     * seated at its own PASSENGER attachment by {@code LivingEntityRidingPositionMixin}).
     * Returns {@code SUCCESS_SERVER} on a successful mount (server-authoritative —
     * the passenger list syncs to all clients), else {@code PASS}.
     */
    public static InteractionResult mount(Player rider, ServerPlayer victim) {
        if (rider.isPassenger()) {
            return InteractionResult.PASS; // this rider is already riding something
        }
        if (victim.getPassengers().size() >= seatCount(victim)) {
            return InteractionResult.PASS; // every seat on this morph is taken
        }
        boolean ok;
        MOUNTING.set(Boolean.TRUE);
        try {
            // (vehicle, force=true, broadcast=true): force skips
            // canRide/canAddPassenger; the MOUNTING guard + EntityStartRidingMixin
            // lift the player-vehicle canSerialize reject (verified javap -c:
            // vanilla startRiding refuses a non-serializable vehicle type, and the
            // Player type is non-serializable — the "hidden reject" open question).
            ok = rider.startRiding(victim, true, true);
        } finally {
            MOUNTING.set(Boolean.FALSE);
        }
        return ok ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
    }

    /**
     * How many riders {@code victim}'s current morph can carry — its morph's
     * PASSENGER seat count (four for a happy ghast, one for a horse), or one when
     * the victim is not morphed / the morph exposes no seat (so a mount still
     * proceeds and seats at the vanilla point).
     */
    private static int seatCount(ServerPlayer victim) {
        return MorphAbilities.committedVariant(victim)
                .map(v -> MorphEntities.profileOf(v, victim.level()).seatCount())
                .orElse(1);
    }
}
