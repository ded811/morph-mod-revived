package com.deds.morph.gametest;

import com.deds.morph.Morph;
import com.deds.morph.MorphAbility;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.deds.morph.MorphAbility.CLIMB;
import static com.deds.morph.MorphAbility.FALL_NEGATE;
import static com.deds.morph.MorphAbility.FIRE_IMMUNITY;
import static com.deds.morph.MorphAbility.FLOAT;
import static com.deds.morph.MorphAbility.FLY;
import static com.deds.morph.MorphAbility.HOSTILE;
import static com.deds.morph.MorphAbility.POISON_RESISTANCE;
import static com.deds.morph.MorphAbility.STEP;
import static com.deds.morph.MorphAbility.SUNBURN;
import static com.deds.morph.MorphAbility.SWIM;
import static com.deds.morph.MorphAbility.WATER_ALLERGY;
import static com.deds.morph.MorphAbility.WITHER_RESISTANCE;

/**
 * Server gametests for the generic {@link MorphAbility#deriveAbilities} function
 * (mod id {@code deds_morph}, spec
 * {@code docs/specs/morph/wave2/redesign-abilities-hitbox.md} §A.2). Each mob is
 * spawned with {@code spawnWithNoFreeWill} (a fully-built {@code Mob} with
 * attributes) and its derived {@link MorphAbility} set asserted.
 *
 * <p><b>These are the GENERIC-derivation results, javap-verified against the
 * 26.2 entity-type tags/attributes/method-overrides this session</b> — they are
 * a faithful modern re-derivation of iChun's hand-written per-class table, not a
 * byte-for-byte copy of it, so a few sets differ from the original by design
 * (the task grants this latitude; assertions below stay on the clearly-correct
 * memberships):
 * <ul>
 * <li><b>Spiders</b>: BOTH {@code spider} and {@code cave_spider} derive
 *     {@code POISON_RESISTANCE} — {@code Spider.canBeAffected(POISON)==false} in
 *     vanilla (spiders really are poison-immune), so the generic
 *     {@code !canBeAffected(poison)} rule is more accurate than iChun's table
 *     (which only listed cave spider).</li>
 * <li><b>Undead</b> (zombie/skeleton/wither_skeleton): modern tags put all
 *     undead in {@code can_breathe_under_water} (→ SWIM),
 *     {@code ignores_poison_and_regen} (→ POISON_RESISTANCE) and
 *     {@code inverted_healing_and_harm} (→ WITHER_RESISTANCE), so these appear
 *     in addition to iChun's {hostile, sunburn}. Asserted via required/forbidden
 *     so only the clearly-correct memberships are pinned.</li>
 * <li><b>Fall-immune flyers</b> (bat/blaze): {@code fall_damage_immune} adds
 *     {@code FALL_NEGATE} alongside {@code FLY} — bats/blazes really are
 *     fall-immune.</li>
 * <li><b>Custom-flight flyers</b> (bat, blaze): the nav/control probe misses
 *     them (javap-verified: neither uses {@code FlyingMoveControl}/
 *     {@code FlyingPathNavigation}), so {@link MorphAbility} adds an explicit
 *     flyer fallback set — a documented heuristic.</li>
 * <li><b>Float</b> (chicken): no generic 26.2 property exists; an explicit
 *     {@code {chicken, parrot}} fallback set — a documented heuristic.</li>
 * <li><b>Step</b>: {@code horse} AND {@code iron_golem} both carry
 *     {@code STEP_HEIGHT==1.0} (&gt; the 0.6 player default) so both derive
 *     {@code STEP}; {@code iron_golem} is NOT in any water-breathing tag so it
 *     does not derive {@code SWIM} generically (iChun gave it swim by hand) —
 *     hence iron_golem asserts only the clearly-correct {@code FALL_NEGATE}
 *     present / {@code HOSTILE} absent.</li>
 * </ul>
 *
 * <p>Bound only to the public seam {@link MorphAbility#deriveAbilities}. Written
 * to keep the existing morph gametests untouched.</p>
 */
public final class MorphAbilityGameTests implements CustomTestMethodInvoker {

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    /** Asserts the derived set contains every {@code required} ability and none
     *  of the {@code forbidden} ones (the clearly-correct-memberships form). */
    private static void expect(GameTestHelper helper, String name,
            LivingEntity mob, Set<MorphAbility> required,
            Set<MorphAbility> forbidden) {
        EnumSet<MorphAbility> got = MorphAbility.deriveAbilities(mob);
        for (MorphAbility a : required) {
            helper.assertTrue(got.contains(a),
                    name + " must derive " + a + " but derived " + got);
        }
        for (MorphAbility a : forbidden) {
            helper.assertFalse(got.contains(a),
                    name + " must NOT derive " + a + " but derived " + got);
        }
    }

    /** Asserts the derived set equals EXACTLY {@code expected} (used only where
     *  the generic result is fully pinned by verified tags/attributes). */
    private static void expectExact(GameTestHelper helper, String name,
            LivingEntity mob, MorphAbility... expected) {
        EnumSet<MorphAbility> got = MorphAbility.deriveAbilities(mob);
        EnumSet<MorphAbility> exp = EnumSet.noneOf(MorphAbility.class);
        exp.addAll(List.of(expected));
        helper.assertTrue(got.equals(exp),
                name + " abilities: expected exactly " + exp + " but derived " + got);
    }

