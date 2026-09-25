package com.deds.morph;

import com.deds.api.DedsMod;
import com.deds.api.ModContext;
import com.deds.api.attach.PlayerDataKey;
import com.deds.api.attach.PlayerDataSpec;
import com.deds.api.config.ConfigHandle;
import com.deds.api.event.CombatEvents;
import com.deds.api.event.ServerEvents;
import com.deds.api.id.BId;
import com.deds.api.net.MessageType;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Morph, wave 1 + wave 2 — revival of Morph Beta 0.7.1 by iChun (the last
 * 1.6.4 build, which is why this project targets that version; see
 * docs/specs/morph/SPEC.md). Kill a living mob and
 * you acquire it as a morph; with the original's default {@code instaMorph=1}
 * you immediately become it. The selector (client) picks any acquired morph
 * or your own form back.
 *
 * <p>Server-authoritative state machine, wave-1 shape: {@link #STATE} holds
 * the whole per-player morph state (persistent, kept on respawn — the
 * original's {@code loseMorphsOnDeath=0} default — and synced to everyone so
 * every client can render every player's morph). {@link #SELECT} is the only
 * client input: wear an acquired morph or demorph.</p>
 *
 * <p>Wave 2 adds the server seams the {@code /morph} command and the selector
 * remove key drive ({@link #demorph}, {@link #clear}, {@link #morphTarget},
 * {@link #removeMorph}, {@link #whitelistAdd}/{@link #whitelistRemove}), the
 * {@link MorphConfig} JSON config (the skill whitelist gate is enforced now),
 * and the server-side transition lock ({@link #isMorphing}): a committed morph
 * change freezes further changes for {@link #TRANSITION_TICKS} ticks, the
 * original's "you cannot acquire/morph while morphing" rule.</p>
 */
public final class Morph implements DedsMod {

    public static final String MOD_ID = "deds_morph";

    /** Ticks a transformation takes (original wording: "~4 seconds"). */
    public static final int TRANSITION_TICKS = 80;

    /**
     * The WORN morph, registered as {@code deds_morph:worn} — the PUBLIC half
     * (wave-9 item 4). Persistent, copied on respawn
     * ({@code loseMorphsOnDeath=0}), {@code Sync.ALL} so every client can render
     * every player's morph. Mirrors the original's {@code MorphInfo} packet,
     * broadcast with {@code sendPacketToAllPlayers}
     * ({@code O:morph/common/core/EntityHelper.java:130}).
     */
    public static PlayerDataKey<Optional<MorphVariant>> WORN;

    /**
     * The ACQUIRED list + favourites, registered as {@code deds_morph:state} —
     * the PRIVATE half (wave-9 item 4). Persistent, copied on respawn,
     * {@code Sync.TARGET_ONLY}: only the owner ever receives it, exactly as the
     * original sent its state list with {@code sendPacketToPlayer}
     * ({@code O:morph/common/morph/MorphHandler.java:91,107}). Before this split
     * a modified client could enumerate every player's morphs and favourites.
     *
     * <p>Keeps the pre-wave-9 attachment key {@code state} so existing saves
     * load their collection unchanged — see {@link MorphList}'s migration note.</p>
     */
    public static PlayerDataKey<MorphList> LIST;

    /**
     * Per-player morph state — a thin COMPOSITION over {@link #WORN} +
     * {@link #LIST}, not an attachment of its own. Reads assemble a
     * {@link MorphState}; writes fan out to both halves and skip the half that
     * did not change (so a favourite toggle does not re-broadcast the worn
     * morph, and morphing does not re-send the list). Every server seam and
     * every frozen gametest keeps talking to this.
     */
    public static PlayerDataKey<MorphState> STATE;

    /**
     * C2S {@code deds_morph:select}: wear an acquired morph variant, or demorph
     * when the optional is empty. Invalid targets are ignored (debug log).
     */
    public static MessageType<Optional<MorphVariant>> SELECT;

    /**
     * C2S {@code deds_morph:remove}: the selector's remove key (Delete /
     * Backspace) asks the server to drop an acquired morph variant. Delegates to
     * {@link #removeMorph} which enforces every guard (never the worn morph,
     * never mid-transition). A dropped variant also leaves {@code favourites}.
     */
    public static MessageType<MorphVariant> REMOVE;

    /**
     * C2S {@code deds_morph:favourite}: the selector's grave key toggles a
     * morph variant's favourite star. Delegates to {@link #toggleFavourite}; the
     * own form (never a {@link MorphVariant} in {@code acquired}) is structurally
     * unstarable.
     */
    public static MessageType<MorphVariant> FAVOURITE;

    /**
     * S2C {@code deds_morph:acquire_fx}: the acquisition suck-in effect,
     * broadcast to every player in the victim's dimension (the original's
     * {@code sendPacketToAllInDimension}) after a successful acquisition.
     * Clients spawn a 40-tick client-local effect from it.
     */
    public static MessageType<AcquireFx> ACQUIRE_FX;

    /** Gameplay config ({@code config/deds_morph/morph.json}). */
    public static ConfigHandle<MorphConfig> CONFIG;

    private static final StreamCodec<RegistryFriendlyByteBuf, Optional<MorphVariant>>
            SELECT_CODEC = ByteBufCodecs.optional(MorphVariant.STREAM_CODEC);

    /**
     * Disk codec of the {@link #WORN} half — the same {@code {current?}} field
     * the pre-wave-9 combined blob used, so the shape stays legible in a player
     * file (and a hand-edited one keeps working).
     */
    private static final com.mojang.serialization.Codec<Optional<MorphVariant>>
            WORN_CODEC = MorphVariant.CODEC.optionalFieldOf("current").codec();

    /**
     * Transient-NBT strip set used by {@link #variantOf} to normalize a victim's
     * saved NBT down to its identity. Strip only these known-vanilla-transient
     * keys and keep everything else — that is what makes modded variants
     * distinct for free (spec §3). Mutable so a future config/data file can add
     * keys without code changes (the original's extensibility intent, minus the
     * remote fetch). {@code Age} is NOT here — it is normalized separately (baby
     * ⇒ {@code -24000}, else absent) to keep baby-vs-adult as identity.
     *
     * <p>This set is CLASS-INDEPENDENT: it lists only keys that {@code Entity},
     * {@code LivingEntity} and {@code Mob} write for every mob. Mob-SPECIFIC
     * volatile keys ({@code Endermite.Lifetime}, {@code AbstractHorse.Temper},
     * {@code NeutralMob.anger_end_time}, …) are stripped by type in
     * {@link MorphNbtStripper} — see its javadoc for why the two halves are
     * scoped differently.</p>
     */
    public static final Set<String> TRANSIENT_KEYS = new HashSet<>(List.of(
            // Entity (common)
            "Pos", "Motion", "Rotation", "fall_distance", "Fire", "Air",
            "OnGround", "Invulnerable", "PortalCooldown", "UUID",
            // NB: "CustomName"/"CustomNameVisible" are deliberately NOT stripped
            // (wave 5 item D) — a name-tagged mob is its OWN morph variant, exactly
            // like every other discriminator, so a "Dinnerbone" sheep is a separate
            // selector entry from a plain sheep and the dummy carries the name (and
            // vanilla's always-on flag) for the mob-style nametag.
            "Silent", "NoGravity", "Glowing",
            "HasVisualFire", "TicksFrozen", "Tags", "Passengers",
            // 1.21.x transient explosion/knockback-impulse physics state
            // (written unconditionally, so it leaks into a fresh mob's identity)
            "current_impulse_context_reset_grace_time",
            "current_explosion_impact_pos",
            "ignore_fall_damage_from_current_explosion",
            // LivingEntity
            "Health", "AbsorptionAmount", "Brain", "DeathTime", "HurtTime",
            "FallFlying", "active_effects", "last_hurt_by_mob",
            "last_hurt_by_player", "last_hurt_by_player_memory_time",
            "ticks_since_last_hurt_by_mob", "sleeping_pos",
            "locator_bar_icon",   // per-instance waypoint marker, never a variant
            "attributes",   // transient health-derived max + per-instance modifiers
            // Mob
            "CanPickUpLoot", "PersistenceRequired", "LeftHanded", "NoAI",
            "DeathLootTable", "DeathLootTableSeed", "drop_chances", "home_pos",
            "home_radius", "equipment", "leash",
            // AgeableMob (Age itself is normalized, not stripped)
            "ForcedAge", "AgeLocked",
            // Slime/AbstractCubeMob: Size is KEPT (the variant discriminator);
            // wasOnGround is per-instance jump physics, so a mid-jump vs
            // grounded slime of the same size stays one identity.
            "wasOnGround"));

    /**
     * Server-only, transient transition clock keyed by player UUID: the server
     * tick at which the current transition ends. Not persisted (a logout
     * mid-morph resolves server-side, matching the original) and NOT a field
     * on {@link MorphState} — that record is a wave-1 binding contract with a
     * fixed disk/wire shape and must not be widened for this.
     */
    private static final Map<UUID, Integer> transitionEnd =
            new ConcurrentHashMap<>();

    private static Logger log;

    /** Result of {@link #removeMorph}, for command messaging. */
    public enum RemoveResult { REMOVED, NOT_OWNED, IS_CURRENT, MORPHING }

    /** Result of {@link #toggleFavourite}, for the selector's grave key. */
    public enum FavouriteResult { STARRED, UNSTARRED, NOT_OWNED }

    @Override
    public void onInitialize(ModContext ctx) {
        log = ctx.logger();

        CONFIG = ctx.config().register("morph", MorphConfig.CODEC,
                MorphConfig::defaults);

        // Wave-9 item 4 (acquired-list privacy): two attachments, not one.
        // WORN goes to everyone (they render it); LIST goes to its owner only.
        WORN = ctx.playerData().register("worn",
                PlayerDataSpec.of(WORN_CODEC, Optional::empty)
                        .syncedWith(PlayerDataSpec.Sync.ALL, SELECT_CODEC));

        LIST = ctx.playerData().register("state",
                PlayerDataSpec.of(MorphList.CODEC, () -> MorphList.EMPTY)
                        .syncedWith(PlayerDataSpec.Sync.TARGET_ONLY,
                                MorphList.STREAM_CODEC));

        STATE = composedState();

        SELECT = ctx.net().registerC2S("select", SELECT_CODEC,
                (target, sender) -> select(sender, target));

        REMOVE = ctx.net().registerC2S("remove", MorphVariant.STREAM_CODEC,
                (variant, sender) -> removeMorph(sender, variant));

        FAVOURITE = ctx.net().registerC2S("favourite",
                MorphVariant.STREAM_CODEC,
                (variant, sender) -> toggleFavourite(sender, variant));

        ACQUIRE_FX = ctx.net().registerS2C("acquire_fx",
                AcquireFx.STREAM_CODEC);

        CombatEvents.PLAYER_KILLED_LIVING.register(
                kill -> acquireFromKill(kill.killer(), kill.victim()));

        // Wave-2 deliverable 5: per-morphed-player passive-ability tick. Commits
        // the derived ability set at transition end, ticks the active set, and
        // snaps the hitbox — see MorphAbilities.
        ServerEvents.PLAYER_TICK_END.register(MorphAbilities::tick);

        // CRITICAL (cross-world morph-lock fix): the transition lock + ability
        // maps are static and survive a world exit, but a fresh integrated server
        // resets MinecraftServer.getTickCount() to 0 — so a stale future deadline
        // would read as isMorphing()==true forever and permanently block ALL
        // morphing in every subsequent world until a full game restart. Reset on
        // both lifecycle edges so every world starts (and leaves) clean.
        ServerEvents.STARTED.register(server -> resetServerState());
        ServerEvents.STOPPING.register(server -> resetServerState());

        // Wave-3 behavior wave 2: interactions (milk/stew/shear) + mounting a
        // morphed player, routed off the shared USE_ENTITY event.
        MorphInteractions.register();

        ctx.commands().register((dispatcher, buildContext, selection) ->
                MorphCommand.register(dispatcher));
    }

    /**
     * Builds the {@link #STATE} facade over {@link #WORN} + {@link #LIST}
     * (wave-9 item 4). Reads assemble the three components; writes only touch
     * the half that actually changed, so the number of sync packets per state
     * write is unchanged from the single-attachment build.
     *
     * <p>On a CLIENT, {@code LIST.get(remotePlayer)} returns
     * {@link MorphList#EMPTY} — that is the whole point of the change, and it is
     * safe because every client reader of the list halves
     * ({@code MorphSelector}, {@code MorphRadial}) reads the LOCAL player, while
     * every reader of a remote player takes {@code .current()} only.</p>
     */
    private static PlayerDataKey<MorphState> composedState() {
        return new PlayerDataKey<MorphState>() {
            @Override
            public BId id() {
                return LIST.id();
            }

            @Override
            public MorphState get(Player player) {
                MorphList list = LIST.get(player);
                return new MorphState(WORN.get(player), list.acquired(),
                        list.favourites());
            }

            @Override
            public void set(ServerPlayer player, MorphState value) {
                if (!WORN.get(player).equals(value.current())) {
                    WORN.set(player, value.current());
                }
                MorphList next = new MorphList(value.acquired(),
                        value.favourites());
                if (!LIST.get(player).equals(next)) {
                    LIST.set(player, next);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // acquisition
    // ------------------------------------------------------------------

    /**
     * Kill-acquisition entry (original 0.7.1 flow): reject a self-attributed kill,
     * then the boss gate on the VICTIM's own type ({@code bossMorphs=0} — a real
     * ender dragon or wither grants nothing; a victim merely MORPHED as a boss is
     * caught by acquireTarget's resolved-variant boss gate), then the common
     * acquisition body. A NEW mob acquisition discards the victim (original
     * {@code setDead}); a PLAYER victim is NEVER discarded (it runs vanilla
     * die()/respawn — see acquireTarget).
     */
    private static void acquireFromKill(ServerPlayer killer,
            LivingEntity victim) {
        // BLOCKER: explicit self-kill guard (wave 4 §1.2). A self-inflicted
        // entity-attributed death (own arrow/TNT/wind-charge) fires the kill event
        // with attacker==victim==self; without this you would acquire yourself.
        if (victim == killer || killer.getUUID().equals(victim.getUUID())) {
            return;
        }
        EntityType<?> type = victim.getType();
        if (type == EntityTypes.ENDER_DRAGON || type == EntityTypes.WITHER) {
            return; // bossMorphs=0 (0.7.1 default): bosses grant nothing
        }
        acquireTarget(killer, victim, /*discard=*/true, /*forced=*/false);
    }

    /**
     * Common acquisition body (public seam — gametests enter here). Gates,
     * then the no-duplicate append + insta-morph, then the {@code acquire_fx}
     * broadcast, then (if {@code discard}) removes the target.
     *
     * <p>Order of gates: transition lock → target type (a {@code Player} is
     * gated by {@code playerMorphs}; a {@code Mob} is always eligible; other
     * living such as armor stands are excluded) → skill whitelist (empty =
     * everyone) → no-duplicate (UUID-dedup for players, structural for mobs). On
     * success the killer insta-morphs into the new id ({@code instaMorph=1}) and
     * the transition lock starts.</p>
     *
     * @param discard whether to remove the target after acquiring (kills do;
     *                {@code /morph morphtarget} does not)
     * @param forced  mirrors the original signature; a no-op in wave 1 (our
     *                model always insta-morphs) — retained for later
     *                {@code instaMorph=0}
     * @return true if a NEW morph was acquired
     */
    public static boolean acquireTarget(ServerPlayer killer,
            LivingEntity target, boolean discard, boolean forced) {
        if (isMorphing(killer)) {
            return false;
        }
        if (!(target instanceof Player) && !(target instanceof Mob)) {
            return false; // armor stands and other non-Mob living excluded
        }
        List<String> whitelist = config().whitelistedPlayers();
        if (!whitelist.isEmpty()
                && !whitelist.contains(killer.getScoreboardName())) {
            return false; // skill whitelist gate (0.4.0): only listed players
        }
        // Resolve the EFFECTIVE variant FIRST, then gate on it (wave 5 item C):
        // a morphed victim yields the morph they APPEAR as, so `playerMorphs` must
        // only block when the result really is a player form — killing someone
        // disguised as a cow gives a cow and goes down the normal mob path.
        MorphVariant variant = variantOf(target);
        if (variant.isPlayer() && !config().playerMorphs()) {
            return false; // playerMorphs gate (wave 4 §1.2)
        }
        // Boss gate on the RESOLVED variant (review finding 7): acquireFromKill's
        // victim-type check sees only EntityTypes.PLAYER for a morphed victim, so a
        // player disguised as a wither would hand the boss form over on death (and
        // it would then self-propagate through PvP). Config-aware via `bossMorphs`
        // (0.7.1 default 0 = blocked); also closes the op `/morph morphtarget`-at-a-
        // boss seed, since this seam has had no boss gate at all.
        if (!config().bossMorphs() && isBossType(variant.type())) {
            return false;
        }
        // childMorphs (wave-10; O:morph/common/core/EntityHelper.java:49 —
        // `Morph.childMorphs == 0 && living.isChild()`). Gated on the RESOLVED
        // variant for the same reason the boss gate is (wave-5 item C): a player
        // disguised as a baby zombie hands over a BABY variant, and the live
        // victim's own isBaby() is false. Profile.baby() is the loaded dummy's
        // isBaby(), i.e. exactly the variant's own child flag.
        if (!config().childMorphs()
                && MorphEntities.profileOf(variant, target.level()).baby()) {
            return false;
        }
        // blacklistedMobs (wave-10; O:morph/common/core/CommonProxy.java:24-43
        // resolves the CSV of class names at init, O:EntityHelper.java:53-59
        // enforces it with clz.isInstance(living)). See isBlacklisted for the
        // 26.2 shape of "a class name, with inheritance".
        if (isBlacklisted(variant)) {
            return false;
        }
        // Self-acquire via mirror (review finding 8): a victim morphed as YOU
        // resolves to YOUR OWN player variant — the wave-4 self-kill guard compares
        // victim identity, not the resolved variant, and samePlayer dedup can't
        // catch it (you never hold your own variant). Without this you'd acquire
        // yourself, insta-morph into your own skin, and gain a redundant
        // "yourself" selector column.
        if (variant.isPlayer() && variant.playerId()
                .filter(id -> id.equals(killer.getUUID())).isPresent()) {
            return false;
        }
        MorphState state = STATE.get(killer);
        // Dedup: player variants by UUID only (a re-acquire's Name may differ, and
        // keeps the first-acquired entry); mob variants stay structural (§1.1/§1.2).
        //
        // AUDIT ITEM 5 ("re-acquiring an owned morph REPLACES the entry in place,
        // keeping list position and favourite" —
        // O:morph/common/morph/MorphHandler.java:22-54) is deliberately NOT ported
        // here; wave 10 examined it and recorded the reasoning as deviation D10-7
        // in the SPEC. Short version: our identity is the (type, stripped-NBT) pair
        // compared STRUCTURALLY, so a re-acquired mob variant is bit-identical and
        // "replace in place" would write back exactly what is stored; the only
        // field that can differ is a PLAYER variant's Name, and keeping the first
        // one is a deliberate, gametested wave-4 contract
        // (acquireDedupByUuidKeepsFirstName).
        boolean already = variant.isPlayer()
                ? state.acquired().stream().anyMatch(v -> v.samePlayer(variant))
                : state.owns(variant);
        if (already) {
            return false; // no-duplicate append
        }
        STATE.set(killer, state.acquireAndMorph(variant));
        beginTransition(killer);
        if (target.level() instanceof ServerLevel level) {
            AcquireFx fx = new AcquireFx(variant, target.position(),
                    target.yBodyRot, killer.getUUID());
            for (ServerPlayer player : level.players()) {
                ACQUIRE_FX.sendTo(player, fx);
            }
        }
        // BLOCKER: never discard a PLAYER victim (wave 4 §1.2). Acquisition fires
        // from inside ServerPlayer.die(); discarding the victim mid-die() corrupts
        // vanilla death/respawn. A killed player runs vanilla die()/respawn; the
        // suck-in FX is a cosmetic overlay only. Mobs still discard on a new kill.
        if (discard && !(target instanceof Player)) {
            target.discard();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // worn-morph selection
    // ------------------------------------------------------------------

    /**
     * SELECT rule (public server-side seam — gametests enter here): the
     * target must be empty (demorph) or an acquired morph; anything else is
     * ignored with a debug log. A no-op select is dropped without a write.
     * Blocked while a transition is in progress ({@link #isMorphing}).
     */
    public static void select(ServerPlayer player, Optional<MorphVariant> target) {
        if (isMorphing(player)) {
            return;
        }
        MorphState state = STATE.get(player);
        if (target.isPresent() && !state.owns(target.get())) {
            if (log != null) {
                log.debug("ignoring select of unowned morph {} by {}",
                        target.get(), player.getUUID());
            }
            return;
        }
        if (state.current().equals(target)) {
            return;
        }
        STATE.set(player, state.withCurrent(target));
        beginTransition(player);
    }

    // ------------------------------------------------------------------
    // /morph command seams
    // ------------------------------------------------------------------

    /**
     * demorph (admin-robust): drop any stale/expired transition lock first so an
     * admin demorph always proceeds, then, if morphed, start a transition back to
     * the own form. Returns false ONLY when the player was not morphed ("not in
     * morph") — no longer wedged by the transition lock (an admin unstick must
     * work even on a player who appears mid-morph).
     */
    public static boolean demorph(ServerPlayer player) {
        transitionEnd.remove(player.getUUID()); // clear any lingering lock
        MorphState state = STATE.get(player);
        if (state.current().isEmpty()) {
            return false; // genuinely not in morph
        }
        STATE.set(player, state.withCurrent(Optional.empty()));
        beginTransition(player);
        return true;
    }

    /**
     * clear (admin unstick): force-reset a player regardless of the transition
     * lock — a stuck (or genuinely mid-transition) player must always be
     * resettable. Drops the lock, wipes the morph state to {@link MorphState#EMPTY},
     * tears down any applied abilities + transient ability entries, and snaps the
     * collision box back to the vanilla player. Always returns {@code true}.
     */
    public static boolean clear(ServerPlayer player) {
        transitionEnd.remove(player.getUUID());        // clear the lock
        STATE.set(player, MorphState.EMPTY);           // wipe all morphs (synced)
        MorphAbilities.resetPlayer(player);            // kill abilities + drop maps
        if (player.isVehicle()) {
            player.ejectPassengers();                  // a rider on the cleared morph
        }
        player.refreshDimensions();                    // reset box to the player size
        return true;
    }

    /**
     * morphtarget: acquire + morph into the living mob the player is looking
     * at (reach 4.0), does NOT kill/discard the target. Returns false if no
     * morphable living target (or the type is already owned), or while
     * morphing.
     */
    public static boolean morphTarget(ServerPlayer player) {
        if (isMorphing(player)) {
            return false;
        }
        LivingEntity target = lookTarget(player, 4.0);
        if (target == null) {
            return false;
        }
        return acquireTarget(player, target, /*discard=*/false,
                /*forced=*/true);
    }

    /**
     * Removes one acquired morph, enforcing the two remove rules: cannot
     * remove the currently-worn morph ({@link RemoveResult#IS_CURRENT}) and
     * cannot remove the own form (structurally impossible — the demorphed
     * form is never a {@link BId} in {@code acquired}). Blocked while morphing.
     * This is the C2S seam the selector's remove key will call in the client
     * wave; specified + gametested here so the rule lands server-side now.
     */
    public static RemoveResult removeMorph(ServerPlayer player,
            MorphVariant variant) {
        if (isMorphing(player)) {
            return RemoveResult.MORPHING;
        }
        MorphState state = STATE.get(player);
        if (!state.owns(variant)) {
            return RemoveResult.NOT_OWNED;
        }
        if (state.current().equals(Optional.of(variant))) {
            return RemoveResult.IS_CURRENT;
        }
        STATE.set(player, state.without(variant));
        return RemoveResult.REMOVED;
    }

    /**
     * Toggles the favourite star on one acquired morph (the selector's grave
     * key / the {@code deds_morph:favourite} C2S seam). Favourites are a
     * lightweight bookmark for the radial menu, independent of the transition
     * lock — so, unlike {@link #removeMorph}, this is NOT gated on
     * {@link #isMorphing}. The own form is never a {@link BId} in
     * {@code acquired}, so it can never be starred ({@code NOT_OWNED}), which
     * is the original's "cannot favourite your own form" rule by construction.
     *
     * @return {@link FavouriteResult#STARRED} / {@link FavouriteResult#UNSTARRED}
     *         on a successful toggle, {@link FavouriteResult#NOT_OWNED} if the
     *         id was never acquired
     */
    public static FavouriteResult toggleFavourite(ServerPlayer player,
            MorphVariant variant) {
        MorphState state = STATE.get(player);
        if (!state.owns(variant)) {
            return FavouriteResult.NOT_OWNED;
        }
        MorphState next = state.withFavouriteToggled(variant);
        STATE.set(player, next);
        return next.isFavourite(variant)
                ? FavouriteResult.STARRED
                : FavouriteResult.UNSTARRED;
    }

    // ------------------------------------------------------------------
    // whitelist (config-backed skill gate)
    // ------------------------------------------------------------------

    /**
     * Adds a scoreboard name to the config skill whitelist and rewrites the
     * JSON. Returns false (no change) if the name is already present. The
     * {@code player} parameter mirrors the original's command-sender context;
     * the whitelist itself is global.
     */
    public static boolean whitelistAdd(ServerPlayer player, String name) {
        if (CONFIG == null) {
            return false;
        }
        MorphConfig cfg = CONFIG.get();
        if (cfg.whitelistedPlayers().contains(name)) {
            return false;
        }
        List<String> next = new ArrayList<>(cfg.whitelistedPlayers());
        next.add(name);
        CONFIG.set(cfg.withWhitelistedPlayers(next));
        return true;
    }

    /**
     * Removes a scoreboard name from the config skill whitelist and rewrites
     * the JSON. Returns false (no change) if the name was not present.
     */
    public static boolean whitelistRemove(ServerPlayer player, String name) {
        if (CONFIG == null) {
            return false;
        }
        MorphConfig cfg = CONFIG.get();
        if (!cfg.whitelistedPlayers().contains(name)) {
            return false;
        }
        List<String> next = new ArrayList<>(cfg.whitelistedPlayers());
        next.remove(name);
        CONFIG.set(cfg.withWhitelistedPlayers(next));
        return true;
    }

    // ------------------------------------------------------------------
    // transition lock (§5.1)
    // ------------------------------------------------------------------

    /**
     * True while {@code player} is mid-transformation — the ~4 s
     * ({@link #TRANSITION_TICKS}) window after any committed morph change,
     * during which acquire/select/remove/demorph/clear/morphtarget are all
     * blocked (original: acquire blocked by {@code info.getMorphing()}, select
     * + remove blocked by the {@code MapPacketHandler} morphing guard).
     */
    public static boolean isMorphing(ServerPlayer player) {
        Integer end = transitionEnd.get(player.getUUID());
        if (end == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        return server != null && server.getTickCount() < end;
    }

    /**
     * How far through the current transformation {@code player} is, 0.0 → 1.0
     * (the original's {@code morphProgress/80F}, exposed by its
     * {@code morph/api/Api.java} — see {@link com.deds.morph.api.MorphApi}).
     * Returns 1.0 when no transformation is running.
     */
    public static float transitionProgress(ServerPlayer player) {
        Integer end = transitionEnd.get(player.getUUID());
        MinecraftServer server = player.level().getServer();
        if (end == null || server == null) {
            return 1.0f;
        }
        int remaining = end - server.getTickCount();
        if (remaining <= 0) {
            return 1.0f;
        }
        return Math.clamp(
                (TRANSITION_TICKS - remaining) / (float) TRANSITION_TICKS,
                0.0f, 1.0f);
    }

    /** Starts the transition lock on a committed morph change. */
    private static void beginTransition(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            transitionEnd.put(player.getUUID(),
                    server.getTickCount() + TRANSITION_TICKS);
        }
    }

    /**
     * Drops ONE player's transition lock (wave 5 item B4). Called when the ability
     * system detects that this UUID is now a DIFFERENT {@link ServerPlayer} object
     * — i.e. a respawn or a rejoin. Both {@code commit()} and the box self-heal are
     * gated on {@code !isMorphing}, so a lock left set by logging out (or dying)
     * mid-transition would stall the reconcile on the new entity; a death or a
     * rejoin also legitimately ends any in-flight transformation.
     */
    public static void clearTransitionLock(ServerPlayer player) {
        transitionEnd.remove(player.getUUID());
    }

    /**
     * Clears ALL transient, non-persisted server state — the transition lock map
     * here plus the ability system's per-player maps ({@link MorphAbilities#clearTransientState}).
     * Wired to {@link ServerEvents#STARTED} and {@link ServerEvents#STOPPING} so a
     * new integrated-server world in the same JVM starts clean.
     *
     * <p><b>Why this is critical:</b> {@link #transitionEnd} is a static
     * {@code UUID→tick} map holding a deadline from {@link MinecraftServer#getTickCount()}.
     * It survived a world exit while {@code getTickCount()} reset to 0 on the next
     * integrated server, so a stale (large) deadline read as {@code getTickCount()
     * < deadline == true} forever — {@link #isMorphing} returned true permanently,
     * gating acquire/select/demorph/clear into a hard lock that only a full game
     * restart (JVM static reset) cleared. Resetting on the lifecycle edges removes
     * the stale state so morphing works in every world.</p>
     */
    public static void resetServerState() {
        transitionEnd.clear();
        MorphAbilities.clearTransientState();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Current config, never null (defaults before registration/tests). */
    static MorphConfig config() {
        return CONFIG != null ? CONFIG.get() : MorphConfig.defaults();
    }

    /**
     * The living entity the player is looking at within {@code reach} blocks,
     * or null — replaces the original {@code EntityHelper.getEntityLook}.
     */
    private static LivingEntity lookTarget(ServerPlayer player, double reach) {
        // getHitResultOnViewVector does NOT return entity hits in 26.2 (it
        // block-clips only), so use the canonical entity raycast: cast the
        // eye→look*reach segment against living, pickable entities in the
        // swept box. (Verified: a pig dead-ahead was MISSed by the view-vector
        // helper — gametest morph/cmd/morphtarget.)
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 reachVec = player.getViewVector(1.0f).scale(reach);
        Vec3 end = eye.add(reachVec);
        AABB searchBox = player.getBoundingBox().expandTowards(reachVec).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end,
                searchBox,
                e -> e instanceof LivingEntity && e != player && e.isPickable(),
                reach * reach);
        if (hit != null && hit.getEntity() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // variant identity (§2-4: entity type + normalized NBT)
    // ------------------------------------------------------------------

    /**
     * The morph identity of a living entity: its {@code EntityType} id plus its
     * <b>normalized</b> NBT (spec §2-4). Captures the full save via the verified
     * 26.2 path ({@link TagValueOutput#createWithContext} + {@code saveWithoutId}
     * + {@code buildResult}), strips {@link #TRANSIENT_KEYS}, then applies the
     * Age rule (baby ⇒ {@code Age=-24000}, else absent — keeps baby-vs-adult as
     * identity, collapses all adult/growth ages to one). The surviving keys are
     * the mob's variant discriminators (sheep {@code Color}, slime {@code Size},
     * wolf {@code CollarColor}, …), so two visibly-different variants get
     * non-equal identities and same-variant mobs with different transient noise
     * (Pos/Health/effects) collapse to one — replacing the original's fragile
     * sorted-string identifier with structural {@link net.minecraft.nbt.CompoundTag}
     * equality. The returned {@code data} tag is fresh and owned by the caller.
     */
    public static MorphVariant variantOf(LivingEntity living) {
        if (living instanceof Player player) {
            // "You acquire what the victim APPEARS as" (wave 5 item C): a victim
            // wearing a morph yields THAT morph — the mod's core loop is "kill what
            // you see, become it", and it keeps a disguise honest (killing someone
            // in disguise must not unmask them or hand over their identity).
            Optional<MorphVariant> worn = STATE.get(player).current();
            if (worn.isPresent()) {
                return worn.get();
            }
            // Unmorphed: their own player form. Identity = UUID (discriminator) +
            // current username (offline nametag + skin resolution). Wave 4 §1.2.
            return MorphVariant.ofPlayer(player.getUUID(),
                    player.getGameProfile().name());
        }
        CompoundTag tag = saveEntity(living);
        for (String key : TRANSIENT_KEYS) {
            tag.remove(key);
        }
        // Per-TYPE volatile keys (playtest bug 2, 2026-07-29): the flat set above
        // only knows the keys EVERY living entity writes. A mob-specific counter/
        // timer/position (Endermite.Lifetime, AbstractHorse.Temper,
        // NeutralMob.anger_end_time, …) is per-INSTANCE, so leaving it in forked
        // one mob into a new morph on every kill. Scoped by type so a modded
        // mob's same-named variant discriminator survives — the 26.2 shape of
        // iChun's NBTStripper (see MorphNbtStripper).
        for (String key : MorphNbtStripper.volatileKeysFor(living)) {
            tag.remove(key);
        }
        tag.remove("Age");
        if (living.isBaby()) {
            tag.putInt("Age", -24000);
        }
        return new MorphVariant(entityTypeId(living.getType()), tag);
    }

    /** Full entity save without the {@code id} key (the type is carried by the
     *  {@link BId}), via the javap-verified 26.2 {@code TagValueOutput} bridge. */
    private static CompoundTag saveEntity(LivingEntity living) {
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, living.level().registryAccess());
        living.saveWithoutId(out);
        return out.buildResult();
    }

    /** True for the boss types {@code bossMorphs=0} excludes (ender dragon +
     *  wither), compared on a RESOLVED variant's type id — so a victim MORPHED as
     *  a boss is gated the same as killing the real thing (review finding 7). */
    private static boolean isBossType(BId type) {
        return type.equals(entityTypeId(EntityTypes.ENDER_DRAGON))
                || type.equals(entityTypeId(EntityTypes.WITHER));
    }

    /**
     * The {@code blacklistedMobs} gate (wave 10). The original took a CSV of
     * fully-qualified <b>class</b> names, resolved them once at init
     * ({@code O:morph/common/core/CommonProxy.java:24-43}) and refused any
     * victim with {@code clz.isInstance(living)}
     * ({@code O:morph/common/core/EntityHelper.java:53-59}) — so blacklisting
     * {@code EntityMob} blacklisted every monster.
     *
     * <p><b>The 26.2 shape (deviation D10-2).</b> Class names are not a stable,
     * data-friendly identifier here — a modpack author writes registry ids —
     * so an entry is an ENTITY TYPE ID ({@code minecraft:creeper}, or bare
     * {@code creeper} for the {@code minecraft} namespace). The original's
     * INHERITANCE half, which is the reason a class name was useful at all, is
     * recovered by also accepting an entity-type TAG: an entry starting with
     * {@code #} ({@code #minecraft:undead}) blacklists everything in the tag.
     * Matching is on the RESOLVED variant, like every other acquisition gate,
     * so a player disguised as a blacklisted mob is refused too.</p>
     */
    static boolean isBlacklisted(MorphVariant variant) {
        List<String> blacklist = config().blacklistedMobs();
        if (blacklist.isEmpty()) {
            return false;
        }
        Identifier typeId = Identifier.fromNamespaceAndPath(
                variant.type().namespace(), variant.type().path());
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(typeId);
        for (String raw : blacklist) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith("#")) {
                Identifier tagId = Identifier.tryParse(entry.substring(1));
                if (tagId != null && type != null
                        && type.builtInRegistryHolder().is(
                                TagKey.create(Registries.ENTITY_TYPE, tagId))) {
                    return true;
                }
                continue;
            }
            Identifier id = Identifier.tryParse(entry);
            if (id != null && id.equals(typeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code loseMorphsOnDeath} (wave 10;
     * {@code O:morph/common/core/EventHandler.java:694-725}). Mode 0 keeps
     * everything (the 0.7.1 default); mode 1 wipes the WHOLE collection; mode 2
     * removes only the morph the player was wearing. Both non-zero modes also
     * put the player back in their own form — the original built a fresh
     * {@code MorphInfo(username, info.nextState, selfState)} with
     * {@code morphing = true} ({@code :713-717}) and played the morph sound.
     *
     * <p>Called from {@code ServerPlayerDieMixin} at the TAIL of
     * {@code ServerPlayer.die}, i.e. AFTER Fabric's
     * {@code AFTER_KILLED_OTHER_ENTITY} has fired out of {@code LivingEntity.die}
     * — so a killer still acquires the victim's worn morph before the victim
     * loses it, which is the original's ordering too (the lose branch and the
     * acquire branch are the two halves of one {@code onLivingDeath}, and the
     * acquire half reads the victim entity, not the victim's state).</p>
     *
     * <p>No transition is started: the player is dead, the respawn builds a new
     * {@code ServerPlayer}, and an 80-tick lock keyed on a UUID that is about to
     * come back would only block the respawned player from morphing. The state
     * write is what the client observes, so {@code MorphDummies.clientTick}
     * still plays the morph sound off the change.</p>
     */
    public static void loseMorphsOnDeath(ServerPlayer player) {
        int mode = config().loseMorphsOnDeath();
        if (mode <= 0) {
            return;
        }
        MorphState state = STATE.get(player);
        if (mode == 1) {
            STATE.set(player, MorphState.EMPTY);
        } else {
            Optional<MorphVariant> worn = state.current();
            if (worn.isEmpty()) {
                return; // already in your own form: nothing to lose (":707")
            }
            STATE.set(player, state.without(worn.get())
                    .withCurrent(Optional.empty()));
        }
        transitionEnd.remove(player.getUUID());
        MorphAbilities.resetPlayer(player);
        player.refreshDimensions();
    }

    // TODO(deds-api): lift — the API should bridge vanilla registry ids to
    // BId (and offer a boss-tag query) so mod code never touches Identifier.
    private static BId entityTypeId(EntityType<?> type) {
        return BId.of(EntityType.getKey(type).toString());
    }
}
