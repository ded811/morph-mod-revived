package com.deds.morph.gametest;

import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;
import com.deds.morph.MorphConfig;
import com.deds.morph.MorphSleep;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import com.deds.api.id.BId;

import com.mojang.datafixers.util.Either;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Unit;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.TagValueOutput;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Morph wave 10 — the five config options that loaded and did nothing, the two
 * missing halves of the {@code ability-fly} row, and the derivation behind the
 * second of them. Cited against iChun's published {@code legacy} source
 * ({@code O:} = {@code ../misc/morph-source-github/}).
 *
 * <ul>
 * <li>{@code morph/config/child-morphs} — {@code O:morph/common/core/
 *     EntityHelper.java:49}</li>
 * <li>{@code morph/config/blacklisted-mobs} — {@code O:morph/common/core/
 *     CommonProxy.java:24-43} + {@code O:EntityHelper.java:53-59}</li>
 * <li>{@code morph/config/can-sleep-morphed} — {@code O:morph/common/core/
 *     EventHandler.java:651-668} (default deliberately INVERTED, D10-3)</li>
 * <li>{@code morph/config/lose-morphs-on-death} — {@code O:morph/common/core/
 *     EventHandler.java:694-725}</li>
 * <li>{@code morph/ability/fly-hunger} + {@code morph/ability/fly-water} —
 *     {@code O:morph/common/ability/AbilityFly.java:58-103}</li>
 * </ul>
 *
 * <p>The fifth option, {@code allowMorphSelection}, is CLIENT-only by
 * construction ({@code MorphSelector} is {@code @Environment(CLIENT)} and
 * cannot even be loaded in a dedicated-server gametest), so its assertion lives
 * in {@code MorphRenderClientTest} — as does the sleeping-jitter amplitude
 * measurement and the ability-icon overflow scroll.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original to iChun.</p>
 */
public final class MorphWave10GameTests implements CustomTestMethodInvoker {

    /** Registered mock server player, de-registered before the test ends. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runBeforeTestEnd(() ->
                helper.getLevel().getServer().getPlayerList().remove(player));
        return player;
    }

    /** Day time {@code deds_morph_test:night} writes. */
    private static final int MIDNIGHT_TICKS = 18000;