    private static Set<MorphAbility> of(MorphAbility... a) {
        return a.length == 0 ? EnumSet.noneOf(MorphAbility.class)
                : EnumSet.copyOf(List.of(a));
    }

    // ==================================================================
    // 1. flyers + floaters (custom-flight + explicit-fallback signals)
    // ==================================================================

    /**
     * {@code bat} ⇒ {FLY, FALL_NEGATE} (custom-flight fallback + fall-immune
     * tag); {@code chicken} ⇒ {FLOAT, FALL_NEGATE} (explicit floater fallback +
     * fall-immune tag). Neither is hostile or a climber.
     */
    @GameTest(maxTicks = 60)
    public void flyersAndFloaters(GameTestHelper helper) {
        LivingEntity bat = spawn(helper, EntityTypes.BAT, 1, 1);
        LivingEntity chicken = spawn(helper, EntityTypes.CHICKEN, 3, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    expectExact(helper, "bat", bat, FLY, FALL_NEGATE);
                    expectExact(helper, "chicken", chicken, FLOAT, FALL_NEGATE);
                })
                .thenSucceed();
    }

    // ==================================================================
    // 2. spiders (climb via WallClimberNavigation + poison immunity)
    // ==================================================================

    /**
     * {@code spider} and {@code cave_spider} both ⇒ {CLIMB, HOSTILE,
     * POISON_RESISTANCE}. Spiders climb (WallClimberNavigation — the ONLY
     * climb signal since wave 8 dropped the ARTHROPOD tag fallback that also
     * gave endermite/silverfish/bee a wall-climb),
     * are hostile (Enemy/MONSTER), and are poison-immune
     * ({@code Spider.canBeAffected(POISON)==false}).
     */
    @GameTest(maxTicks = 60)
    public void spiders(GameTestHelper helper) {
        LivingEntity spider = spawn(helper, EntityTypes.SPIDER, 1, 1);
        LivingEntity caveSpider = spawn(helper, EntityTypes.CAVE_SPIDER, 3, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    expectExact(helper, "spider", spider,
                            CLIMB, HOSTILE, POISON_RESISTANCE);
                    expectExact(helper, "cave_spider", caveSpider,
                            CLIMB, HOSTILE, POISON_RESISTANCE);
                })
                .thenSucceed();
    }

    // ==================================================================
    // 3. undead (sunburn + the undead-tag ability cluster)
    // ==================================================================

    /**
     * {@code zombie}/{@code skeleton} ⇒ at least {HOSTILE, SUNBURN} (never
     * FIRE_IMMUNITY — neither is fire-immune); {@code wither_skeleton} ⇒ at least
     * {FIRE_IMMUNITY, WITHER_RESISTANCE, HOSTILE} and NEVER SUNBURN (fire-immune
     * undead do not burn in daylight).
     */
    @GameTest(maxTicks = 60)
    public void undead(GameTestHelper helper) {
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 1, 1);
        LivingEntity skeleton = spawn(helper, EntityTypes.SKELETON, 3, 1);
        LivingEntity witherSkeleton =
                spawn(helper, EntityTypes.WITHER_SKELETON, 5, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    expect(helper, "zombie", zombie,
                            of(HOSTILE, SUNBURN),
                            of(FIRE_IMMUNITY, FLY, CLIMB, STEP, WATER_ALLERGY,
                                    FLOAT, FALL_NEGATE));
                    expect(helper, "skeleton", skeleton,
                            of(HOSTILE, SUNBURN),
                            of(FIRE_IMMUNITY, FLY, CLIMB, STEP, WATER_ALLERGY,
                                    FLOAT, FALL_NEGATE));
                    expect(helper, "wither_skeleton", witherSkeleton,
                            of(FIRE_IMMUNITY, WITHER_RESISTANCE, HOSTILE),
                            of(SUNBURN, FLY, CLIMB, STEP, WATER_ALLERGY, FLOAT,
                                    FALL_NEGATE));
                })
                .thenSucceed();
    }

    // ==================================================================
    // 4. nether mobs (fire immunity + water allergy + fall-immune)
    // ==================================================================

    /**
     * {@code blaze} ⇒ at least {FLY, FIRE_IMMUNITY, WATER_ALLERGY, HOSTILE}
     * (never SUNBURN — fire-immune); {@code magma_cube} ⇒ at least
     * {FIRE_IMMUNITY, FALL_NEGATE, HOSTILE} and never WATER_ALLERGY/SWIM.
     */
    @GameTest(maxTicks = 60)
    public void netherMobs(GameTestHelper helper) {
        LivingEntity blaze = spawn(helper, EntityTypes.BLAZE, 1, 1);
        LivingEntity magmaCube = spawn(helper, EntityTypes.MAGMA_CUBE, 3, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    expect(helper, "blaze", blaze,
                            of(FLY, FIRE_IMMUNITY, WATER_ALLERGY, HOSTILE),
                            of(SUNBURN, CLIMB, STEP, SWIM, POISON_RESISTANCE,
                                    WITHER_RESISTANCE, FLOAT));
                    expect(helper, "magma_cube", magmaCube,
                            of(FIRE_IMMUNITY, FALL_NEGATE, HOSTILE),
                            of(SUNBURN, WATER_ALLERGY, SWIM, POISON_RESISTANCE,
                                    WITHER_RESISTANCE, FLY, CLIMB, FLOAT));
                })
                .thenSucceed();
    }

    // ==================================================================
    // 5. aquatic + golem (swim tag; fall-negate; step attribute)
    // ==================================================================

    /**
     * {@code squid} ⇒ EXACTLY {SWIM} (aquatic/can-breathe tags; not hostile —
     * WATER_CREATURE, not Enemy). {@code iron_golem} ⇒ at least {FALL_NEGATE}
     * and never HOSTILE (MISC category, not Enemy). (Iron golem also derives STEP
     * from its 1.0 step attribute; it does NOT derive SWIM generically — iChun
     * gave it swim by hand, no 26.2 tag expresses it — so SWIM/STEP are left
     * unasserted here.)
     */
    @GameTest(maxTicks = 60)
    public void aquaticAndGolem(GameTestHelper helper) {
        LivingEntity squid = spawn(helper, EntityTypes.SQUID, 1, 1);
        LivingEntity ironGolem = spawn(helper, EntityTypes.IRON_GOLEM, 4, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    expectExact(helper, "squid", squid, SWIM);
                    expect(helper, "iron_golem", ironGolem,
                            of(FALL_NEGATE),
                            of(HOSTILE, FLY, CLIMB, FIRE_IMMUNITY, WATER_ALLERGY,
                                    SUNBURN, FLOAT));
                })
                .thenSucceed();
    }

    // ==================================================================
    // 6. step + water allergy + passive (empty)
    // ==================================================================

    /**
     * {@code horse} ⇒ EXACTLY {STEP} (1.0 step attribute; passive).
     * {@code enderman} ⇒ at least {WATER_ALLERGY, HOSTILE}
     * ({@code isSensitiveToWater()} + MONSTER). {@code cow} and {@code pig} ⇒
     * EXACTLY {} (no ability signal at all — passive baseline).
     */
    @GameTest(maxTicks = 60)
    public void stepAllergyAndPassive(GameTestHelper helper) {
        LivingEntity horse = spawn(helper, EntityTypes.HORSE, 1, 1);
        LivingEntity enderman = spawn(helper, EntityTypes.ENDERMAN, 3, 1);
        LivingEntity cow = spawn(helper, EntityTypes.COW, 5, 1);
        LivingEntity pig = spawn(helper, EntityTypes.PIG, 7, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    expectExact(helper, "horse", horse, STEP);
                    expect(helper, "enderman", enderman,
                            of(WATER_ALLERGY, HOSTILE),
                            of(FIRE_IMMUNITY, FLY, CLIMB, SWIM, SUNBURN,
                                    FALL_NEGATE, POISON_RESISTANCE,
                                    WITHER_RESISTANCE, FLOAT));
                    expectExact(helper, "cow", cow);
                    expectExact(helper, "pig", pig);
                })
                .thenSucceed();
    }

    // ==================================================================
    // 7. morph/hitbox/humanoid-crouch (server-observable getDimensions)
    // ==================================================================

    /**
     * A crouching HUMANOID morph shrinks its collision box so the player fits
     * under a low (1.5-tall) gap and the camera drops. Morphing as a
     * {@code zombie} (~1.95 standing), {@code getDimensions(Pose.CROUCHING)} is
     * shorter than {@code getDimensions(Pose.STANDING)} and {@code <= 1.5}, with
     * the width unchanged. Server-observable — the {@code getDimensions} mixin
     * runs server-side off the committed morph.
     */
    @GameTest(maxTicks = 260)
    public void humanoidCrouchShrinks(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertTrue(
                        Morph.acquireTarget(player, zombie, false, true),
                        "acquiring a zombie should morph the player"))
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling boxes");
                    EntityDimensions standing =
                            player.getDimensions(Pose.STANDING);
                    EntityDimensions crouching =
                            player.getDimensions(Pose.CROUCHING);
                    helper.assertTrue(standing.height() > 1.5f,
                            "precondition: a standing zombie morph is taller than 1.5 ("
                                    + standing.height() + ")");
                    helper.assertTrue(crouching.height() < standing.height(),
                            "a crouching zombie morph must be SHORTER than standing but "
                                    + crouching.height() + " >= " + standing.height());
                    helper.assertTrue(crouching.height() <= 1.5f + 1.0e-3f,
                            "a crouching zombie morph must fit under a 1.5 gap but is "
                                    + crouching.height());
                    helper.assertTrue(
                            Math.abs(crouching.width() - standing.width()) < 1.0e-3f,
                            "the crouch keeps the morph width (" + standing.width()
                                    + ") but is " + crouching.width());
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
