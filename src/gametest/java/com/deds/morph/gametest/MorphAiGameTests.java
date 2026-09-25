package com.deds.morph.gametest;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;
import com.deds.morph.fabric.mixin.AvoidEntityGoalAccessor;
import com.deds.morph.fabric.mixin.MobGoalsAccessor;

import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server gametests for Morph wave-3 behavior wave 3, "AI relationships"
 * (feature ids {@code morph/ai/*}), mod id {@code deds_morph}. Source of truth:
 * {@code docs/specs/morph/wave3/behavior-ai-relationships-audit.md} §A.6.
 *
 * <p>A morphed player is seeded directly ({@code Morph.STATE.set} with no
 * transition), so the next {@code PLAYER_TICK_END} commits it — no ~80-tick morph
 * needed. A hunter/avoider mob is then spawned WITH AI adjacent, the level ticked,
 * and the mob's {@code getTarget()} / flee distance asserted.</p>
 */
public final class MorphAiGameTests implements CustomTestMethodInvoker {

    /**
     * A mock server player in SURVIVAL. {@code GameTestHelper.makeMockServerPlayerInLevel}
     * returns a player whose {@code gameMode()} is hardcoded to CREATIVE, and
     * {@code Mob.asValidTarget()} rejects every creative player (so {@code getTarget()}
     * stays null even after a goal sets it) — creative players are never hunted, by
     * design. To exercise the hunt/avoid bridges we build the same mock but override
     * {@code gameMode()} to SURVIVAL (mirrors the vanilla construction: real
     * {@link Connection} on an {@link EmbeddedChannel}, placed via the player list).
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-morph-player");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                ClientInformation.createDefault()) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL; // targetable/avoidable, unlike the CREATIVE mock
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(new ChannelHandler[] {connection});
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.runBeforeTestEnd(() -> server.getPlayerList().remove(player));
        return player;
    }

    /** Builds a stone floor (y=1) over the relative x/z rectangle. */
    private static void floor(GameTestHelper helper, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    /** Mobs cannot target PLAYERS in PEACEFUL (vanilla {@code canAttack}); the
     *  gametest world is peaceful, so force a hostile difficulty for hunt tests. */
    private static void hostileWorld(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
    }

    /** Seeds a committed morph without a transition (fast path). */
    private static void seedMorph(ServerPlayer victim, String mobPath) {
        MorphVariant variant = MorphVariant.ofType(BId.of("minecraft", mobPath));
        Morph.STATE.set(victim, new MorphState(Optional.of(variant), List.of(variant)));
    }

    private static void placeAt(ServerPlayer player, GameTestHelper helper,
            double x, double y, double z) {
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        // The mock is SURVIVAL (see mockPlayer) but spawns with 0 health, so it is
        // not alive yet; give it health + drop invulnerability so a mob's
        // canAttack(morph) / asValidTarget(morph) both pass.
        player.setHealth(20.0f);
        player.getAbilities().invulnerable = false;
    }

    // ==================================================================
    // morph/ai/wolf-hunts-sheep
    // ==================================================================

    @GameTest(maxTicks = 140)
    public void wolfHuntsSheep(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        Mob[] wolf = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    hostileWorld(helper);
                    placeAt(victim, helper, 2.5, 2.0, 2.5);
                    seedMorph(victim, "sheep");
                })
                .thenExecuteAfter(5, () -> {
                    helper.assertTrue(
                            MorphAbilities.committedVariant(victim).isPresent(),
                            "the victim must be a committed sheep-morph");
                    wolf[0] = helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 2));
                })
                // Wait for the hunt rather than sampling one tick: a wolf's target
                // legitimately comes and goes mid-fight (knockback, sight), and the
                // one-tick check at tick 87 failed now and then.
                // wolfKeepsHuntingSheepMorph covers holding the target.
                .thenWaitUntil(() -> helper.assertTrue(
                        wolf[0].getTarget() == victim,
                        "a wolf must hunt a sheep-morph (Animal + prey selector) but "
                                + "target is " + wolf[0].getTarget()))
                .thenSucceed();
    }

    /**
     * ...and KEEPS hunting it: once the wolf has the sheep-morph, it must hold
     * it for a full second. (Its prey goal re-tests the real player every tick;
     * NonTameRandomTargetGoalMixin keeps that goal on the target. Without the
     * hook the wolf's anger goal takes the player over instead, so this test
     * guards the outcome, not the hook.)
     */
    @GameTest(maxTicks = 200)
    public void wolfKeepsHuntingSheepMorph(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        hostileWorld(helper);
        placeAt(victim, helper, 2.5, 2.0, 2.5);
        seedMorph(victim, "sheep");
        Mob wolf = helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 2));
        int[] held = new int[1];
        helper.succeedWhen(() -> {
            held[0] = wolf.getTarget() == victim ? held[0] + 1 : 0;
            helper.assertTrue(held[0] >= 20,
                    "a wolf must hold a sheep-morph target for 20 ticks in a row; "
                            + "held " + held[0] + ", target " + wolf.getTarget());
        });
    }

    // ==================================================================
    // morph/ai/zombie-hunts-villager
    // ==================================================================

    @GameTest(maxTicks = 140)
    public void zombieHuntsVillager(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        Mob[] zombie = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    hostileWorld(helper);
                    placeAt(victim, helper, 2.5, 2.0, 2.5);
                    seedMorph(victim, "villager");
                })
                .thenExecuteAfter(5, () ->
                        zombie[0] = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 2, 2)))
                .thenExecuteAfter(80, () -> helper.assertTrue(
                        zombie[0].getTarget() == victim,
                        "a zombie must hunt a villager-morph (AbstractVillager) but "
                                + "target is " + zombie[0].getTarget()))
                .thenSucceed();
    }

    // ==================================================================
    // morph/ai/golem-hunts-zombie (broad Mob target + hostile selector on dummy)
    // ==================================================================

    @GameTest(maxTicks = 160)
    public void golemHuntsZombie(GameTestHelper helper) {
        floor(helper, 1, 8, 1, 4);
        ServerPlayer victim = mockPlayer(helper);
        Mob[] golem = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    hostileWorld(helper);
                    placeAt(victim, helper, 2.5, 2.0, 2.5);
                    seedMorph(victim, "zombie");
                })
                .thenExecuteAfter(5, () ->
                        golem[0] = helper.spawn(EntityTypes.IRON_GOLEM, new BlockPos(6, 2, 2)))
                // Assert early: an iron golem kills a 20-hp morph within ~4 hits, so
                // by tick 90 getTarget() would be null again (the player is dead).
                .thenExecuteAfter(30, () -> helper.assertTrue(
                        golem[0].getTarget() == victim,
                        "an iron golem must hunt a zombie-morph (Mob + hostile selector "
                                + "on a zombie dummy) but target is " + golem[0].getTarget()))
                .thenSucceed();
    }

    // ==================================================================
    // morph/ai/golem-ignores-cow (broad Mob target, selector fails on cow dummy)
    // ==================================================================

    @GameTest(maxTicks = 160)
    public void golemIgnoresCow(GameTestHelper helper) {
        floor(helper, 1, 8, 1, 4);
        ServerPlayer victim = mockPlayer(helper);
        Mob[] golem = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    hostileWorld(helper);
                    placeAt(victim, helper, 2.5, 2.0, 2.5);
                    seedMorph(victim, "cow");
                })
                .thenExecuteAfter(5, () ->
                        golem[0] = helper.spawn(EntityTypes.IRON_GOLEM, new BlockPos(6, 2, 2)))
                .thenExecuteAfter(90, () -> helper.assertTrue(
                        golem[0].getTarget() != victim,
                        "an iron golem must NOT hunt a cow-morph (hostile selector fails "
                                + "on a cow dummy) but target is " + golem[0].getTarget()))
                .thenSucceed();
    }

    // ==================================================================
    // morph/ai/hostile-morph-still-ignored (composition with hostile-ignore)
    // ==================================================================

    @GameTest(maxTicks = 140)
    public void hostileMorphStillIgnored(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        Mob[] zombie = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    hostileWorld(helper);
                    placeAt(victim, helper, 2.5, 2.0, 2.5);
                    seedMorph(victim, "zombie");
                })
                .thenExecuteAfter(5, () ->
                        zombie[0] = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 2, 2)))
                .thenExecuteAfter(80, () -> helper.assertTrue(
                        zombie[0].getTarget() != victim,
                        "a hostile mob must still IGNORE a hostile (zombie) morph via the "
                                + "hostile-ability, even with the AI bridge active, but "
                                + "target is " + zombie[0].getTarget()))
                .thenSucceed();
    }

    // ==================================================================
    // morph/ai/neutral-not-overridden
    // ==================================================================

    @GameTest(maxTicks = 120)
    public void neutralNotOverridden(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 4);
        ServerPlayer sheepMorph = mockPlayer(helper);
        Mob[] wolf = new Mob[1];
        Mob[] pig = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    hostileWorld(helper);
                    placeAt(sheepMorph, helper, 2.5, 2.0, 2.5);
                    seedMorph(sheepMorph, "sheep");
                    pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 2, 4));
                    wolf[0] = helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 2));
                    // Provoke the wolf onto a NON-morph target BEFORE the morph
                    // commits, so the bridge sees a non-null target and must not steal it.
                    wolf[0].setTarget(pig[0]);
                })
                .thenExecuteAfter(40, () -> helper.assertTrue(
                        wolf[0].getTarget() == pig[0],
                        "the AI bridge must NOT steal an already-set target (only adds "
                                + "when the mob has none) — target is " + wolf[0].getTarget()))
                .thenSucceed();
    }

    // ==================================================================
    // morph/ai/creeper-flees-cat (AVOID)
    // ==================================================================

    @GameTest(maxTicks = 160)
    public void creeperFleesCat(GameTestHelper helper) {
        floor(helper, 1, 14, 1, 3);
        // PEACEFUL (default): the creeper cannot target the cat-morph as a player
        // (canAttack false), so only the AVOID goal engages.
        ServerPlayer catMorph = mockPlayer(helper);
        placeAt(catMorph, helper, 2.5, 2.0, 2.5);
        seedMorph(catMorph, "cat");
        // ~5 blocks: inside the cat-avoid range (6).
        Mob creeper = helper.spawn(EntityTypes.CREEPER, new BlockPos(7, 2, 2));
        // Assert the AVOID bridge deterministically: once the morph commits, the
        // creeper's cat-avoid goal must pick the cat-morphed player as its "toAvoid"
        // entity. AvoidEntityGoal.canUse() sets toAvoid from the (bridge-augmented)
        // scan BEFORE the flee-path check, so this is independent of the random flee
        // pathfinding (the emergent flee itself is unchanged vanilla behavior).
        helper.succeedWhen(() -> helper.assertTrue(
                avoidTargetOf(creeper) == catMorph,
                "a creeper's cat-avoid goal must flee a cat-morph (bridge §A.3) "
                        + "— toAvoid was " + avoidTargetOf(creeper)));
    }

    // ==================================================================
    // Crash guards: vanilla code that meets a morphed PLAYER where it
    // expects the mob the player looks like.
    // ==================================================================

    /**
     * A villager near a zombie-morph. The villager-fear bridge makes
     * {@code VillagerHostilesSensor.isHostile(player)} true; vanilla then asks
     * {@code isClose}, which looks the distance up by the entity's OWN type -
     * {@code minecraft:player}, not in the table - and unboxed a null: a
     * NullPointerException in the villager's tick, i.e. a server crash, for any
     * zombie-shaped player within 16 blocks of a villager. The villager must
     * instead record the player as its nearest hostile.
     */
    @GameTest(maxTicks = 120)
    public void villagerFearsZombieMorphWithoutCrashing(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer zombieMorph = mockPlayer(helper);
        placeAt(zombieMorph, helper, 2.5, 2.0, 2.5);
        seedMorph(zombieMorph, "zombie");
        Mob villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(5, 2, 2));
        helper.succeedWhen(() -> helper.assertTrue(
                villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE)
                        .orElse(null) == zombieMorph,
                "a villager must record a zombie-morph as its nearest hostile - was "
                        + villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE)));
    }

    /**
     * A fox near a wolf-morph. The fox's wolf-avoid goal tests
     * {@code ((Wolf) entity).isTame()} on every candidate, and the AVOID bridge
     * used to hand it the player: a ClassCastException, i.e. a server crash. The
     * goal must now run without throwing.
     */
    @GameTest(maxTicks = 60)
    public void foxNearWolfMorphDoesNotCrash(GameTestHelper helper) {
        avoidGoalsSurvive(helper, "wolf", EntityTypes.FOX, false);
    }

    /** A spider near an armadillo-morph: the spider's armadillo-avoid goal
     *  tests {@code ((Armadillo) entity).isScared()} - same failure as the fox. */
    @GameTest(maxTicks = 60)
    public void spiderNearArmadilloMorphDoesNotCrash(GameTestHelper helper) {
        avoidGoalsSurvive(helper, "armadillo", EntityTypes.SPIDER, true);
    }

    /** Seeds {@code morphPath} on a player, puts {@code mobType} 3 blocks away,
     *  and once the morph has committed runs every one of the mob's avoid goals'
     *  {@code canUse()} directly - deterministic, where the goal selector would
     *  only reach them on its own schedule - then lets the mob tick a while. */
    private static void avoidGoalsSurvive(GameTestHelper helper, String morphPath,
            net.minecraft.world.entity.EntityType<? extends Mob> mobType,
            boolean hostileMob) {
        floor(helper, 1, 6, 1, 3);
        if (hostileMob) {
            hostileWorld(helper); // a monster is removed at once in PEACEFUL
        }
        ServerPlayer morphed = mockPlayer(helper);
        placeAt(morphed, helper, 2.5, 2.0, 2.5);
        seedMorph(morphed, morphPath);
        Mob[] mob = new Mob[1];
        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(MorphAbilities.committedVariant(morphed).isPresent(),
                            "the " + morphPath + "-morph must have committed");
                    mob[0] = helper.spawn(mobType, new BlockPos(5, 2, 2));
                })
                .thenExecuteAfter(2, () -> {
                    for (WrappedGoal wrapped : ((MobGoalsAccessor) (Object) mob[0])
                            .deds_morph$goalSelector().getAvailableGoals()) {
                        if (wrapped.getGoal() instanceof AvoidEntityGoal<?> avoid) {
                            avoid.canUse();
                        }
                    }
                })
                .thenExecuteAfter(40, () -> helper.assertTrue(mob[0].isAlive(),
                        "the " + mobType + " must still be ticking next to a "
                                + morphPath + "-morph"))
                .thenSucceed();
    }

    // ==================================================================
    // Someone's mob: pets and player-built golems obey PvP
    // ==================================================================

    /** Fails the test on any tick {@code mob} targets {@code victim}; succeeds
     *  after {@code ticks} ticks otherwise. */
    private static void neverTargets(GameTestHelper helper, Mob mob,
            ServerPlayer victim, int ticks, String what) {
        helper.onEachTick(() -> {
            if (mob.getTarget() == victim) {
                helper.fail(what);
            }
        });
        helper.runAfterDelay(ticks, helper::succeed);
    }

    /** A player-built iron golem is someone's: with PvP off it must leave a
     *  zombie-shaped player alone (a village golem still hunts one, see
     *  {@link #golemHuntsZombie}). */
    @GameTest(maxTicks = 120, environment = "deds_morph_test:no_pvp")
    public void builtGolemSparesZombieMorphWithPvpOff(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        hostileWorld(helper);
        placeAt(victim, helper, 2.5, 2.0, 2.5);
        seedMorph(victim, "zombie");
        helper.assertFalse(helper.getLevel().isPvpAllowed(), "precondition: the no_pvp environment is active");
        net.minecraft.world.entity.animal.golem.IronGolem golem =
                helper.spawn(EntityTypes.IRON_GOLEM, new BlockPos(5, 2, 2));
        golem.setPlayerCreated(true);
        neverTargets(helper, golem, victim, 90,
                "a player-built golem must not hunt a zombie-morph with PvP off");
    }

    /** A pet wolf with PvP off must not hunt another player shaped as a
     *  skeleton (wolves hunt skeletons). */
    @GameTest(maxTicks = 120, environment = "deds_morph_test:no_pvp")
    public void petWolfSparesSkeletonMorphWithPvpOff(GameTestHelper helper) {
        floor(helper, 1, 6, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer owner = mockPlayer(helper);
        hostileWorld(helper);
        placeAt(victim, helper, 2.5, 2.0, 2.5);
        placeAt(owner, helper, 5.5, 2.0, 3.5);
        seedMorph(victim, "skeleton");
        helper.assertFalse(helper.getLevel().isPvpAllowed(), "precondition: the no_pvp environment is active");
        net.minecraft.world.entity.animal.wolf.Wolf wolf =
                helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 2));
        wolf.tame(owner);
        neverTargets(helper, wolf, victim, 90,
                "a pet wolf must not hunt a skeleton-morph with PvP off");
    }

    /** An invisible zombie-shaped player 4.5 blocks from a golem: vanilla would
     *  not spot an invisible player that far off, so the hunt bridge must not
     *  either (it used to skip vanilla's visibility and team checks). */
    @GameTest(maxTicks = 120)
    public void golemDoesNotSpotAnInvisibleZombieMorph(GameTestHelper helper) {
        floor(helper, 1, 7, 1, 3);
        ServerPlayer victim = mockPlayer(helper);
        hostileWorld(helper);
        placeAt(victim, helper, 1.5, 2.0, 2.5);
        seedMorph(victim, "zombie");
        victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.INVISIBILITY, 20 * 60, 0, false, false));
        Mob golem = helper.spawn(EntityTypes.IRON_GOLEM, new BlockPos(6, 2, 2));
        neverTargets(helper, golem, victim, 90,
                "a golem must not hunt an invisible zombie-morph from 4.5 blocks");
    }

    /** The entity a mob's {@link AvoidEntityGoal} is currently set to flee, or null.
     *  Reads {@code AvoidEntityGoal.toAvoid} across the mob's goals (a creeper has an
     *  ocelot-avoid and a cat-avoid; the ocelot one stays null with no ocelot near). */
    private static LivingEntity avoidTargetOf(Mob mob) {
        if (mob == null) {
            return null;
        }
        for (WrappedGoal wrapped : ((MobGoalsAccessor) (Object) mob)
                .deds_morph$goalSelector().getAvailableGoals()) {
            if (wrapped.getGoal() instanceof AvoidEntityGoal<?> avoid) {
                LivingEntity toAvoid =
                        ((AvoidEntityGoalAccessor) avoid).deds_morph$toAvoid();
                if (toAvoid != null) {
                    return toAvoid;
                }
            }
        }
        return null;
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
