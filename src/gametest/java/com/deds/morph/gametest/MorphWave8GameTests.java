package com.deds.morph.gametest;

import com.deds.morph.Morph;
import com.deds.morph.MorphAbility;
import com.deds.morph.MorphHumanoid;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Morph wave 8 — the 2026-07-29 playtest round-4 fixes
 * ({@code docs/specs/morph/wave8/playtest-round-4.md}). Three user-reported
 * bugs, each with a whole-matrix audit test alongside the specific case:
 *
 * <ol>
 * <li><b>{@code morph/ability/climb-wall-climbers-only}</b> — the endermite
 *     (and silverfish, and bee) had a spider wall-climb because the CLIMB
 *     derivation was OR-ed with {@code EntityTypeTags.ARTHROPOD}. Asserted both
 *     for the three named mobs and as a REGISTRY SWEEP: the set of types that
 *     derive CLIMB is exactly {spider, cave_spider}.</li>
 * <li><b>{@code morph/variant/volatile-nbt-stripped}</b> — every killed
 *     endermite minted a new morph because {@code Endermite.Lifetime} (a per-tick
 *     counter) was part of the variant identity. Asserted for the endermite
 *     specifically, as a registry sweep over every known volatile key, and in
 *     BOTH directions (appearance NBT must still discriminate, name tags
 *     included).</li>
 * <li><b>{@code morph/mech/sleep-humanoid}</b> — a morphed player standing on
 *     the pillow instead of lying down. The RENDER half is client-only (see
 *     {@code MorphRenderClientTest}); the two headless halves are the shared
 *     humanoid policy ({@link MorphHumanoid}) and the in-bed hitbox.</li>
 * </ol>
 */
public final class MorphWave8GameTests implements CustomTestMethodInvoker {

    private static final float EPS = 1.0e-4f;

    /** Spawns a free-will-less mob on a fresh stone pedestal at (x,2,z). */
    private static <E extends Mob> E spawn(GameTestHelper helper,
            EntityType<E> type, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(type, new BlockPos(x, 2, z));
    }

