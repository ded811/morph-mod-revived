package com.deds.morph.gametest;

import com.deds.api.event.CombatEvents;
import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphConfig;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server gametests for Morph wave 5 — playtest round 3 (feature ids
 * {@code morph/hitbox/reconcile}, {@code morph/acquire/morphed-victim},
 * {@code morph/nametag/named-mob}, {@code morph/skin/offline-resolve}). Source of
 * truth: {@code docs/specs/morph/wave5/playtest-round-3.md}. Mod id
 * {@code deds_morph}. Recreation of iChun's Morph; all credit to iChun.
 *
 * <p>The animation items (A) are client-render only and thus <b>[manual]</b>; the
 * render halves of D and E likewise. What IS server-catchable is here.</p>
 */
public final class MorphWave5GameTests implements CustomTestMethodInvoker {

    /** The transient step-height modifier id applied by {@code MorphAbilities}. */
    private static final Identifier STEP_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(Morph.MOD_ID, "morph_step");

    /** A ZOMBIE is the non-negotiable hitbox subject: 0.6x1.95, eye 1.74 — exactly
     *  player-WIDTH, so a width-only self-heal cannot mask the bug (a cow can). */
    private static final MorphVariant ZOMBIE =
            MorphVariant.ofType(BId.of("minecraft", "zombie"));
    private static final MorphVariant COW =
            MorphVariant.ofType(BId.of("minecraft", "cow"));

