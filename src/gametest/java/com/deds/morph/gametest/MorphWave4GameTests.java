package com.deds.morph.gametest;

import com.deds.api.event.CombatEvents;
import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphConfig;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server gametests for Morph wave 4 — PLAYER MORPHS (identity/acquisition/profile/
 * grouping, feature ids {@code morph/player/*} + {@code morph/cmd/morphtarget})
 * and the LOCATOR-BAR HIDE ({@code morph/locator/*}). Source of truth:
 * {@code docs/specs/morph/wave4/player-morphs-and-locator.md}. Mod id
 * {@code deds_morph}. Recreation of iChun's Morph; all credit to iChun.
 *
 * <p>Mock players are SURVIVAL (the AI-relationship pattern): the creative
 * {@code GameTestHelper} mock breaks kill/target paths. Player kills are driven
 * through the real {@link CombatEvents#PLAYER_KILLED_LIVING} trigger (which the
 * server bridge fires for player victims too), so the acquisition tests exercise
 * {@code acquireFromKill} — self-kill guard + never-discard-a-player included.</p>
 */
public final class MorphWave4GameTests implements CustomTestMethodInvoker {

    /** The transient locator-hide modifier id — rebuilt to match
     *  {@code MorphAbilities.LOCATOR_HIDE_ID} (which is private). */
    private static final Identifier LOCATOR_HIDE_ID =
            Identifier.fromNamespaceAndPath(Morph.MOD_ID, "morph_locator_hide");

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A SURVIVAL mock server player with a random identity. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return mockPlayerNamed(helper, UUID.randomUUID(), "test-morph-player");
    }

    /** A SURVIVAL mock server player with a SPECIFIC UUID + name (mirrors the
     *  vanilla mock construction; {@code gameMode()} forced to SURVIVAL). */
    private static ServerPlayer mockPlayerNamed(GameTestHelper helper, UUID uuid,
            String name) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(uuid, name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                ClientInformation.createDefault()) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(new ChannelHandler[] {connection});
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.runBeforeTestEnd(() -> server.getPlayerList().remove(player));
        return player;
    }

    private static MorphState state(GameTestHelper helper, ServerPlayer player) {
        MorphState s = Morph.STATE.get(player);
        if (s == null) {
            helper.fail("Morph.STATE.get must never return null");
        }
        return s;
    }

    /** The player variants a state owns, in acquisition order. */
    private static List<MorphVariant> playerVariants(MorphState state) {
        return state.acquired().stream().filter(MorphVariant::isPlayer).toList();
    }

    /** Seeds a committed mob morph (no transition — fast commit next tick). */
    private static void seedMob(ServerPlayer player, String mobPath) {
        MorphVariant v = MorphVariant.ofType(BId.of("minecraft", mobPath));
        Morph.STATE.set(player, new MorphState(Optional.of(v), List.of(v)));
    }

    /** Seeds a committed PLAYER morph (no transition). */
    private static void seedPlayerMorph(ServerPlayer player, MorphVariant playerVariant) {
        Morph.STATE.set(player,
                new MorphState(Optional.of(playerVariant), List.of(playerVariant)));
    }

    private static AttributeInstance waypointRange(ServerPlayer player) {
        return player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE);
    }

    private static int settle() {
        return Morph.TRANSITION_TICKS + 20;
    }

    private static MorphConfig withPlayerMorphs(MorphConfig c, boolean value) {
        return new MorphConfig(c.childMorphs(), value, c.bossMorphs(),
                c.blacklistedMobs(), c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), c.loseMorphsOnDeath(), c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), c.canSleepMorphed(),
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    private static void place(ServerPlayer p, Vec3 pos, float yaw, float pitch) {
        p.setPos(pos.x, pos.y, pos.z);
        p.xo = pos.x;
        p.yo = pos.y;
        p.zo = pos.z;
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setYHeadRot(yaw);
        p.setXRot(pitch);
        p.xRotO = pitch;
    }

    /** Aims {@code p}'s look exactly at {@code target}'s bounding-box centre. */
    private static void aimAt(ServerPlayer p, Entity target) {
        Vec3 dir = target.getBoundingBox().getCenter().subtract(p.getEyePosition());
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(dir.y, horiz));
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setYHeadRot(yaw);
        p.setXRot(pitch);
        p.xRotO = pitch;
    }

    // ==================================================================
    // morph/player/identity
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void identityRoundTripsAndGuardsBareType(GameTestHelper helper) {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        MorphVariant steve = MorphVariant.ofPlayer(uuid, "Steve");

        helper.assertTrue(steve.isPlayer(), "ofPlayer must be a player variant");
        helper.assertTrue(steve.playerId().equals(Optional.of(uuid)),
                "playerId must be the given UUID but was " + steve.playerId());
        helper.assertTrue(steve.playerName().equals(Optional.of("Steve")),
                "playerName must be the given name but was " + steve.playerName());

        // disk CODEC round-trip
        var encoded = MorphVariant.CODEC
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, steve)
                .result().orElseThrow(() -> new IllegalStateException("encode failed"));
        MorphVariant fromNbt = MorphVariant.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded)
                .result().orElseThrow(() -> new IllegalStateException("parse failed"));
        helper.assertTrue(fromNbt.equals(steve),
                "CODEC round-trip must be lossless but got " + fromNbt);
        helper.assertTrue(fromNbt.isPlayer() && fromNbt.playerId().equals(Optional.of(uuid)),
                "decoded player variant must keep Id+Name");

        // wire STREAM_CODEC round-trip
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().registryAccess());
        MorphVariant.STREAM_CODEC.encode(buf, steve);
        MorphVariant fromWire = MorphVariant.STREAM_CODEC.decode(buf);
        helper.assertTrue(fromWire.equals(steve),
                "STREAM_CODEC round-trip must be lossless but got " + fromWire);

        // invariant guard: a bare minecraft:player (no Id) is NOT a player variant
        MorphVariant bare = MorphVariant.ofType(MorphVariant.PLAYER_TYPE);
        helper.assertFalse(bare.isPlayer(),
                "a bare minecraft:player (empty data) must NOT report isPlayer()");
        helper.assertTrue(bare.playerId().isEmpty() && bare.playerName().isEmpty(),
                "a bare player type must have no playerId/playerName");
        helper.succeed();
    }

    // ==================================================================
    // morph/player/acquire  (full kill path: never-discard + self-kill guard)
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void killAcquiresPlayerMorphWithoutDiscard(GameTestHelper helper) {
        ServerPlayer killer = mockPlayer(helper);
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer loner = mockPlayer(helper);
        UUID victimId = victim.getUUID();

        // the real acquisition trigger (fires for player victims too)
        CombatEvents.PLAYER_KILLED_LIVING.invoke(
                new CombatEvents.Kill(killer, victim));

        MorphState s = state(helper, killer);
        List<MorphVariant> players = playerVariants(s);
        helper.assertTrue(players.size() == 1,
                "killing a player must acquire exactly one player variant but got "
                        + players.size());
        helper.assertTrue(players.get(0).playerId().equals(Optional.of(victimId)),
                "the acquired player variant must carry the victim's UUID");
        helper.assertTrue(s.current().isPresent() && s.current().get().isPlayer()
                        && s.current().get().playerId().equals(Optional.of(victimId)),
                "the killer must be morphed into the victim (insta-morph)");
        // BLOCKER 1: a player victim is NEVER discarded (runs vanilla die/respawn)
        helper.assertTrue(victim.isAlive() && !victim.isRemoved(),
                "a player victim must NOT be discarded on acquisition");

        // BLOCKER 2: a self-attributed kill acquires nothing
        CombatEvents.PLAYER_KILLED_LIVING.invoke(
                new CombatEvents.Kill(loner, loner));
        helper.assertTrue(playerVariants(state(helper, loner)).isEmpty(),
                "a self-kill must never acquire a self player-morph");
        helper.succeed();
    }

    // ==================================================================
    // morph/player/config
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void playerMorphsConfigGate(GameTestHelper helper) {
        ServerPlayer killerOff = mockPlayer(helper);
        ServerPlayer victimOff = mockPlayer(helper);
        ServerPlayer killerOn = mockPlayer(helper);
        ServerPlayer victimOn = mockPlayer(helper);

        // Toggle config ATOMICALLY within one synchronous step so no concurrent
        // test observes playerMorphs=false; always restore in finally.
        MorphConfig original = Morph.CONFIG.get();
        try {
            Morph.CONFIG.set(withPlayerMorphs(original, false));
            CombatEvents.PLAYER_KILLED_LIVING.invoke(
                    new CombatEvents.Kill(killerOff, victimOff));
            helper.assertTrue(playerVariants(state(helper, killerOff)).isEmpty(),
                    "playerMorphs=false must acquire nothing from a player kill");

            Morph.CONFIG.set(withPlayerMorphs(original, true));
            CombatEvents.PLAYER_KILLED_LIVING.invoke(
                    new CombatEvents.Kill(killerOn, victimOn));
            helper.assertTrue(playerVariants(state(helper, killerOn)).size() == 1,
                    "playerMorphs=true must acquire a player variant from a player kill");
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    // ==================================================================
    // morph/player/acquire-dedup  (SAME UUID, DIFFERENT name → dedup, keep first)
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void acquireDedupByUuidKeepsFirstName(GameTestHelper helper) {
        ServerPlayer killer = mockPlayer(helper);
        UUID target = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // first acquire recorded as Alice (seeded — no transition lock)
        MorphVariant alice = MorphVariant.ofPlayer(target, "Alice");
        Morph.STATE.set(killer, new MorphState(Optional.of(alice), List.of(alice)));

        // second acquire: SAME UUID, DIFFERENT name (Bob) via the real seam
        ServerPlayer bob = mockPlayerNamed(helper, target, "Bob");
        boolean acquired = Morph.acquireTarget(killer, bob, /*discard=*/false,
                /*forced=*/true);

        helper.assertFalse(acquired,
                "re-acquiring the same player UUID must be a no-op (dedup by samePlayer)");
        List<MorphVariant> players = playerVariants(state(helper, killer));
        helper.assertTrue(players.size() == 1,
                "exactly one player entry must remain after a same-UUID re-acquire but got "
                        + players.size());
        helper.assertTrue(players.get(0).playerName().equals(Optional.of("Alice")),
                "the FIRST-acquired name must be preserved (Alice) but was "
                        + players.get(0).playerName());
        helper.succeed();
    }

    // ==================================================================
    // morph/player/profile
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void playerProfileHasNoAbilitiesOrDimensions(GameTestHelper helper) {
        MorphVariant player = MorphVariant.ofPlayer(UUID.randomUUID(), "Alex");
        ServerLevel level = helper.getLevel();

        MorphEntities.Profile profile = MorphEntities.profileOf(player, level);
        // Reference identity GUARDS the isPlayer short-circuit: deleting it makes
        // profileOf return the distinct BROKEN instance, failing this (the
        // field-value assertions below would still pass, since PLAYER_PROFILE and
        // BROKEN are field-for-field identical — hence this == check).
        helper.assertTrue(profile == MorphEntities.playerProfile(),
                "profileOf(playerVariant) must return the cached PLAYER_PROFILE "
                        + "instance (the §1.3 short-circuit)");
        helper.assertTrue(profile.abilities().isEmpty(),
                "a player morph must grant no abilities");
        helper.assertTrue(profile.dimensions() == null,
                "a player morph must defer to the vanilla player box (null dimensions)");
        helper.assertTrue(MorphEntities.create(player, level) == null,
                "create(playerVariant) must return null (no shared dummy)");
        helper.succeed();
    }

    // ==================================================================
    // morph/player/grouping  (each target player its own selector column)
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void groupingKeepsEachPlayerItsOwnColumn(GameTestHelper helper) {
        UUID x = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID y = UUID.fromString("99999999-9999-9999-9999-999999999999");
        MorphVariant p1 = MorphVariant.ofPlayer(x, "Alice");
        MorphVariant p2 = MorphVariant.ofPlayer(y, "Bob");
        MorphVariant cow = MorphVariant.ofType(BId.of("minecraft", "cow"));

        MorphState st = new MorphState(Optional.empty(), List.of(p1, cow, p2));

        var groups = st.groupedByKey();
        helper.assertTrue(groups.size() == 3,
                "two distinct players + one mob must yield three columns but got "
                        + groups.size());
        helper.assertTrue(st.variantsOfKey(p1.groupKey()).equals(List.of(p1)),
                "player X's column must hold exactly its one variant (no cross-player bleed)");
        helper.assertTrue(st.variantsOfKey(p2.groupKey()).equals(List.of(p2)),
                "player Y's column must hold exactly its one variant");
        helper.assertFalse(p1.groupKey().equals(p2.groupKey()),
                "two distinct player UUIDs must have distinct group keys");
        helper.assertTrue(st.variantsOfKey(cow.groupKey()).equals(List.of(cow)),
                "the mob column must hold the cow variant");
        helper.succeed();
    }

    // ==================================================================
    // morph/cmd/morphtarget  (looked-at PLAYER, no discard)
    // ==================================================================

    @GameTest(maxTicks = 60)
    public void morphTargetMorphsLookedAtPlayer(GameTestHelper helper) {
        ServerPlayer caller = mockPlayer(helper);
        ServerPlayer victim = mockPlayer(helper);
        UUID victimId = victim.getUUID();
        for (int z = 1; z <= 4; z++) {
            helper.setBlock(new BlockPos(1, 1, z), Blocks.STONE);
        }
        Vec3 callerStand = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        Vec3 victimStand = helper.absoluteVec(new Vec3(1.5, 2.0, 3.5));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    place(victim, victimStand, 180f, 0f);
                    place(caller, callerStand, 0f, 0f);
                })
                .thenExecuteAfter(2, () -> {
                    place(victim, victimStand, 180f, 0f);
                    place(caller, callerStand, 0f, 0f);
                    aimAt(caller, victim);
                    helper.assertTrue(Morph.morphTarget(caller),
                            "morphTarget on a looked-at player must return true");
                    MorphState s = state(helper, caller);
                    List<MorphVariant> players = playerVariants(s);
                    helper.assertTrue(players.size() == 1
                                    && players.get(0).playerId().equals(Optional.of(victimId)),
                            "morphTarget must acquire the looked-at player's variant");
                    helper.assertTrue(victim.isAlive() && !victim.isRemoved(),
                            "morphTarget must NOT discard the target player");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/locator/hide
    // ==================================================================

    @GameTest(maxTicks = 160)
    public void nonPlayerMorphHidesFromLocatorBar(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        player.setHealth(20.0f);
        seedMob(player, "cow");

        helper.startSequence()
                .thenExecuteAfter(settle(), () -> {
                    helper.assertTrue(waypointRange(player).hasModifier(LOCATOR_HIDE_ID),
                            "a non-player morph must carry the locator-hide modifier");
                    helper.assertFalse(player.isTransmittingWaypoint(),
                            "a non-player morph must not transmit a waypoint (range clamped to 0)");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/locator/demorph-restore
    // ==================================================================

    @GameTest(maxTicks = 280)
    public void demorphRestoresLocatorBar(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        player.setHealth(20.0f);
        seedMob(player, "cow");

        helper.startSequence()
                .thenExecuteAfter(settle(), () -> helper.assertTrue(
                        waypointRange(player).hasModifier(LOCATOR_HIDE_ID),
                        "precondition: the morphed player is hidden"))
                .thenExecute(() -> helper.assertTrue(Morph.demorph(player),
                        "demorph must succeed for a morphed player"))
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(waypointRange(player).hasModifier(LOCATOR_HIDE_ID),
                            "demorph must remove the locator-hide modifier");
                    helper.assertTrue(player.isTransmittingWaypoint(),
                            "a demorphed player must transmit a waypoint again");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/locator/player-visible
    // ==================================================================

    @GameTest(maxTicks = 160)
    public void playerMorphStaysVisibleOnLocatorBar(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        player.setHealth(20.0f);
        MorphVariant asOther =
                MorphVariant.ofPlayer(UUID.randomUUID(), "SomeoneElse");
        seedPlayerMorph(player, asOther);

        helper.startSequence()
                .thenExecuteAfter(settle(), () -> {
                    helper.assertTrue(
                            MorphAbilities.committedVariant(player).isPresent(),
                            "precondition: the player-morph must be committed");
                    helper.assertFalse(waypointRange(player).hasModifier(LOCATOR_HIDE_ID),
                            "a PLAYER morph must NOT be hidden (no locator-hide modifier)");
                    helper.assertTrue(player.isTransmittingWaypoint(),
                            "a player-morphed player must still transmit a waypoint");
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
