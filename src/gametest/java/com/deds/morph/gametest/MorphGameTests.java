package com.deds.morph.gametest;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Server gametests for the Morph module contract (mod id {@code deds_morph}):
 * kill-acquisition with instaMorph, no-duplicate acquisition, boss exclusion
 * ({@code bossMorphs=0}), and {@code Morph.STATE} attachment round-trips.
 *
 * <p><b>Wave-2 variant identity migration.</b> Morph identity is now a
 * {@link MorphVariant} (entity type + normalized NBT), not a bare {@link BId}.
 * Tests that KILL/acquire a real mob assert at the <b>type</b> level
 * ({@link MorphState#ownsType}/{@link MorphState#variantsOf}) — a spawned mob
 * carries surviving additional NBT ({@code CanBreakDoors}, …) so its computed
 * variant is not necessarily the default {@code ofType(...)} — while tests that
 * SEED state directly use explicit {@link MorphVariant#ofType} values whose
 * equality is stable. The semantics (acquire a zombie ⇒ own+wear a zombie;
 * second identical kill ⇒ no duplicate) are unchanged.</p>
 *
 * <p>Kills go through the REAL death-attribution path — the victim is hurt
 * with {@code level.damageSources().playerAttack(mockPlayer)} for far more
 * than its max health via {@code LivingEntity.hurtServer}. That drives
 * vanilla's {@code die(source)} with the player as the attributed killer, which
 * is what fires {@code CombatEvents.PLAYER_KILLED_LIVING} — the same pipeline a
 * live melee kill uses, minus the swing.</p>
 */
public final class MorphGameTests implements CustomTestMethodInvoker {

    /** Registry ids the contract appends: the victim's EntityType id. */
    private static final BId ZOMBIE_ID = BId.of("minecraft", "zombie");
    private static final BId SKELETON_ID = BId.of("minecraft", "skeleton");
    private static final BId WITHER_ID = BId.of("minecraft", "wither");

    /** Stable default-variant identities for SEEDED (non-kill) state. */
    private static final MorphVariant ZOMBIE = MorphVariant.ofType(ZOMBIE_ID);
    private static final MorphVariant SKELETON = MorphVariant.ofType(SKELETON_ID);

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * A registered mock server player (house style: registered via
     * makeMockServerPlayerInLevel so PlayerList knows it, de-registered
     * again before the test ends).
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runBeforeTestEnd(() ->
                helper.getLevel().getServer().getPlayerList().remove(player));
        return player;
    }

    /**
     * Kills {@code victim} through the real damage pipeline, attributed to
     * {@code killer}. 10000 damage overkills every mob in these tests (a
     * wither has 300 max health) in a single hit.
     */
    private static void killByPlayer(GameTestHelper helper, ServerPlayer killer,
            String what, LivingEntity victim) {
        boolean accepted = victim.hurtServer(helper.getLevel(),
                helper.getLevel().damageSources().playerAttack(killer), 10000f);
        helper.assertTrue(accepted,
                "the " + what + " should accept the mock player's attack "
                        + "(hurtServer returned false)");
    }

    private static MorphState state(GameTestHelper helper, ServerPlayer player) {
        MorphState state = Morph.STATE.get(player);
        if (state == null) {
            helper.fail("Morph.STATE.get must never return null "
                    + "(PlayerDataKey contract: the spec's default fills in)");
        }
        return state;
    }

    /** True if {@code current} is present and names a variant of {@code type}. */
    private static boolean wearsType(MorphState state, BId type) {
        return state.current().isPresent()
                && state.current().get().type().equals(type);
    }

    // ------------------------------------------------------------------
    // 1. acquisition + instaMorph
    // ------------------------------------------------------------------

    /**
     * Contract: player kills a living non-player mob → a variant of its
     * EntityType is appended to {@code acquired} AND the player auto-morphs into
     * it (original instaMorph default). A mock player melee-kills a zombie;
     * afterwards {@code acquired} owns a zombie-type variant and {@code current}
     * is a zombie-type variant.
     */
    @GameTest(maxTicks = 100)
    public void zombieKillAcquiresAndInstaMorphs(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Zombie zombie = helper.spawnWithNoFreeWill(
                EntityTypes.ZOMBIE, new BlockPos(1, 2, 1));

        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    MorphState before = state(helper, player);
                    helper.assertTrue(
                            before.acquired().isEmpty() && before.current().isEmpty(),
                            "a fresh mock player must start with no morphs but has "
                                    + before);
                })
                .thenExecute(() -> killByPlayer(helper, player, "zombie", zombie))
                .thenWaitUntil(() -> helper.assertTrue(zombie.isDeadOrDying(),
                        "the zombie should be dead after a 10000-damage player attack"))
                .thenWaitUntil(() -> {
                    MorphState after = state(helper, player);
                    helper.assertTrue(after.ownsType(ZOMBIE_ID),
                            "acquired should own a " + ZOMBIE_ID
                                    + " variant after the player killed a zombie but is "
                                    + after.acquired());
                    helper.assertTrue(wearsType(after, ZOMBIE_ID),
                            "instaMorph: current should be a " + ZOMBIE_ID
                                    + " variant right after the kill but is "
                                    + after.current());
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 2. no duplicates
    // ------------------------------------------------------------------

    /**
     * Contract: acquiring the SAME variant is appended only once. Two identical
     * zombies normalize to the same variant (Pos/Health/etc. stripped) → exactly
     * one zombie entry. The post-kill wait is a fixed delay: the second kill's
     * expected effect is "nothing new appears", which cannot be waited FOR.
     */
    @GameTest(maxTicks = 160)
    public void secondZombieKillAddsNoDuplicate(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        Zombie first = helper.spawnWithNoFreeWill(
                EntityTypes.ZOMBIE, new BlockPos(1, 2, 1));
        Zombie second = helper.spawnWithNoFreeWill(
                EntityTypes.ZOMBIE, new BlockPos(3, 2, 1));

        helper.startSequence()
                .thenExecuteAfter(1, () ->
                        killByPlayer(helper, player, "first zombie", first))
                .thenWaitUntil(() -> helper.assertTrue(
                        state(helper, player).ownsType(ZOMBIE_ID),
                        "first zombie kill should acquire a " + ZOMBIE_ID
                                + " variant"))
                .thenExecute(() ->
                        killByPlayer(helper, player, "second zombie", second))
                .thenWaitUntil(() -> helper.assertTrue(second.isDeadOrDying(),
                        "the second zombie should be dead"))
                .thenExecuteAfter(10, () -> {
                    List<MorphVariant> zombies =
                            state(helper, player).variantsOf(ZOMBIE_ID);
                    helper.assertTrue(zombies.size() == 1,
                            "acquired must hold exactly one " + ZOMBIE_ID
                                    + " variant after two identical zombie kills "
                                    + "but has " + zombies);
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 3. boss exclusion (bossMorphs=0)
    // ------------------------------------------------------------------

    /**
     * Contract: ender dragon and wither are excluded ({@code bossMorphs=0}).
     * The player's state is seeded to a known value (zombie acquired and
     * current — via STATE.set, so this test does not depend on a prior kill),
     * then the player kills a REAL wither through the same hurt path as test
     * 1; the state must be exactly unchanged: no {@code minecraft:wither} in
     * acquired and no auto-morph away from zombie.
     */
    @GameTest(maxTicks = 160)
    public void witherKillIsExcludedFromAcquisition(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        MorphState seeded = new MorphState(
                Optional.of(ZOMBIE), List.of(ZOMBIE));
        Morph.STATE.set(player, seeded);

        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        WitherBoss wither = helper.spawnWithNoFreeWill(
                EntityTypes.WITHER, new BlockPos(2, 2, 2));

        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    wither.setInvulnerableTicks(0);
                    killByPlayer(helper, player, "wither", wither);
                })
                .thenWaitUntil(() -> helper.assertTrue(wither.isDeadOrDying(),
                        "the wither should be dead after a 10000-damage player "
                                + "attack with its invulnerability cleared"))
                // Fixed delay again: "state did NOT change" cannot be waited for.
                .thenExecuteAfter(10, () -> {
                    MorphState after = state(helper, player);
                    helper.assertFalse(after.ownsType(WITHER_ID),
                            "bossMorphs=0: killing a wither must not acquire a "
                                    + WITHER_ID + " variant but acquired is "
                                    + after.acquired());
                    helper.assertTrue(after.equals(seeded),
                            "killing a wither must leave the morph state exactly "
                                    + "unchanged (was " + seeded + ", is " + after + ")");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 4 + 5. STATE attachment round-trips (SELECT fallback — class Javadoc)
    // ------------------------------------------------------------------

    /**
     * STATE round-trip with an empty {@code current}: writes
     * {@code acquired=[zombie], current=empty} via {@code PlayerDataKey.set} and
     * reads back the identical record.
     */
    @GameTest(maxTicks = 60)
    public void stateRoundTripsWithEmptyCurrent(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        MorphState written = new MorphState(
                Optional.empty(), List.of(ZOMBIE));
        Morph.STATE.set(player, written);

        helper.succeedWhen(() -> {
            MorphState read = state(helper, player);
            helper.assertTrue(read.equals(written),
                    "STATE.get should return the record STATE.set stored (wrote "
                            + written + ", read " + read + ")");
        });
    }

    /**
     * Persistence shape: a two-entry {@code acquired} list survives a
     * set/get round-trip as an equal record with acquisition order preserved
     * (zombie first, skeleton second — and current pointing at the second
     * entry, a valid post-SELECT shape).
     */
    @GameTest(maxTicks = 60)
    public void statePreservesTwoEntryAcquisitionOrder(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        MorphState written = new MorphState(
                Optional.of(SKELETON), List.of(ZOMBIE, SKELETON));
        Morph.STATE.set(player, written);

        helper.succeedWhen(() -> {
            MorphState read = state(helper, player);
            helper.assertTrue(read.equals(written),
                    "STATE.get should return the record STATE.set stored (wrote "
                            + written + ", read " + read + ")");
            List<MorphVariant> acquired = read.acquired();
            helper.assertTrue(acquired.size() == 2
                            && acquired.get(0).equals(ZOMBIE)
                            && acquired.get(1).equals(SKELETON),
                    "acquisition order must be preserved ([" + ZOMBIE + ", "
                            + SKELETON + "]) but acquired is " + acquired);
        });
    }

    // ------------------------------------------------------------------
    // SELECT rule via the public server seam
    // ------------------------------------------------------------------

    /** SELECT with an OWNED target applies it as the current morph. */
    @GameTest(maxTicks = 60)
    public void selectOwnedMorphApplies(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(
                Optional.empty(), List.of(ZOMBIE)));

        Morph.select(player, Optional.of(ZOMBIE));

        helper.succeedWhen(() -> helper.assertTrue(
                state(helper, player).current().equals(Optional.of(ZOMBIE)),
                "selecting an owned morph must set it as current"));
    }

    /** SELECT with an UNOWNED target is ignored — state fully unchanged. */
    @GameTest(maxTicks = 60)
    public void selectUnownedIdIgnored(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        MorphState seeded = new MorphState(
                Optional.of(ZOMBIE), List.of(ZOMBIE));
        Morph.STATE.set(player, seeded);

        Morph.select(player, Optional.of(SKELETON));

        helper.succeedWhen(() -> helper.assertTrue(
                state(helper, player).equals(seeded),
                "selecting an unowned morph must change nothing"));
    }

    /** SELECT(empty) demorphs but keeps the acquired list. */
    @GameTest(maxTicks = 60)
    public void selectEmptyDemorphs(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(
                Optional.of(ZOMBIE), List.of(ZOMBIE)));

        Morph.select(player, Optional.empty());

        helper.succeedWhen(() -> {
            MorphState read = state(helper, player);
            helper.assertTrue(read.current().isEmpty(),
                    "SELECT(empty) must demorph");
            helper.assertTrue(read.acquired().equals(List.of(ZOMBIE)),
                    "demorphing must keep the acquired list");
        });
    }

    /**
     * Disk-codec round-trip: the empty-current shape (field omission), a
     * populated shape, a favourites-bearing shape, AND a VARIANT-bearing shape
     * (non-empty {@code data}, exercising the {@code {type,data}} codec branch)
     * all survive NBT encode/parse as equal records — so both the compact
     * bare-string default form and the verbose variant form persist losslessly.
     */
    @GameTest(maxTicks = 40)
    public void codecRoundTripsThroughNbt(GameTestHelper helper) {
        CompoundTag slimeData = new CompoundTag();
        slimeData.putInt("Size", 2);
        MorphVariant bigSlime =
                new MorphVariant(BId.of("minecraft", "slime"), slimeData);

        MorphState demorphed = new MorphState(
                Optional.empty(), List.of(ZOMBIE, SKELETON));
        MorphState morphed = new MorphState(
                Optional.of(SKELETON), List.of(ZOMBIE, SKELETON));
        MorphState favourited = new MorphState(
                Optional.of(SKELETON), List.of(ZOMBIE, SKELETON),
                List.of(SKELETON));
        MorphState withVariant = new MorphState(
                Optional.of(bigSlime), List.of(ZOMBIE, bigSlime),
                List.of(bigSlime));
        for (MorphState original :
                List.of(demorphed, morphed, favourited, withVariant)) {
            var encoded = MorphState.CODEC
                    .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, original)
                    .result().orElseThrow(() -> new IllegalStateException(
                            "encode failed for " + original));
            MorphState decoded = MorphState.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded)
                    .result().orElseThrow(() -> new IllegalStateException(
                            "parse failed for " + original));
            helper.assertTrue(decoded.equals(original),
                    "codec round-trip must be lossless (wrote " + original
                            + ", read " + decoded + ")");
        }
        helper.succeed();
    }

    /**
     * SAVE MIGRATION: a wave-1-shaped disk record — {@code acquired} as a list
     * of bare id STRINGS, no {@code current}, no {@code favourites} — still
     * parses, each bare string migrating to a DEFAULT {@link MorphVariant} via
     * {@link MorphVariant#CODEC}'s legacy branch, with no datafixer code. Built
     * as raw NBT so it genuinely has the old shape.
     */
    @GameTest(maxTicks = 40)
    public void codecLoadsLegacyBareIdState(GameTestHelper helper) {
        net.minecraft.nbt.CompoundTag legacy = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag acquired = new net.minecraft.nbt.ListTag();
        acquired.add(net.minecraft.nbt.StringTag.valueOf(ZOMBIE_ID.toString()));
        acquired.add(net.minecraft.nbt.StringTag.valueOf(SKELETON_ID.toString()));
        legacy.put("acquired", acquired); // no "current", no "favourites"

        MorphState decoded = MorphState.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, legacy)
                .result().orElseThrow(() -> new IllegalStateException(
                        "a legacy (bare-id) record must still parse"));

        helper.assertTrue(decoded.current().isEmpty(),
                "a missing current must load as own form");
        helper.assertTrue(decoded.acquired().equals(List.of(ZOMBIE, SKELETON)),
                "legacy bare ids must migrate to default variants but is "
                        + decoded.acquired());
        helper.assertTrue(decoded.favourites().isEmpty(),
                "a legacy record with no favourites field must load with empty "
                        + "favourites but is " + decoded.favourites());
        helper.succeed();
    }

    /**
     * Favourite toggle ({@code Morph.toggleFavourite}, the server seam the
     * {@code deds_morph:favourite} C2S message delegates to): toggling an OWNED
     * variant stars it (STARRED), toggling again unstars it (UNSTARRED).
     */
    @GameTest(maxTicks = 60)
    public void favouriteToggleStarsThenUnstarsOwnedMorph(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(
                Optional.of(ZOMBIE), List.of(ZOMBIE, SKELETON)));

        Morph.FavouriteResult first = Morph.toggleFavourite(player, SKELETON);
        helper.assertTrue(first == Morph.FavouriteResult.STARRED,
                "first toggle of an owned morph must STAR it but was " + first);
        helper.assertTrue(state(helper, player).isFavourite(SKELETON),
                "the starred morph must now be a favourite");

        Morph.FavouriteResult second = Morph.toggleFavourite(player, SKELETON);
        helper.assertTrue(second == Morph.FavouriteResult.UNSTARRED,
                "second toggle must UNSTAR it but was " + second);

        helper.succeedWhen(() -> helper.assertFalse(
                state(helper, player).isFavourite(SKELETON),
                "the unstarred morph must no longer be a favourite"));
    }

    /**
     * Favourite guard: toggling a NEVER-acquired variant returns NOT_OWNED and
     * changes nothing. The own form is never a {@link MorphVariant} in
     * {@code acquired}, so this is also "cannot favourite your own form".
     */
    @GameTest(maxTicks = 60)
    public void favouriteOfUnownedIsRejected(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        MorphState seeded = new MorphState(Optional.empty(), List.of(ZOMBIE));
        Morph.STATE.set(player, seeded);

        Morph.FavouriteResult result = Morph.toggleFavourite(player, SKELETON);

        helper.assertTrue(result == Morph.FavouriteResult.NOT_OWNED,
                "favouriting a non-owned variant must return NOT_OWNED but was "
                        + result);
        helper.succeedWhen(() -> helper.assertTrue(
                state(helper, player).equals(seeded),
                "a rejected favourite toggle must change nothing"));
    }

    /**
     * Remove path (the {@code deds_morph:remove} C2S seam,
     * {@code Morph.removeMorph}): removing a favourited, non-worn variant
     * succeeds (REMOVED) and drops it from BOTH {@code acquired} and
     * {@code favourites} — the record's {@code without} favourite-cleanup.
     */
    @GameTest(maxTicks = 60)
    public void removeDropsMorphFromAcquiredAndFavourites(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        // own form worn (so SKELETON is not the current morph), SKELETON starred
        Morph.STATE.set(player, new MorphState(
                Optional.empty(), List.of(ZOMBIE, SKELETON),
                List.of(SKELETON)));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(state(helper, player).isFavourite(SKELETON),
                            "precondition: SKELETON is a favourite");
                    helper.assertFalse(Morph.isMorphing(player),
                            "seed must not start a transition");
                    Morph.RemoveResult rr = Morph.removeMorph(player, SKELETON);
                    helper.assertTrue(rr == Morph.RemoveResult.REMOVED,
                            "removing an owned, non-worn morph must return REMOVED but was " + rr);
                    MorphState after = state(helper, player);
                    helper.assertFalse(after.owns(SKELETON),
                            "the removed morph must be gone from acquired but is " + after.acquired());
                    helper.assertFalse(after.isFavourite(SKELETON),
                            "removing a favourited morph must also drop it from favourites but is "
                                    + after.favourites());
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