    /** Registered mock server player, de-registered before the test ends. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runBeforeTestEnd(() ->
                helper.getLevel().getServer().getPlayerList().remove(player));
        return player;
    }

    /** Every registered entity type that builds into a {@link Mob} — the morph
     *  candidate set ({@code acquireTarget} takes only {@code Player}/{@code Mob}). */
    private static List<Mob> everyMob(GameTestHelper helper) {
        List<Mob> mobs = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Entity entity;
            try {
                entity = type.create(helper.getLevel(), EntitySpawnReason.LOAD);
            } catch (Exception broken) {
                continue;
            }
            if (entity instanceof Mob mob) {
                mobs.add(mob);
            }
        }
        return mobs;
    }

    private static String idOf(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    /** Day time {@code deds_morph_test:night} writes. */
    private static final int MIDNIGHT_TICKS = 18000;

    /**
     * Re-pins the overworld clock to midnight, IN THE TEST BODY.
     *
     * <p>A {@code minecraft:clock_time} test environment does not hold the
     * time: {@code TestEnvironmentDefinition$ClockTime.setup} is five
     * instructions — save the old total ticks, {@code setTotalTicks}, return
     * the old value for {@code teardown} (javap) — and the clock then FREE-RUNS
     * with the rest of the run. Nothing bounds the gap between that one write
     * and the tick an assertion lands on: batch order is nondeterministic and
     * every other batch advances the same clock. Measured drift on a sibling
     * module across six cold runs of one command: 14 to ~3,560 ticks
     * (Playbook §8d). 18000 + ~5,500 is dawn, and dawn is exactly what
     * {@code BED_RULE.canSleep} refuses — i.e. the same failure the wave-8
     * cold-world fix addressed, one layer further out.
     *
     * <p>{@code updateSkyBrightness()} follows because {@code ClockTime.setup}
     * does not call it: {@code Level.skyDarken} is
     * {@code 15 − EnvironmentAttributes.SKY_LIGHT_LEVEL} (javap,
     * {@code Level.updateSkyBrightness}) and is otherwise refreshed only once
     * per server tick, so a same-tick {@code isDarkOutside()} would still read
     * the pre-pin value.
     *
     * <p>Keep the {@code environment =} annotation: it buys the batch and its
     * teardown restores the clock, so this inline write does not leak.
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

    // ==================================================================
    // 1. morph/ability/climb-wall-climbers-only  (playtest bug 1)
    // ==================================================================

    /**
     * {@code morph/ability/climb-wall-climbers-only}. The reported case and its
     * two siblings: endermite, silverfish and bee are all in
     * {@code #minecraft:arthropod} and none of them can climb a wall in vanilla
     * 26.2, so none may derive {@link MorphAbility#CLIMB}. Endermite and
     * silverfish must derive EXACTLY {HOSTILE} — which is also, to the letter,
     * what iChun mapped for the silverfish
     * ({@code morph/common/ability/AbilityHandler.java:71}).
     *
     * <p>Mutation-proof: restoring {@code || holder.is(EntityTypeTags.ARTHROPOD)}
     * in {@code MorphAbility.deriveAbilities} fails this on all three mobs.</p>
     */
    @GameTest(maxTicks = 80)
    public void arthropodsThatCannotClimbDoNotGetClimb(GameTestHelper helper) {
        LivingEntity endermite = spawn(helper, EntityTypes.ENDERMITE, 1, 1);
        LivingEntity silverfish = spawn(helper, EntityTypes.SILVERFISH, 3, 1);
        LivingEntity bee = spawn(helper, EntityTypes.BEE, 5, 1);

        helper.startSequence().thenExecuteAfter(2, () -> {
            EnumSet<MorphAbility> mite = MorphAbility.deriveAbilities(endermite);
            EnumSet<MorphAbility> fish = MorphAbility.deriveAbilities(silverfish);
            EnumSet<MorphAbility> buzz = MorphAbility.deriveAbilities(bee);
            helper.assertFalse(mite.contains(MorphAbility.CLIMB),
                    "an endermite cannot climb walls in vanilla — it must NOT "
                            + "derive CLIMB, but derived " + mite);
            helper.assertFalse(fish.contains(MorphAbility.CLIMB),
                    "a silverfish cannot climb walls (it infests blocks, which "
                            + "is not climbing) — derived " + fish);
            helper.assertFalse(buzz.contains(MorphAbility.CLIMB),
                    "a bee flies, it does not wall-climb — derived " + buzz);
            helper.assertTrue(mite.equals(EnumSet.of(MorphAbility.HOSTILE)),
                    "endermite must derive exactly {HOSTILE}, derived " + mite);
            helper.assertTrue(fish.equals(EnumSet.of(MorphAbility.HOSTILE)),
                    "silverfish must derive exactly {HOSTILE} (iChun's own "
                            + "mapping), derived " + fish);
        }).thenSucceed();
    }

    /**
     * {@code morph/ability/climb-wall-climbers-only} — THE WHOLE MATRIX. Sweeps
     * every registered mob type and asserts the set that derives
     * {@link MorphAbility#CLIMB} is exactly {@code {spider, cave_spider}}: the
     * two mobs a jar-wide scan finds using {@code WallClimberNavigation}. This is
     * the audit the user asked for ("check the whole matrix, not just the
     * reported case") expressed as an assertion, so any future re-widening of the
     * climb signal — by a tag, a superclass or a new mob — goes red here.
     */
    @GameTest(maxTicks = 100)
    public void onlyWallClimbersDeriveClimbAcrossTheWholeMobSet(
            GameTestHelper helper) {
        Set<String> climbers = new TreeSet<>();
        for (Mob mob : everyMob(helper)) {
            if (MorphAbility.deriveAbilities(mob).contains(MorphAbility.CLIMB)) {
                climbers.add(idOf(mob));
            }
        }
        Set<String> expected = new TreeSet<>(
                List.of("minecraft:spider", "minecraft:cave_spider"));
        helper.assertTrue(climbers.equals(expected),
                "exactly the two vanilla wall-climbers may derive CLIMB; "
                        + "expected " + expected + " but got " + climbers);
        helper.succeed();
    }

    /**
     * {@code morph/ability/swim-water-breathers} — the MISS half of the same
     * audit. {@code #minecraft:can_breathe_under_water} contains
     * {@code #minecraft:undead}, so it cannot gate SWIM on its own (a plain
     * zombie would get a swim boost); subtracting {@code #undead} makes it usable
     * and picks up the two vanilla water-breathers that are NOT in
     * {@code #minecraft:aquatic}: frog and copper golem, which had no SWIM at all.
     *
     * <p>Mutation-proof in both directions: dropping the new
     * {@code CAN_BREATHE_UNDER_WATER} clause fails the frog/copper-golem
     * assertions; dropping the {@code !UNDEAD} subtraction fails the zombie one.</p>
     */
    @GameTest(maxTicks = 80)
    public void waterBreathersDeriveSwimButLandUndeadDoNot(
            GameTestHelper helper) {
        LivingEntity frog = spawn(helper, EntityTypes.FROG, 1, 1);
        LivingEntity copperGolem = spawn(helper, EntityTypes.COPPER_GOLEM, 3, 1);
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 5, 1);
        LivingEntity skeleton = spawn(helper, EntityTypes.SKELETON, 7, 1);

        helper.startSequence().thenExecuteAfter(2, () -> {
            helper.assertTrue(MorphAbility.deriveAbilities(frog)
                            .contains(MorphAbility.SWIM),
                    "a frog breathes underwater in 26.2 (#can_breathe_under_water)"
                            + " so a frog morph must derive SWIM");
            helper.assertTrue(MorphAbility.deriveAbilities(copperGolem)
                            .contains(MorphAbility.SWIM),
                    "a copper golem breathes underwater in 26.2 so a copper "
                            + "golem morph must derive SWIM");
            helper.assertFalse(MorphAbility.deriveAbilities(zombie)
                            .contains(MorphAbility.SWIM),
                    "a LAND undead must NOT derive SWIM — #can_breathe_under_water"
                            + " contains #undead, which is the whole reason the "
                            + "tag is subtracted");
            helper.assertFalse(MorphAbility.deriveAbilities(skeleton)
                            .contains(MorphAbility.SWIM),
                    "a skeleton must NOT derive SWIM (see zombie)");
        }).thenSucceed();
    }

    // ==================================================================
    // 2. morph/variant/volatile-nbt-stripped  (playtest bug 2)
    // ==================================================================

    /** Loads {@code tag} onto {@code victim} through the same 26.2 bridge
     *  {@code MorphEntities} uses, so a test can give one instance a different
     *  per-instance field value than another. */
    private static void load(GameTestHelper helper, LivingEntity victim,
            CompoundTag tag) {
        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), tag);
        victim.load(in);
    }

    /**
     * {@code morph/variant/volatile-nbt-stripped} — the reported bug. Two
     * endermites that differ ONLY in {@code Lifetime} (the per-tick counter
     * {@code Endermite.aiStep} increments and {@code addAdditionalSaveData}
     * writes unconditionally — javap, 26.2) are ONE morph, so acquiring the
     * second is rejected as a duplicate and the wheel keeps a single entry.
     *
     * <p>The acquire path is driven twice with the transition lock cleared in
     * between, so the second {@code false} can only mean "duplicate" and never
     * "still morphing"; the size of {@code acquired()} is the assertion that
     * matches what the user actually sees.</p>
     *
     * <p>Mutation-proof: deleting the {@code Endermite.class} rule from
     * {@code MorphNbtStripper} fails both the identity compare and the
     * {@code acquired().size()} assertion.</p>
     */
    @GameTest(maxTicks = 120)
    public void endermiteLifetimeIsNotPartOfTheMorphIdentity(
            GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity young = spawn(helper, EntityTypes.ENDERMITE, 1, 1);
        LivingEntity old = spawn(helper, EntityTypes.ENDERMITE, 3, 1);

        helper.startSequence().thenExecuteAfter(2, () -> {
            CompoundTag aged = new CompoundTag();
            aged.putInt("Lifetime", 1337);
            load(helper, old, aged);

            MorphVariant a = Morph.variantOf(young);
            MorphVariant b = Morph.variantOf(old);
            helper.assertFalse(a.data().contains("Lifetime"),
                    "Lifetime is per-instance and must not survive identity "
                            + "normalization; identity data = " + a.data());
            helper.assertTrue(a.equals(b),
                    "two endermites differing only in Lifetime must be ONE "
                            + "morph: " + a + " vs " + b);

            helper.assertTrue(Morph.acquireTarget(player, young, false, true),
                    "the first endermite must be acquired");
            Morph.clearTransitionLock(player);
            helper.assertFalse(Morph.acquireTarget(player, old, false, true),
                    "a second endermite with a different Lifetime must be "
                            + "rejected as a DUPLICATE");
            MorphState state = Morph.STATE.get(player);
            helper.assertTrue(state.acquired().size() == 1,
                    "the morph wheel must hold exactly ONE endermite, holds "
                            + state.acquired().size() + ": " + state.acquired());
        }).thenSucceed();
    }

    /**
     * {@code morph/variant/name-tag-discriminates}. The user's explicit
     * exception: "endermites should only have one version other than if you
     * nametag them". A name-tagged endermite is still its own morph after the
     * volatile-NBT strip, so the wheel gains a second entry.
     *
     * <p>Mutation-proof: adding {@code "CustomName"} to {@code TRANSIENT_KEYS}
     * (or to the endermite strip rule) fails this.</p>
     */
    @GameTest(maxTicks = 120)
    public void aNameTaggedEndermiteIsStillItsOwnMorph(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity plain = spawn(helper, EntityTypes.ENDERMITE, 1, 1);
        LivingEntity named = spawn(helper, EntityTypes.ENDERMITE, 3, 1);

        helper.startSequence().thenExecuteAfter(2, () -> {
            // load() FIRST — Entity.load reads CustomName too, so a tag without
            // it would clear a name set beforehand — then name the mob.
            CompoundTag aged = new CompoundTag();
            aged.putInt("Lifetime", 4242);
            load(helper, named, aged);   // volatile noise under the name
            named.setCustomName(Component.literal("Nibbles"));

            helper.assertFalse(Morph.variantOf(plain)
                            .equals(Morph.variantOf(named)),
                    "a name-tagged endermite must remain a DISTINCT morph");

            helper.assertTrue(Morph.acquireTarget(player, plain, false, true),
                    "the plain endermite must be acquired");
            Morph.clearTransitionLock(player);
            helper.assertTrue(Morph.acquireTarget(player, named, false, true),
                    "the name-tagged endermite must be acquired as a NEW morph");
            MorphState state = Morph.STATE.get(player);
            helper.assertTrue(state.acquired().size() == 2,
                    "plain + name-tagged endermite = 2 morphs, got "
                            + state.acquired().size() + ": " + state.acquired());
        }).thenSucceed();
    }

    /**
     * Every key that is per-INSTANCE volatile somewhere in vanilla 26.2. Sourced
     * from a javap sweep of every {@code addAdditionalSaveData} under
     * {@code net.minecraft.world.entity} plus the two mob interfaces
     * ({@code NeutralMob}, {@code InventoryCarrier}); the full table with the
     * keep/strip decision for every key lives in the wave-8 spec note. None of
     * these names is an appearance discriminator for ANY vanilla mob, so none may
     * appear in any morph identity.
     */
    private static final Set<String> VOLATILE_KEYS = Set.of(
            // per-tick counters / timers / countdowns
            "Lifetime", "EggLayTime", "Moistness", "DarkTicksRemaining",
            "MoreCarrotTicks", "scute_time", "next_weather_age", "still_timeout",
            "DuplicationCooldown", "LastPoseTick", "TicksSincePollination",
            "CannotEnterHiveTicks", "CropsGrownSincePollination", "SpellTicks",
            "AttackTick", "StunTick", "RoarTick", "life_ticks", "pickup_timer",
            "StrayConversionTime", "DrownedConversionTime", "InWaterTime",
            "ConversionTime", "TimeInOverworld", "DespawnDelay",
            "SkeletonTrapTime", "LastRestock", "LastGossipDecay", "Fuse", "fuse",
            "anger_end_time", "AngerTime", "InLove",
            // spawn-time random rolls
            "Temper", "Strength", "ExplosionRadius", "ExplosionPower",
            // world positions the individual happens to remember
            "hive_pos", "flower_pos", "anchor_pos", "bound_pos",
            "patrol_target", "wander_target", "carriedBlockState",
            // per-instance ownership / social memory
            "Owner", "owner", "LoveCause", "Trusted", "Trusting", "angry_at",
            "PlayerCreated", "ConversionPlayer", "Gossips", "Offers", "Xp",
            "RaidId", "Wave", "listener", "anger",
            // inventories
            "Inventory", "Items",
            // transient behaviour toggles / poses
            "Sitting", "Sleeping", "Crouching", "state", "BatFlags", "ignited",
            "HasNectar", "HasStung", "GotFish", "PuffState", "AttachFace",
            "Peek", "has_egg", "IsChickenJockey", "CanBreakDoors",
            "IsImmuneToZombification", "CannotBeHunted", "CannotHunt", "Johnny",
            "PatrolLeader", "Patrolling", "CanJoinRaid", "SkeletonTrap",
            "EatingHaystack", "Bred", "Tame", "FromBucket", "from_bucket",
            "FoodLevel", "RestocksToday", "VillagerDataFinalized",
            "AssignProfessionWhenSpawned", "locator_bar_icon");

    /**
     * {@code morph/variant/volatile-nbt-stripped} — THE WHOLE MATRIX. Sweeps
     * every registered mob type and asserts its normalized identity contains NOT
     * ONE of {@link #VOLATILE_KEYS}. This is the second audit the user asked for
     * ("are there OTHER mobs whose saved NBT contains per-instance volatile
     * fields... check the whole mob set") as an assertion, so a future 26.2 mob
     * (or a re-ordered strip table) that reintroduces a per-instance field into
     * the identity goes red immediately.
     *
     * <p>Mutation-proof: deleting ANY single rule from {@code MorphNbtStripper}
     * fails this with the offending mob and key named.</p>
     */
    @GameTest(maxTicks = 100)
    public void noMorphIdentityCarriesVolatileNbtAcrossTheWholeMobSet(
            GameTestHelper helper) {
        List<String> offenders = new ArrayList<>();
        for (Mob mob : everyMob(helper)) {
            CompoundTag identity = Morph.variantOf(mob).data();
            for (String key : new TreeSet<>(identity.keySet())) {
                if (VOLATILE_KEYS.contains(key)) {
                    offenders.add(idOf(mob) + "." + key);
                }
            }
        }
        helper.assertTrue(offenders.isEmpty(),
                "per-instance volatile NBT must never be part of a morph "
                        + "identity — these would fork a new morph per kill: "
                        + offenders);
        helper.succeed();
    }

    /**
     * The other direction: the strip must not eat APPEARANCE. Each of these keys
     * is what makes two same-species mobs look different, so each must survive
     * normalization — otherwise "one endermite" would have quietly become "one
     * sheep" too.
     *
     * <p>Mutation-proof: adding any one of these keys to a
     * {@code MorphNbtStripper} rule (or to {@code TRANSIENT_KEYS}) fails here.</p>
     */
    @GameTest(maxTicks = 100)
    public void appearanceNbtStaysPartOfTheIdentity(GameTestHelper helper) {
        record Case(EntityType<? extends Mob> type, String key) {
        }
        List<Case> cases = List.of(
                new Case(EntityTypes.SHEEP, "Color"),
                new Case(EntityTypes.SHEEP, "Sheared"),
                new Case(EntityTypes.WOLF, "CollarColor"),
                new Case(EntityTypes.CAT, "variant"),
                new Case(EntityTypes.HORSE, "Variant"),
                new Case(EntityTypes.SLIME, "Size"),
                new Case(EntityTypes.CREEPER, "powered"),
                new Case(EntityTypes.VILLAGER, "VillagerData"),
                new Case(EntityTypes.DONKEY, "ChestedHorse"),
                new Case(EntityTypes.SHULKER, "Color"),
                new Case(EntityTypes.SNOW_GOLEM, "Pumpkin"),
                new Case(EntityTypes.GOAT, "IsScreamingGoat"),
                new Case(EntityTypes.PANDA, "MainGene"),
                new Case(EntityTypes.RABBIT, "RabbitType"),
                new Case(EntityTypes.MOOSHROOM, "Type"),
                new Case(EntityTypes.COPPER_GOLEM, "weather_state"),
                new Case(EntityTypes.PIG, "sound_variant"),
                new Case(EntityTypes.PHANTOM, "size"),
                new Case(EntityTypes.BOGGED, "sheared"),
                new Case(EntityTypes.SALMON, "type"));

        List<String> missing = new ArrayList<>();
        for (Case c : cases) {
            Entity entity = c.type().create(helper.getLevel(),
                    EntitySpawnReason.LOAD);
            if (!(entity instanceof LivingEntity living)) {
                missing.add(c.type() + " could not be built");
                continue;
            }
            CompoundTag identity = Morph.variantOf(living).data();
            if (!identity.contains(c.key())) {
                missing.add(EntityType.getKey(c.type()) + "." + c.key()
                        + " (identity=" + new TreeSet<>(identity.keySet()) + ")");
            }
        }
        helper.assertTrue(missing.isEmpty(),
                "appearance NBT must remain part of the morph identity, "
                        + "these were stripped: " + missing);
        helper.succeed();
    }

    // ==================================================================
    // 3. morph/mech/sleep-humanoid  (playtest bug 3)
    // ==================================================================

    /** The vanilla types {@link MorphHumanoid} must call people-shaped. */
    private static final Set<String> HUMANOID_IDS = new TreeSet<>(List.of(
            "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
            "minecraft:zombie_villager", "minecraft:zombified_piglin",
            "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
            "minecraft:wither_skeleton", "minecraft:parched",
            "minecraft:piglin", "minecraft:piglin_brute",
            "minecraft:villager", "minecraft:wandering_trader",
            "minecraft:witch", "minecraft:pillager", "minecraft:vindicator",
            "minecraft:evoker", "minecraft:illusioner",
            "minecraft:enderman", "minecraft:warden", "minecraft:giant",
            "minecraft:iron_golem", "minecraft:snow_golem",
            "minecraft:copper_golem", "minecraft:vex", "minecraft:creaking"));

    /**
     * {@code morph/mech/sleep-humanoid} — the POLICY, whole-matrix. Sweeps every
     * registered mob type and pins {@link MorphHumanoid#isHumanoid} to the
     * documented set. This is how "humanoid" is defined in one auditable place:
     * the render code (client-only, not reachable from a gametest) asks exactly
     * this question, so pinning it here is the closest a headless test can get to
     * the user's rule "a chicken must not lie on its back".
     */
    @GameTest(maxTicks = 100)
    public void humanoidPolicyCoversExactlyThePeopleShapedMobs(
            GameTestHelper helper) {
        Set<String> humanoids = new TreeSet<>();
        for (Mob mob : everyMob(helper)) {
            if (MorphHumanoid.isHumanoid(mob)) {
                humanoids.add(idOf(mob));
            }
        }
        helper.assertTrue(humanoids.equals(HUMANOID_IDS),
                "the humanoid set drives who lies down in a bed; expected "
                        + HUMANOID_IDS + " but got " + humanoids);
        // and the shapes the user singled out are definitely NOT in it
        for (EntityType<? extends Mob> type : List.of(EntityTypes.CHICKEN,
                EntityTypes.COW, EntityTypes.SPIDER, EntityTypes.ENDERMITE,
                EntityTypes.SLIME, EntityTypes.BAT)) {
            helper.assertFalse(humanoids.contains(
                            EntityType.getKey(type).toString()),
                    EntityType.getKey(type) + " must not be humanoid");
        }
        helper.succeed();
    }

    /** A player-morph variant is humanoid by definition (it has no dummy mob). */
    @GameTest(maxTicks = 40)
    public void aPlayerMorphIsHumanoid(GameTestHelper helper) {
        MorphVariant playerVariant = MorphVariant.ofPlayer(
                java.util.UUID.randomUUID(), "Ded811");
        helper.assertTrue(MorphHumanoid.isHumanoid(playerVariant,
                        helper.getLevel()),
                "a PLAYER morph must be humanoid — it is the case the user "
                        + "called out first");
        helper.assertFalse(MorphHumanoid.isHumanoid(
                        MorphVariant.ofType(com.deds.api.id.BId.of(
                                "minecraft:chicken")), helper.getLevel()),
                "a chicken morph must not be humanoid");
        helper.succeed();
    }

    /**
     * {@code morph/mech/sleep-humanoid} — the HITBOX half ("also make sure the
     * hitboxes work correctly in bed"). While a morphed player is in a bed the
     * collision box must be vanilla's {@code SLEEPING_DIMENSIONS}
     * (0.2×0.2, eye 0.2 — javap of {@code LivingEntity.<clinit>}), NOT the
     * morph's standing box: {@code LivingEntityDimensionsMixin} deliberately
     * defers every non-upright pose to vanilla. The sleep is held across TWO
     * driven living ticks so {@code LivingEntity.tick}'s per-tick
     * {@code checkBedExists} watchdog really runs (Playbook §8 — a same-tick
     * assertion is blind to it), and the morph box is asserted to come BACK on
     * waking.
     *
     * <p>{@code getBedOrientation()} is asserted too: it is the field the render
     * fix reads to anchor the lying body on the pillow, and a null there is
     * exactly the "standing on the pillow" symptom.</p>
     *
     * <p>Mutation-proof: letting the dimensions mixin answer for
     * {@code Pose.SLEEPING} (dropping its early return) makes the in-bed box the
     * zombie's 0.6×1.95 and fails.</p>
     *
     * <p><b>Runs in the pinned-midnight {@code night} environment, and must.</b>
     * {@code Player.tick} force-wakes any sleeper whose position fails the
     * {@code BED_RULE} environment attribute — {@code WHEN_DARK} in the
     * overworld, i.e. {@code Level.isDarkOutside()} (javap, offsets 44-122;
     * {@code BedRule$Rule.test} offset 46) — by calling
     * {@code stopSleepInBed(false, true)}. In daylight that fires on the FIRST
     * driven living tick, before {@code checkBedExists} is ever consulted, so
     * a "sleep persists" assertion is unholdable at noon. The gametest server
     * reuses and saves {@code build/run/gametest/world/}, so without the pin
     * the world clock — and therefore this test — is inherited from however
     * many runs that directory has accumulated: it passed against a warm world
     * that happened to be at night and failed 3/3 against a cold one
     * (2026-07-29; docs/TESTING.md "cold world" rule). The wave-8 layer-3
     * harness already carried the same lesson as a comment; this layer-2 test
     * did not carry it as configuration.</p>
     *
     * <p><b>…and the environment is not a HOLD (2026-07-30).</b>
     * {@code minecraft:clock_time} is a ONE-SHOT:
     * {@code TestEnvironmentDefinition$ClockTime.setup} writes
     * {@code setTotalTicks} once and the clock then free-runs (Playbook §8d).
     * This test is the module's most exposed one because it sleeps LATE — the
     * bed click happens {@code TRANSITION_TICKS + 22} ticks into the body, on
     * top of however many ticks passed between the batch's one-shot write and
     * the batch actually running. So the clock is re-pinned in the body, right
     * before the sleep, and the {@code isDarkOutside()} precondition below now
     * follows a pin rather than a hope. The annotation stays: it buys the
     * batch and the teardown that restores the clock.</p>
     *
     * <p>The bed is a PLAIN VANILLA {@link BedBlock}, deliberately: the
     * {@code checkBedExists} lambda is a hard {@code instanceof BedBlock} and
     * Carpenter's Blocks' {@code LivingEntityBedCheckMixin} — which widens it —
     * is not loaded in a morph-only run. Nothing here may depend on it.</p>
     */
    @GameTest(maxTicks = 240, environment = "deds_morph_test:night")
    public void aMorphedSleeperKeepsTheVanillaBedHitbox(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos foot = new BlockPos(3, 2, 3);
        BlockPos head = foot.east();
        // 26.2: the sixteen beds live in the ColorCollection Blocks.BED.
        BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));

        ServerPlayer player = mockPlayer(helper);
        mockPlayer(helper); // awake guard: all-asleep would trigger the night skip
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () ->
                        helper.assertTrue(
                                Morph.acquireTarget(player, zombie, false, true),
                                "acquiring a zombie must start the transition"))
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 20, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the transition must be over before sleeping");
                    EntityDimensions standing =
                            player.getDimensions(Pose.STANDING);
                    helper.assertTrue(
                            Math.abs(standing.height() - zombie.getBbHeight())
                                    < EPS,
                            "precondition: the morphed player must carry the "
                                    + "zombie's standing box, has "
                                    + standing.height());
                    // the in-bed box is vanilla's, whatever the morph is
                    EntityDimensions sleeping =
                            player.getDimensions(Pose.SLEEPING);
                    helper.assertTrue(Math.abs(sleeping.width() - 0.2f) < EPS
                                    && Math.abs(sleeping.height() - 0.2f) < EPS
                                    && Math.abs(sleeping.eyeHeight() - 0.2f)
                                            < EPS,
                            "a morphed player's SLEEPING dimensions must be "
                                    + "vanilla 0.2x0.2 (eye 0.2), are "
                                    + sleeping.width() + "x" + sleeping.height()
                                    + " eye " + sleeping.eyeHeight());
                    // RE-PIN the clock here, not just in the environment: the
                    // batch's clock_time write is a one-shot and this sleep
                    // happens ~TRANSITION_TICKS+22 ticks into the body, on top
                    // of unbounded cross-batch drift (Playbook §8d).
                    pinMidnight(helper);
                    // Precondition, not decoration: if the `night` environment
                    // ever stops being applied — or the pin above is deleted —
                    // say SO here instead of failing three assertions later as
                    // a mystery. Player.tick's BED_RULE watchdog needs
                    // isDarkOutside().
                    helper.assertTrue(helper.getLevel().isDarkOutside(),
                            "precondition: this test must run in the pinned "
                                    + "midnight environment "
                                    + "(deds_morph_test:night) — Player.tick "
                                    + "force-wakes any sleeper in daylight. "
                                    + "clock="
                                    + helper.getLevel().getOverworldClockTime()
                                    + " skyDarken="
                                    + helper.getLevel().getSkyDarken());
                    player.startSleeping(helper.absolutePos(head));
                    helper.assertTrue(player.isSleeping(),
                            "the morphed player must be asleep");
                })
                .thenExecuteAfter(1, player::doTick)
                .thenExecuteAfter(1, player::doTick)
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(player.isSleeping(),
                            "the sleep must PERSIST across living ticks "
                                    + "(LivingEntity.tick's checkBedExists "
                                    + "watchdog AND Player.tick's BED_RULE "
                                    + "watchdog). bedStillThere="
                                    + (helper.getLevel()
                                            .getBlockState(
                                                    helper.absolutePos(head))
                                            .getBlock() instanceof BedBlock)
                                    + " sleepingPos=" + player.getSleepingPos()
                                    + " isDarkOutside="
                                    + helper.getLevel().isDarkOutside()
                                    + " clock="
                                    + helper.getLevel().getOverworldClockTime()
                                    + " sleepTimer=" + player.getSleepTimer()
                                    + " — sleepTimer tells the two watchdogs "
                                    + "APART (both measured): ~101 means "
                                    + "Player.tick's BED_RULE branch called "
                                    + "stopSleepInBed(false, …) which parks "
                                    + "the counter at 100, i.e. it was DAY; "
                                    + "0 means stopSleeping()/stopSleepInBed"
                                    + "(true, …), i.e. the bed went away");
                    helper.assertTrue(player.hasPose(Pose.SLEEPING),
                            "a sleeping morphed player must hold Pose.SLEEPING "
                                    + "— that is the flag the renderer's "
                                    + "lie-down branch keys on, got "
                                    + player.getPose());
                    helper.assertTrue(
                            player.getBedOrientation() == Direction.EAST,
                            "getBedOrientation() must answer the bed's facing "
                                    + "(the renderer anchors the body on the "
                                    + "pillow with it), got "
                                    + player.getBedOrientation());
                    helper.assertTrue(
                            Math.abs(player.getBbWidth() - 0.2f) < EPS
                                    && Math.abs(player.getBbHeight() - 0.2f)
                                            < EPS,
                            "the live in-bed AABB must be 0.2x0.2, is "
                                    + player.getBbWidth() + "x"
                                    + player.getBbHeight());
                    player.stopSleeping();
                })
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(player.isSleeping(),
                            "the player must be awake again");
                    helper.assertTrue(
                            Math.abs(player.getBbHeight()
                                    - zombie.getBbHeight()) < EPS,
                            "waking must restore the MORPH box ("
                                    + zombie.getBbHeight() + "), is "
                                    + player.getBbHeight());
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