    private static final float EPS = 1.0e-3f;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return mockPlayerNamed(helper, UUID.randomUUID(), "test-morph-player");
    }

    /** A SURVIVAL mock server player with a specific identity (the creative
     *  {@code GameTestHelper} mock breaks kill/target paths). */
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

    private static List<MorphVariant> playerVariants(MorphState state) {
        return state.acquired().stream().filter(MorphVariant::isPlayer).toList();
    }

    private static MorphConfig withPlayerMorphs(MorphConfig c, boolean value) {
        return new MorphConfig(c.childMorphs(), value, c.bossMorphs(),
                c.blacklistedMobs(), c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), c.loseMorphsOnDeath(), c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), c.canSleepMorphed(),
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    // ==================================================================
    // morph/hitbox/reconcile — the box must survive a REAL respawn
    // ==================================================================

    /**
     * Wave 5 item B. Supersedes the old cow-based test, which could not fail: it
     * never respawned (so it only exercised the LOGIN path, where {@code COMMITTED}
     * is empty and {@code commit()} already worked) and used a COW (width 0.9 != 0.6,
     * so the old WIDTH-ONLY self-heal fired). A zombie is exactly player-width, so
     * only a width+height+eye compare — plus the {@code Committed} owner check that
     * forces a re-commit onto the NEW {@code ServerPlayer} — can pass this.
     *
     * <p>Also asserts the STEP_HEIGHT {@code morph_step} transient modifier is back
     * after the respawn: {@code ServerPlayer.restoreFrom} calls
     * {@code assignBaseValues}/{@code assignPermanentModifiers}, never
     * {@code assignAllValues}, so every transient modifier is dropped on death and
     * is only restored by the ability set being re-{@code apply()}d.</p>
     */
    @GameTest(maxTicks = 200)
    public void morphedBoxSurvivesRespawn(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity zombie = helper.spawnWithNoFreeWill(
                EntityTypes.ZOMBIE, new BlockPos(3, 2, 3));
        // Seeded (no transition) so the commit lands on the next ability tick.
        Morph.STATE.set(player, new MorphState(Optional.of(ZOMBIE), List.of(ZOMBIE)));
        ServerPlayer[] after = new ServerPlayer[1];

        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    // Pre-condition: the ORIGINAL player wears the zombie box.
                    helper.assertTrue(
                            Math.abs(player.getBbHeight() - zombie.getBbHeight()) < EPS,
                            "precondition: morphed height must be the zombie's "
                                    + zombie.getBbHeight() + " but is "
                                    + player.getBbHeight());
                    // A REAL respawn: builds a brand-new ServerPlayer (and copies the
                    // old entity id, so getId() is not an identity discriminator).
                    after[0] = helper.getLevel().getServer().getPlayerList()
                            .respawn(player, false,
                                    net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    helper.assertTrue(after[0] != null,
                            "respawn must return the new ServerPlayer");
                    ServerPlayer respawned = after[0];
                    helper.runBeforeTestEnd(() -> helper.getLevel().getServer()
                            .getPlayerList().remove(respawned));
                })
                .thenExecuteAfter(40, () -> {
                    ServerPlayer respawned = after[0];
                    helper.assertTrue(respawned != player,
                            "respawn must produce a DIFFERENT ServerPlayer instance");
                    helper.assertTrue(
                            state(helper, respawned).current().equals(Optional.of(ZOMBIE)),
                            "the morph must persist across respawn but is "
                                    + state(helper, respawned).current());
                    EntityDimensions want =
                            respawned.getDimensions(respawned.getPose());
                    // HEIGHT + EYE are the discriminating assertions (width is 0.6
                    // for both a zombie morph and a vanilla player).
                    helper.assertTrue(
                            Math.abs(respawned.getBbHeight() - want.height()) < EPS,
                            "after respawn the box HEIGHT must be the morph's "
                                    + want.height() + " but is "
                                    + respawned.getBbHeight());
                    helper.assertTrue(
                            Math.abs(respawned.getBbHeight() - zombie.getBbHeight()) < EPS,
                            "after respawn the height must match a real zombie ("
                                    + zombie.getBbHeight() + ") but is "
                                    + respawned.getBbHeight());
                    // INDEPENDENT oracle (review finding 10): the live zombie's own
                    // eye (1.74). Comparing against want.eyeHeight() was
                    // tautological — refreshDimensions writes the eye field from
                    // the very same EntityDimensions the height assertion uses, so
                    // it could never fail independently (a regression to the 0.85
                    // default eye would have shipped green).
                    helper.assertTrue(
                            Math.abs(respawned.getEyeHeight() - zombie.getEyeHeight())
                                    < EPS,
                            "after respawn the EYE height must match a real zombie's ("
                                    + zombie.getEyeHeight() + ") but is "
                                    + respawned.getEyeHeight());
                })
                .thenSucceed();
    }

    /**
     * The ABILITY half of the same respawn reconcile (wave 5 item B2). A CAMEL is
     * used rather than the zombie above because the step ability is only granted by
     * a morph whose STEP_HEIGHT attribute EXCEEDS the player's 0.6 — a zombie's is
     * exactly 0.6, so it never carries the modifier and the assertion would be
     * vacuous; a camel's is 1.5 (javap-verified).
     *
     * <p>{@code ServerPlayer.restoreFrom} calls {@code assignBaseValues}/
     * {@code assignPermanentModifiers}, never {@code assignAllValues}, so EVERY
     * transient attribute modifier is dropped on death. Only the {@code Committed}
     * owner check — which forces the full {@code commit()} to re-run on the new
     * entity — puts the {@code morph_step} modifier back.</p>
     */
    @GameTest(maxTicks = 200)
    public void morphAbilitiesReapplyAfterRespawn(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        MorphVariant camel = MorphVariant.ofType(BId.of("minecraft", "camel"));
        Morph.STATE.set(player, new MorphState(Optional.of(camel), List.of(camel)));
        ServerPlayer[] after = new ServerPlayer[1];

        helper.startSequence()
                .thenExecuteAfter(20, () -> {
                    AttributeInstance step =
                            player.getAttribute(Attributes.STEP_HEIGHT);
                    helper.assertTrue(step != null
                                    && step.hasModifier(STEP_MODIFIER_ID),
                            "precondition: a camel morph must carry the morph_step "
                                    + "transient modifier before the respawn");
                    after[0] = helper.getLevel().getServer().getPlayerList()
                            .respawn(player, false,
                                    net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    ServerPlayer respawned = after[0];
                    helper.runBeforeTestEnd(() -> helper.getLevel().getServer()
                            .getPlayerList().remove(respawned));
                })
                .thenExecuteAfter(40, () -> {
                    ServerPlayer respawned = after[0];
                    AttributeInstance step =
                            respawned.getAttribute(Attributes.STEP_HEIGHT);
                    helper.assertTrue(step != null
                                    && step.hasModifier(STEP_MODIFIER_ID),
                            "the morph_step transient modifier must be re-applied "
                                    + "after respawn (restoreFrom drops transients)");
                })
                .thenSucceed();
    }

    /**
     * The demorph half of the same invariant: a player who demorphs must return to
     * EXACTLY the vanilla player box, and the per-tick reconcile must not fight it.
     */
    @GameTest(maxTicks = 260)
    public void demorphReturnsExactPlayerBox(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        ServerPlayer reference = mockPlayer(helper); // never morphed
        Morph.STATE.set(player, new MorphState(Optional.of(ZOMBIE), List.of(ZOMBIE)));

        helper.startSequence()
                .thenExecuteAfter(20, () -> helper.assertTrue(
                        Math.abs(player.getBbHeight() - reference.getBbHeight()) > EPS,
                        "precondition: the morphed box must differ from a plain player"))
                .thenExecute(() -> helper.assertTrue(Morph.demorph(player),
                        "demorph must succeed"))
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 30, () -> {
                    helper.assertTrue(
                            Math.abs(player.getBbWidth() - reference.getBbWidth()) < EPS
                                    && Math.abs(player.getBbHeight()
                                            - reference.getBbHeight()) < EPS
                                    && Math.abs(player.getEyeHeight()
                                            - reference.getEyeHeight()) < EPS,
                            "after demorph the box must be exactly the vanilla player's "
                                    + reference.getBbWidth() + "x"
                                    + reference.getBbHeight() + "/eye "
                                    + reference.getEyeHeight() + " but is "
                                    + player.getBbWidth() + "x" + player.getBbHeight()
                                    + "/eye " + player.getEyeHeight());
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/acquire/morphed-victim — you acquire what the victim APPEARS as
    // ==================================================================

    /** (1) Victim morphed as a cow → the killer acquires the COW, learns NO player
     *  identity, and the victim is still not discarded. */
    @GameTest(maxTicks = 40)
    public void killingMorphedVictimGivesTheirCurrentForm(GameTestHelper helper) {
        ServerPlayer killer = mockPlayer(helper);
        ServerPlayer victim = mockPlayer(helper);
        Morph.STATE.set(victim, new MorphState(Optional.of(COW), List.of(COW)));

        CombatEvents.PLAYER_KILLED_LIVING.invoke(
                new CombatEvents.Kill(killer, victim));

        MorphState s = state(helper, killer);
        helper.assertTrue(s.owns(COW),
                "killing a cow-morphed player must acquire the COW variant but got "
                        + s.acquired());
        helper.assertTrue(playerVariants(s).isEmpty(),
                "killing a disguised player must NOT hand over their player identity");
        helper.assertTrue(s.current().equals(Optional.of(COW)),
                "the killer must be wearing the acquired cow");
        helper.assertTrue(victim.isAlive() && !victim.isRemoved(),
                "a player victim must never be discarded");
        helper.succeed();
    }

    /** (2) Regression guard: an UNMORPHED victim still yields their player variant. */
    @GameTest(maxTicks = 40)
    public void killingUnmorphedVictimGivesPlayerForm(GameTestHelper helper) {
        ServerPlayer killer = mockPlayer(helper);
        ServerPlayer victim = mockPlayer(helper);
        UUID victimId = victim.getUUID();

        CombatEvents.PLAYER_KILLED_LIVING.invoke(
                new CombatEvents.Kill(killer, victim));

        List<MorphVariant> players = playerVariants(state(helper, killer));
        helper.assertTrue(players.size() == 1
                        && players.get(0).playerId().equals(Optional.of(victimId)),
                "killing an unmorphed player must still acquire their player variant");
        helper.succeed();
    }

    /** (3) The {@code playerMorphs} gate applies to the EFFECTIVE variant: with it
     *  OFF, a cow-morphed victim still yields the cow (a mob result must not be
     *  blocked by the player gate). */
    @GameTest(maxTicks = 40)
    public void playerMorphsGateDoesNotBlockMobResult(GameTestHelper helper) {
        ServerPlayer killer = mockPlayer(helper);
        ServerPlayer victim = mockPlayer(helper);
        Morph.STATE.set(victim, new MorphState(Optional.of(COW), List.of(COW)));

        MorphConfig original = Morph.CONFIG.get();
        try {
            Morph.CONFIG.set(withPlayerMorphs(original, false));
            CombatEvents.PLAYER_KILLED_LIVING.invoke(
                    new CombatEvents.Kill(killer, victim));
            helper.assertTrue(state(helper, killer).owns(COW),
                    "playerMorphs=false must NOT block a MOB result from a morphed "
                            + "victim but acquired " + state(helper, killer).acquired());
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    // ==================================================================
    // morph/nametag/named-mob — identity half (render half is [manual])
    // ==================================================================

    /**
     * Wave 5 item D identity: {@code CustomName} must SURVIVE variant normalization,
     * so a name-tagged mob is its OWN morph (a separate selector column) and the
     * dummy carries the name for the mob-style tag. Round-trips through both codecs.
     */
    @GameTest(maxTicks = 60)
    public void namedMobIsItsOwnVariant(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        LivingEntity plain = helper.spawnWithNoFreeWill(
                EntityTypes.SHEEP, new BlockPos(1, 2, 1));
        LivingEntity named = helper.spawnWithNoFreeWill(
                EntityTypes.SHEEP, new BlockPos(3, 2, 1));
        named.setCustomName(Component.literal("Dinnerbone"));

        MorphVariant plainVariant = Morph.variantOf(plain);
        MorphVariant namedVariant = Morph.variantOf(named);

        helper.assertFalse(namedVariant.equals(plainVariant),
                "a name-tagged mob must be a DISTINCT variant from the plain one");
        helper.assertTrue(namedVariant.data().contains("CustomName"),
                "CustomName must survive normalization but data is "
                        + namedVariant.data());

        // CustomNameVisible half, DETERMINISTIC (review finding 9): the SAME mob,
        // so the two variants differ in the visible flag alone — vanilla writes
        // the key only when true, so the flag flips the variant identity iff it
        // survives normalization (re-adding it to TRANSIENT_KEYS fails this).
        MorphVariant visibleVariant;
        named.setCustomNameVisible(true);
        try {
            visibleVariant = Morph.variantOf(named);
        } finally {
            named.setCustomNameVisible(false);
        }
        helper.assertTrue(visibleVariant.data().contains("CustomNameVisible"),
                "CustomNameVisible must survive normalization but data is "
                        + visibleVariant.data());
        helper.assertFalse(visibleVariant.equals(namedVariant),
                "two variants differing ONLY in CustomNameVisible must be distinct");

        // Same selector column: groupKey for a mob variant is the TYPE, so the
        // named sheep is a horizontal entry in the sheep column, never its own
        // column (pins the intended UX; fails if groupKey ever keys on the name).
        helper.assertTrue(namedVariant.groupKey().equals(plainVariant.groupKey()),
                "named and plain sheep must share one selector column (type key)");

        // Disk AND wire round-trips keep the name (spec mandates both codecs).
        var encoded = MorphVariant.CODEC
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, namedVariant)
                .result().orElseThrow(() -> new IllegalStateException("encode failed"));
        MorphVariant decoded = MorphVariant.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded)
                .result().orElseThrow(() -> new IllegalStateException("parse failed"));
        helper.assertTrue(decoded.equals(namedVariant),
                "the named variant must round-trip losslessly but got " + decoded);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(),
                helper.getLevel().registryAccess());
        MorphVariant.STREAM_CODEC.encode(buf, visibleVariant);
        MorphVariant fromWire = MorphVariant.STREAM_CODEC.decode(buf);
        helper.assertTrue(fromWire.equals(visibleVariant),
                "the named variant must survive the WIRE codec but got " + fromWire);
        helper.succeed();
    }

    // ==================================================================
    // morph/skin/offline-resolve — pure-static profile half ([manual] resolution)
    // ==================================================================

    /**
     * Wave 5 item E: the offline fallback profile must carry the REAL stored UUID
     * and no texture properties — never {@code UUIDUtil.createOfflineProfile(name)},
     * which is {@code new GameProfile(nameUUIDFromBytes("OfflinePlayer:"+name), name)}
     * (a fake name-hash id) and threw away the very UUID the skin resolve needs.
     */
    @GameTest(maxTicks = 20)
    public void offlineFallbackProfileKeepsRealUuid(GameTestHelper helper) {
        UUID stored = UUID.fromString("1234abcd-5678-90ef-1234-567890abcdef");
        String name = "Notch";

        GameProfile profile = MorphVariant.ofPlayer(stored, name).playerProfile()
                .orElseThrow(() -> new IllegalStateException(
                        "a player variant must yield an identity profile"));

        helper.assertTrue(profile.id().equals(stored),
                "the fallback profile must keep the REAL stored UUID but was "
                        + profile.id());
        helper.assertTrue(profile.name().equals(name),
                "the fallback profile must keep the username");
        helper.assertTrue(profile.properties().isEmpty(),
                "the fallback profile carries no textures (resolution happens via "
                        + "PlayerSkinRenderCache/ResolvableProfile, by UUID)");

        // Negative assertion on a REAL acquired variant (review finding 14 — the
        // old form compared two compile-time constants and could never fail): kill
        // an actual mock victim through the real trigger and prove the ACQUISITION
        // path stored the victim's real UUID, not the OfflinePlayer name-hash the
        // old createOfflineProfile fallback would have produced.
        ServerPlayer killer = mockPlayer(helper);
        ServerPlayer victim = mockPlayerNamed(helper, UUID.randomUUID(), "Herobrine");
        CombatEvents.PLAYER_KILLED_LIVING.invoke(
                new CombatEvents.Kill(killer, victim));
        List<MorphVariant> acquired = playerVariants(state(helper, killer));
        helper.assertTrue(acquired.size() == 1,
                "the kill must acquire exactly one player variant");
        MorphVariant real = acquired.get(0);
        helper.assertTrue(real.playerId().equals(Optional.of(victim.getUUID())),
                "the acquired variant must store the victim's REAL UUID");
        helper.assertFalse(real.playerId().get().equals(
                        UUIDUtil.createOfflinePlayerUUID(real.playerName().orElse(""))),
                "the acquired variant's Id must NOT be the OfflinePlayer name-hash "
                        + "(guards against regressing to createOfflineProfile)");
        helper.assertTrue(real.playerProfile()
                        .map(p -> p.id().equals(victim.getUUID())).orElse(false),
                "the identity profile built from the acquired variant must carry "
                        + "the victim's real UUID");
        helper.succeed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