    /**
     * Re-pins the overworld clock to midnight, IN THE TEST BODY.
     *
     * <p>{@code minecraft:clock_time} is a ONE-SHOT — {@code ClockTime.setup}
     * calls {@code setTotalTicks} once at batch setup and the clock then
     * FREE-RUNS, by an unbounded and nondeterministic number of ticks, because
     * batch order is nondeterministic and every other batch advances the same
     * clock (Playbook §8d; measured 14 to ~3,560 ticks on a sibling module).
     * Any test whose outcome depends on the time of day must therefore re-pin
     * it where it runs. {@code updateSkyBrightness()} follows because
     * {@code setup} does not call it and {@code skyDarken} is otherwise
     * refreshed only once per server tick, so a same-tick
     * {@code isDarkOutside()} would read the pre-pin value.
     *
     * <p>The {@code environment =} annotation stays: it buys the batch and its
     * teardown restores the clock, so this write does not leak.
     */
    private static void pinMidnight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.clockManager().setTotalTicks(
                level.registryAccess()
                        .lookupOrThrow(Registries.WORLD_CLOCK)
                        .getOrThrow(WorldClocks.OVERWORLD),
                MIDNIGHT_TICKS);
        level.updateSkyBrightness();
    }

    /** Spawns a free-will-less mob on a fresh stone pedestal at (x,2,z). */
    private static <E extends Mob> E spawn(GameTestHelper helper,
            EntityType<E> type, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(type, new BlockPos(x, 2, z));
    }

    private static MorphState state(GameTestHelper helper, ServerPlayer player) {
        return Morph.STATE.get(player);
    }

    private static MorphVariant ofType(String path) {
        return MorphVariant.ofType(BId.of("minecraft", path));
    }

    /** Seeds a COMMITTED morph with no transition lock (the wave-4 pattern). */
    private static void seed(ServerPlayer player, MorphVariant... variants) {
        Morph.clearTransitionLock(player);
        Morph.STATE.set(player, new MorphState(
                variants.length == 0 ? Optional.empty()
                        : Optional.of(variants[0]),
                List.of(variants)));
    }

    // ------------------------------------------------------------------
    // config builders (MorphConfig is a 16-component record; one wither
    // per option keeps every call site to a single readable line)
    // ------------------------------------------------------------------

    private static MorphConfig withChildMorphs(MorphConfig c, boolean value) {
        return new MorphConfig(value, c.playerMorphs(), c.bossMorphs(),
                c.blacklistedMobs(), c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), c.loseMorphsOnDeath(), c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), c.canSleepMorphed(),
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    private static MorphConfig withBlacklist(MorphConfig c, List<String> value) {
        return new MorphConfig(c.childMorphs(), c.playerMorphs(), c.bossMorphs(),
                value, c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), c.loseMorphsOnDeath(), c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), c.canSleepMorphed(),
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    private static MorphConfig withCanSleep(MorphConfig c, boolean value) {
        return new MorphConfig(c.childMorphs(), c.playerMorphs(), c.bossMorphs(),
                c.blacklistedMobs(), c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), c.loseMorphsOnDeath(), c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), value,
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    private static MorphConfig withLoseOnDeath(MorphConfig c, int value) {
        return new MorphConfig(c.childMorphs(), c.playerMorphs(), c.bossMorphs(),
                c.blacklistedMobs(), c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), value, c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), c.canSleepMorphed(),
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    // ==================================================================
    // morph/config/child-morphs
    // ==================================================================

    /**
     * {@code childMorphs} (default true since 2026-09-24; the original's was 0) refuses baby mobs when false
     * ({@code O:morph/common/core/EntityHelper.java:49} —
     * {@code Morph.childMorphs == 0 && living.isChild()}).
     *
     * <p>Until wave 10 this option had ZERO references outside
     * {@code MorphConfig}: babies were freely acquirable while the SPEC's wave-2
     * table claimed they were excluded (2026-07-29 source audit, §3). Both
     * directions are asserted, because a gate that refuses everything would pass
     * a one-sided test.</p>
     *
     * <p>Mutation-proof: deleting the {@code childMorphs} gate in
     * {@code Morph.acquireTarget} makes the {@code false} half acquire the baby
     * and fail.</p>
     */
    @GameTest(maxTicks = 60)
    public void childMorphsGateRefusesBabies(GameTestHelper helper) {
        ServerPlayer off = mockPlayer(helper);
        ServerPlayer on = mockPlayer(helper);
        Zombie babyA = spawn(helper, EntityTypes.ZOMBIE, 1, 1);
        babyA.setBaby(true);
        Zombie babyB = spawn(helper, EntityTypes.ZOMBIE, 1, 3);
        babyB.setBaby(true);
        Cow adult = spawn(helper, EntityTypes.COW, 3, 1);

        helper.assertTrue(babyA.isBaby(), "precondition: the victim is a baby");

        MorphConfig original = Morph.CONFIG.get();
        try {
            Morph.CONFIG.set(withChildMorphs(original, false));
            helper.assertFalse(
                    Morph.acquireTarget(off, babyA, false, true),
                    "childMorphs=false must refuse a BABY mob");
            helper.assertTrue(state(helper, off).acquired().isEmpty(),
                    "nothing may be added to the list on a refused baby kill");
            // the same player still acquires an ADULT — the gate is the baby
            // flag, not the acquisition path
            Morph.clearTransitionLock(off);
            helper.assertTrue(Morph.acquireTarget(off, adult, false, true),
                    "childMorphs=false must still allow an ADULT mob");

            Morph.CONFIG.set(withChildMorphs(original, true));
            helper.assertTrue(Morph.acquireTarget(on, babyB, false, true),
                    "childMorphs=true must ALLOW a baby mob");
            helper.assertTrue(state(helper, on).acquired().size() == 1,
                    "childMorphs=true must record the baby variant");
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    /** "/morph whitelist steve" used to never match the player "Steve". */
    @GameTest(maxTicks = 40)
    public void whitelistMatchesNamesIgnoringCase(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Cow cow = spawn(helper, EntityTypes.COW, 1, 1);
        MorphConfig original = Morph.CONFIG.get();
        try {
            Morph.CONFIG.set(original.withWhitelistedPlayers(java.util.List.of(
                    player.getScoreboardName().toUpperCase(java.util.Locale.ROOT))));
            helper.assertTrue(Morph.acquireTarget(player, cow, false, true),
                    "a whitelisted name must match its player ignoring case");
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    /** A hand-edited save with a malformed id ("pig") must fail that one
     *  entry, which the tolerant list decoder then skips - BId.of threw out of
     *  the codec and aborted the whole player's load. */
    @GameTest(maxTicks = 20)
    public void aMalformedSavedIdIsAnErrorNotACrash(GameTestHelper helper) {
        var result = com.deds.morph.MorphVariant.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE, net.minecraft.nbt.StringTag.valueOf("pig"));
        helper.assertTrue(result.isError(),
                "decoding the id \"pig\" must be a DataResult error, got " + result);
        var good = com.deds.morph.MorphVariant.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE,
                net.minecraft.nbt.StringTag.valueOf("minecraft:pig"));
        helper.assertTrue(good.isSuccess(), "\"minecraft:pig\" must still decode");
        helper.succeed();
    }

    // ==================================================================
    // morph/config/blacklisted-mobs
    // ==================================================================

    /**
     * {@code blacklistedMobs} refuses acquisition by entity-type id, and by
     * entity-type TAG for the inheritance half the original got from class
     * names ({@code O:morph/common/core/CommonProxy.java:24-43} resolves the CSV
     * of {@code Class}es; {@code O:EntityHelper.java:53-59} refuses any victim
     * with {@code clz.isInstance(living)}). Deviation D10-2.
     *
     * <p>Mutation-proof: deleting the {@code isBlacklisted} call in
     * {@code Morph.acquireTarget} acquires the cow and fails; returning
     * {@code true} unconditionally fails the pig case.</p>
     */
    @GameTest(maxTicks = 60)
    public void blacklistedMobsRefusesIdsAndTags(GameTestHelper helper) {
        ServerPlayer byId = mockPlayer(helper);
        ServerPlayer byTag = mockPlayer(helper);
        LivingEntity cow = spawn(helper, EntityTypes.COW, 1, 1);
        LivingEntity pig = spawn(helper, EntityTypes.PIG, 1, 3);
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 3, 1);
        LivingEntity creeper = spawn(helper, EntityTypes.CREEPER, 3, 3);

        MorphConfig original = Morph.CONFIG.get();
        try {
            Morph.CONFIG.set(withBlacklist(original, List.of("minecraft:cow")));
            helper.assertFalse(Morph.acquireTarget(byId, cow, false, true),
                    "a blacklisted entity id must refuse acquisition");
            helper.assertTrue(state(helper, byId).acquired().isEmpty(),
                    "a blacklisted kill must add nothing to the list");
            Morph.clearTransitionLock(byId);
            helper.assertTrue(Morph.acquireTarget(byId, pig, false, true),
                    "a mob that is NOT blacklisted must still be acquirable");

            // The tag half: #minecraft:undead contains zombie but not creeper,
            // which is what an inheritance-shaped blacklist has to be able to say.
            Morph.CONFIG.set(withBlacklist(original,
                    List.of("#minecraft:undead")));
            helper.assertFalse(Morph.acquireTarget(byTag, zombie, false, true),
                    "an entity-type TAG entry must refuse everything in the tag");
            Morph.clearTransitionLock(byTag);
            helper.assertTrue(Morph.acquireTarget(byTag, creeper, false, true),
                    "a mob OUTSIDE the blacklisted tag must stay acquirable "
                            + "(a creeper is not #minecraft:undead)");
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    // ==================================================================
    // morph/config/can-sleep-morphed
    // ==================================================================

    /**
     * {@code canSleepMorphed} refuses the bed while morphed, with the original's
     * exact message ({@code O:morph/common/core/EventHandler.java:651-668}) —
     * and DEFAULTS TO ALLOW, which the original does not (deviation D10-3: the
     * user asked for morphed sleeping and signed off the wave-8 lie-down, so
     * shipping the original's 0 default would have un-shipped it).
     *
     * <p>Drives the real entry point, {@code ServerPlayer.startSleepInBed},
     * so the mixin is what is under test — not the pure predicate. That means
     * vanilla's own gates must pass first (javap of
     * {@code ServerPlayer.startSleepInBed}: not already sleeping, the
     * {@code BED_RULE} environment attribute, {@code bedInRange},
     * {@code bedBlocked}, and a monster scan that {@code isCreative()} skips) —
     * hence the {@code night} environment and the player parked on the bed.
     * A daylight run would return {@code BedRule.asProblem()} instead and the
     * assertion names that difference.</p>
     *
     * <p>Mutation-proof: deleting {@code PlayerSleepInBedMixin}'s injection (or
     * inverting {@code MorphSleep.refuses}) makes the morphed, config-off case
     * return something other than our refusal and fails.</p>
     */
    @GameTest(maxTicks = 80, environment = "deds_morph_test:night")
    public void canSleepMorphedRefusesTheBedAndDefaultsToAllow(
            GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos foot = new BlockPos(3, 2, 3);
        BlockPos head = foot.east();
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));

        // RE-PIN the clock: the environment wrote it once, an unbounded
        // number of ticks ago (Playbook §8d).
        pinMidnight(helper);
        helper.assertTrue(helper.getLevel().isDarkOutside(),
                "precondition: this test must run in the pinned midnight "
                        + "environment (deds_morph_test:night) AND the inline "
                        + "pinMidnight() must still be there — every other "
                        + "outcome of startSleepInBed is a BedRule problem, "
                        + "not ours. skyDarken="
                        + helper.getLevel().getSkyDarken() + " dayTime="
                        + Math.floorMod(
                                helper.getLevel().getOverworldClockTime(),
                                24000L));

        ServerPlayer player = mockPlayer(helper);
        mockPlayer(helper); // awake guard: all-asleep would skip the night
        BlockPos absHead = helper.absolutePos(head);
        player.setPos(absHead.getX() + 0.5, absHead.getY() + 1.0,
                absHead.getZ() + 0.5);

        MorphConfig original = Morph.CONFIG.get();
        try {
            // 1. UNMORPHED + refusal ON: the option only ever touches a morph.
            Morph.CONFIG.set(withCanSleep(original, false));
            seed(player); // own form
            helper.assertFalse(MorphSleep.refuses(player),
                    "an UNMORPHED player must never be refused the bed");

            // 2. MORPHED + refusal ON: our exact problem, from the real path.
            seed(player, ofType("zombie"));
            helper.assertTrue(MorphSleep.refuses(player),
                    "canSleepMorphed=false + morphed must refuse");
            Either<Player.BedSleepingProblem, Unit> refused =
                    GameTestCompat.sleepInBed(player, helper.getLevel(), absHead);
            helper.assertFalse(player.isSleeping(),
                    "a refused bed must not put the player to sleep");
            helper.assertTrue(
                    refused.left().orElse(null) == MorphSleep.PROBLEM,
                    "canSleepMorphed=false must return Morph's OWN refusal "
                            + "(the original's \"You may not rest now, you are "
                            + "in morph\"), got " + describe(refused));

            // 3. MORPHED + the SHIPPED default: the bed works. Asserted on the
            //    default value itself, so a silent flip back to the original's
            //    0 is caught here and not only by reading the record.
            helper.assertTrue(MorphConfig.defaults().canSleepMorphed(),
                    "D10-3: our shipped canSleepMorphed default must be TRUE "
                            + "(allow) — the user's signed-off sleeping feature "
                            + "depends on it");
            Morph.CONFIG.set(withCanSleep(original, true));
            helper.assertFalse(MorphSleep.refuses(player),
                    "canSleepMorphed=true must not refuse a morphed player");
            Either<Player.BedSleepingProblem, Unit> allowed =
                    GameTestCompat.sleepInBed(player, helper.getLevel(), absHead);
            helper.assertTrue(
                    allowed.left().orElse(null) != MorphSleep.PROBLEM,
                    "canSleepMorphed=true must not produce Morph's refusal, "
                            + "got " + describe(allowed));
            if (player.isSleeping()) {
                player.stopSleeping();
            }
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    private static String describe(
            Either<Player.BedSleepingProblem, Unit> result) {
        return result.map(
                problem -> "Left(" + (problem.message() == null ? "no message"
                        : problem.message().getString()) + ")",
                unit -> "Right(slept)");
    }

    // ==================================================================
    // morph/config/lose-morphs-on-death
    // ==================================================================

    /**
     * {@code loseMorphsOnDeath}: 0 keeps everything (the 0.7.1 default), 1 wipes
     * the WHOLE collection, 2 removes only the morph being worn
     * ({@code O:morph/common/core/EventHandler.java:694-725} —
     * {@code playerMorphs.remove(username)} for mode 1,
     * {@code states.remove(info.nextState)} otherwise). Both non-zero modes also
     * put the player back in their own form ({@code :713-717}).
     *
     * <p>Drives the real {@code ServerPlayer.die}, so
     * {@code ServerPlayerDieMixin} is what is under test.</p>
     *
     * <p>Mutation-proof: deleting the mixin injection leaves all three modes
     * keeping everything and fails modes 1 and 2; swapping the two branches
     * fails mode 2's "the other morph survives" assertion.</p>
     */
    @GameTest(maxTicks = 60)
    public void loseMorphsOnDeathModes(GameTestHelper helper) {
        MorphVariant zombie = ofType("zombie");
        MorphVariant cow = ofType("cow");

        MorphConfig original = Morph.CONFIG.get();
        try {
            // mode 0 — the default: death changes nothing
            ServerPlayer keep = mockPlayer(helper);
            Morph.CONFIG.set(withLoseOnDeath(original, 0));
            seed(keep, zombie, cow);
            keep.die(keep.damageSources().genericKill());
            helper.assertTrue(state(helper, keep).acquired().size() == 2
                            && state(helper, keep).current()
                                    .equals(Optional.of(zombie)),
                    "loseMorphsOnDeath=0 must keep every morph AND the worn "
                            + "form, has " + state(helper, keep).acquired().size()
                            + " worn=" + state(helper, keep).current());

            // mode 1 — lose ALL
            ServerPlayer all = mockPlayer(helper);
            Morph.CONFIG.set(withLoseOnDeath(original, 1));
            seed(all, zombie, cow);
            all.die(all.damageSources().genericKill());
            helper.assertTrue(state(helper, all).acquired().isEmpty(),
                    "loseMorphsOnDeath=1 must wipe the WHOLE collection, "
                            + state(helper, all).acquired().size() + " left");
            helper.assertTrue(state(helper, all).current().isEmpty(),
                    "loseMorphsOnDeath=1 must also demorph");

            // mode 2 — lose only the worn one
            ServerPlayer worn = mockPlayer(helper);
            Morph.CONFIG.set(withLoseOnDeath(original, 2));
            seed(worn, zombie, cow); // seed() wears the FIRST variant
            worn.die(worn.damageSources().genericKill());
            helper.assertTrue(state(helper, worn).acquired().equals(List.of(cow)),
                    "loseMorphsOnDeath=2 must remove ONLY the worn morph "
                            + "(zombie) and keep the rest, list is "
                            + state(helper, worn).acquired());
            helper.assertTrue(state(helper, worn).current().isEmpty(),
                    "loseMorphsOnDeath=2 must demorph");

            // mode 2 on an UNMORPHED player takes nothing (":707" — the
            // original's `info.nextState != state` guard)
            ServerPlayer own = mockPlayer(helper);
            seed(own);
            Morph.STATE.set(own,
                    new MorphState(Optional.empty(), List.of(zombie, cow)));
            own.die(own.damageSources().genericKill());
            helper.assertTrue(state(helper, own).acquired().size() == 2,
                    "loseMorphsOnDeath=2 must take nothing from a player who "
                            + "was in their OWN form");
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    // ==================================================================
    // morph/ability/fly-hunger
    // ==================================================================

    /**
     * Flight HUNGER DRAIN, verbatim from
     * {@code O:morph/common/ability/AbilityFly.java:58-78}: moving costs
     * {@code 0.035 · round(sqrt(dx²+dz²)·100) · 0.01} per tick, in water (when
     * this morph is slowed by water) {@code 0.125 · …}, and hovering a flat
     * {@code 0.002}. The {@code ability-fly} SPEC row claimed this from wave 2
     * and nothing implemented it (2026-07-29 source audit, §3).
     *
     * <p>{@code FoodData.exhaustionLevel} is private with no getter, so it is
     * read the one public way: {@code addAdditionalSaveData} writes it as
     * {@code foodExhaustionLevel} (javap).</p>
     *
     * <p>Mutation-proof: deleting the {@code flightExhaustion} call leaves the
     * exhaustion at 0 and fails; using {@code 0.035} for the idle case fails the
     * hover assertion.</p>
     */
    @GameTest(maxTicks = 60)
    public void flightDrainsHungerLikeTheOriginal(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        seed(player, ofType("bat")); // a FLY morph that is NOT fire immune
        // Mock players are stamped creative (Playbook §8) and the original
        // skips a creative flier entirely (":58"), so pin survival flight.
        player.getAbilities().instabuild = false;
        // ...and `invulnerable`, which mocks are ALSO stamped with: javap of
        // Player.causeFoodExhaustion shows an `abilities.invulnerable` early
        // return ahead of everything, so leaving it on makes every exhaustion
        // assertion here vacuously 0.0 (observed).
        player.getAbilities().invulnerable = false;
        // One tick COMMITS the seeded morph's ability set (nothing is applied
        // until MorphAbilities.commit runs), and it is also the tick that turns
        // mayfly on for a FLY morph.
        MorphAbilities.tick(player);
        player.getAbilities().flying = true;

        helper.assertTrue(
                MorphAbilities.activeAbilities(player)
                        .contains(MorphAbility.FLY),
                "precondition: a bat morph must carry FLY");

        // HOVER: dx = dz = 0 -> the flat 0.002 idle cost (":77")
        float before = exhaustion(player);
        player.setPos(player.getX(), player.getY(), player.getZ());
        player.xOld = player.getX();
        player.zOld = player.getZ();
        MorphAbilities.tick(player);
        float hover = exhaustion(player) - before;
        helper.assertTrue(Math.abs(hover - 0.002f) < 1.0e-5f,
                "a hovering flight tick must cost exactly 0.002 exhaustion, "
                        + "cost " + hover);

        // MOVING 1 block in air: i = round(1.0 * 100) = 100 -> 0.035*100*0.01
        before = exhaustion(player);
        player.xOld = player.getX() - 1.0;
        player.zOld = player.getZ();
        MorphAbilities.tick(player);
        float moving = exhaustion(player) - before;
        helper.assertTrue(Math.abs(moving - 0.035f) < 1.0e-5f,
                "flying one block in a tick must cost 0.035*100*0.01 = 0.035 "
                        + "exhaustion, cost " + moving);

        // MOVING 1 block IN WATER with a water-slowed morph: 0.125*100*0.01
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.WATER);
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.WATER);
        BlockPos pool = helper.absolutePos(new BlockPos(1, 2, 1));
        player.setPos(pool.getX() + 0.5, pool.getY() + 0.1, pool.getZ() + 0.5);
        updateFluid(player);
        helper.assertTrue(player.isInWater(),
                "precondition: the player must be in water for the water rate");
        before = exhaustion(player);
        player.xOld = player.getX() - 1.0;
        player.zOld = player.getZ();
        MorphAbilities.tick(player);
        float wet = exhaustion(player) - before;
        helper.assertTrue(Math.abs(wet - 0.125f) < 1.0e-5f,
                "flying one block in water must cost 0.125*100*0.01 = 0.125 "
                        + "exhaustion (the slowdownInWater rate), cost " + wet);
        helper.succeed();
    }

    /** {@code FoodData.exhaustionLevel} through its only public reader. */
    private static float exhaustion(ServerPlayer player) {
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, player.level().registryAccess());
        player.getFoodData().addAdditionalSaveData(out);
        CompoundTag tag = out.buildResult();
        return tag.getFloatOr("foodExhaustionLevel", 0.0f);
    }

    /** {@code Entity.updateFluidInteraction()} is protected and a mock player
     *  never runs {@code baseTick}, so "in water" needs the explicit poke
     *  (Playbook §8). */
    private static void updateFluid(ServerPlayer player) {
        try {
            Method m = net.minecraft.world.entity.Entity.class
                    .getDeclaredMethod("updateFluidInteraction");
            m.setAccessible(true);
            m.invoke(player);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not refresh the fluid state", e);
        }
    }

    // ==================================================================
    // morph/ability/fly-water
    // ==================================================================

    /**
     * The {@code slowdownInWater} DERIVATION (deviation D10-6). iChun's table
     * maps exactly five flyers and splits them two ways
     * ({@code O:morph/common/ability/AbilityHandler.java:56-78}): bat
     * {@code fly(true)}; blaze, ghast, ender dragon and wither {@code fly(false)}.
     * Those five are separated exactly by {@code EntityType.fireImmune()}, which
     * we already derive as {@code FIRE_IMMUNITY} — so the parameter costs no new
     * state. This test pins all five of the original's data points.
     *
     * <p>The MOTION itself (x/z ×0.65, y ×0.2) is client-authoritative and lives
     * in {@code MorphAbilitiesClient} (Playbook §1) — a headless server cannot
     * observe it, so this pins the decision the motion is gated on.</p>
     *
     * <p>Mutation-proof: inverting the {@code !} in
     * {@code MorphAbilities.flySlowdownInWater} fails on the very first mob.</p>
     */
    @GameTest(maxTicks = 20)
    public void flySlowdownInWaterMatchesIChunsFiveFlyers(GameTestHelper helper) {
        assertSlowdown(helper, EntityTypes.BAT, true);          // fly(true)
        assertSlowdown(helper, EntityTypes.BLAZE, false);       // fly(false)
        assertSlowdown(helper, EntityTypes.GHAST, false);       // fly(false)
        assertSlowdown(helper, EntityTypes.WITHER, false);      // fly(false)
        assertSlowdown(helper, EntityTypes.ENDER_DRAGON, false); // fly(false)
        helper.succeed();
    }

    private static void assertSlowdown(GameTestHelper helper,
            EntityType<? extends LivingEntity> type, boolean expected) {
        LivingEntity dummy = type.create(helper.getLevel(),
                net.minecraft.world.entity.EntitySpawnReason.LOAD);
        helper.assertTrue(dummy != null, "could not build " + type);
        EnumSet<MorphAbility> abilities = MorphAbility.deriveAbilities(dummy);
        helper.assertTrue(abilities.contains(MorphAbility.FLY),
                EntityType.getKey(type) + " must derive FLY (it is one of "
                        + "iChun's five AbilityFly mobs)");
        helper.assertTrue(
                MorphAbilities.flySlowdownInWater(abilities) == expected,
                EntityType.getKey(type) + " must derive slowdownInWater="
                        + expected + " (iChun's AbilityHandler:56-78), got "
                        + MorphAbilities.flySlowdownInWater(abilities));
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
