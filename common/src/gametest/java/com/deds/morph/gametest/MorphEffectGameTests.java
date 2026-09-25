package com.deds.morph.gametest;

import com.deds.api.event.Event;
import com.deds.api.event.ServerEvents;
import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbility;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;

/**
 * Server gametests for Morph wave-2 deliverable 5, "passive ability EFFECTS +
 * hitbox/eye/camera adoption" (feature ids {@code morph/ability/*},
 * {@code morph/hitbox/adopt}, {@code morph/api/player-tick-end}), mod id
 * {@code deds_morph}. Source of truth for WHAT to assert:
 * {@code docs/specs/morph/wave2/redesign-abilities-hitbox.md} (Part A effects +
 * Part B dimensions).
 *
 * <p><b>Written BLIND against the frozen wave-2 D5 OUTCOME contract.</b> These
 * tests bind ONLY to <i>observable player state after morphing</i> — never to
 * any implementation mechanism (a coder writes the mixins / {@code MorphAbility}
 * apply-tick-kill / {@code getDimensions} override / {@code ServerEvents
 * .PLAYER_TICK_END} in parallel). The observable seams asserted:
 * <ul>
 * <li>FIRE IMMUNITY — {@code Player.fireImmune()} flips true while morphed as a
 *     fire-immune type (blaze) and false again after demorph.</li>
 * <li>STEP — {@code getAttributeValue(Attributes.STEP_HEIGHT)} rises above the
 *     0.6 player default while morphed as a horse (1.0 step) and returns to
 *     ~0.6 after demorph.</li>
 * <li>FLY — {@code getAbilities().mayfly} becomes true while morphed as a bat
 *     and false after demorph (the mock player is non-creative).</li>
 * <li>POISON / WITHER RESISTANCE — an added {@code POISON}/{@code WITHER} effect
 *     is auto-stripped within a few ticks while morphed as a spider / zombie.</li>
 * <li>HITBOX — {@code getDimensions(Pose.STANDING)} reports the morph mob's box
 *     (cow, ~0.9×1.4), not vanilla 0.6×1.8, and reverts on demorph.</li>
 * <li>API — {@code ServerEvents.PLAYER_TICK_END} exists as
 *     {@code Event<ServerPlayer>} (a compile-time field reference).</li>
 * </ul>
 *
 * <p><b>Morph mechanic used.</b> Each test morphs by acquiring a live, spawned
 * mob via {@code Morph.acquireTarget(player, mob, false, true)}
 * ({@code discard=false} keeps the mob alive so the hitbox test can read its
 * {@code getBbWidth/Height}; {@code forced=true} bypasses gating). That is the
 * same seam the sibling wave-2 tests prove starts and completes the ~80-tick
 * transition for a mock player, so abilities are expected to apply at the
 * transition end and tick thereafter. Every effect is sampled only AFTER
 * {@code isMorphing(p) == false} plus a margin, so the ability has both applied
 * and ticked several times.</p>
 *
 * <p><b>Vanilla API (javap-verified against the extracted 26.2 server jar, never
 * from memory):</b> {@code Entity.fireImmune() : boolean};
 * {@code LivingEntity.getAttributeValue(Holder<Attribute>) : double} with
 * {@code Attributes.STEP_HEIGHT};
 * {@code Player.getAbilities() : Abilities} with public boolean field
 * {@code mayfly}; {@code LivingEntity.addEffect(MobEffectInstance) : boolean} and
 * {@code hasEffect(Holder<MobEffect>) : boolean} with {@code MobEffects.POISON}/
 * {@code WITHER} ({@code Holder<MobEffect>}) and the
 * {@code MobEffectInstance(Holder,int,int)} ctor;
 * {@code LivingEntity.getDimensions(Pose) : EntityDimensions} with
 * {@code EntityDimensions.width()/height()}; {@code Entity.getBbWidth()/
 * getBbHeight() : float}. API seam {@code com.deds.api.event.Event<T>} is a
 * public final class ({@code register(Consumer<T>)}, {@code invoke(T)}).</p>
 *
 * <p><b>RISK flagged for integration (see INTEGRATION-effects.md):</b> the
 * effect tests (fire/step/fly/poison/wither) assume the per-morphed-player
 * ability tick actually fires for a gametest MOCK player. The transition
 * completing for mock players is already proven by the sibling tests, but
 * whether the {@code PLAYER_TICK_END}-driven ability apply/tick reaches a mock
 * player is exactly the D5 uncertainty the task told me to write anyway and
 * flag. The HITBOX test is NOT subject to that risk — {@code getDimensions(Pose)}
 * is a pure read of morph state and does not need a per-player tick.</p>
 */
public final class MorphEffectGameTests implements CustomTestMethodInvoker {

    /** Float epsilon for dimension comparisons (cow 0.9×1.4 vs player 0.6×1.8
     *  are separated by >=0.3, well outside this). */
    private static final float EPS = 0.02f;

    // ------------------------------------------------------------------
    // helpers (house style, mirrored from the sibling morph gametests)
    // ------------------------------------------------------------------

    /** Registered mock server player, de-registered before the test ends. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runBeforeTestEnd(() ->
                helper.getLevel().getServer().getPlayerList().remove(player));
        return player;
    }

    private static MorphState state(GameTestHelper helper, ServerPlayer player) {
        MorphState s = Morph.STATE.get(player);
        if (s == null) {
            helper.fail("Morph.STATE.get must never return null "
                    + "(PlayerDataKey contract: the default fills in)");
        }
        return s;
    }

    /** Spawns a free-will-less mob on a fresh stone pedestal at (x,2,z). */
    private static <E extends Mob> E spawn(GameTestHelper helper,
            EntityType<E> type, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(type, new BlockPos(x, 2, z));
    }

    /** Ticks waited AFTER an acquire/demorph so the transition has fully ended
     *  ({@code isMorphing} false) AND the just-applied ability has ticked a few
     *  times. The transition is the frozen {@code TRANSITION_TICKS} long. */
    private static int settle() {
        return Morph.TRANSITION_TICKS + 20;
    }

    // ==================================================================
    // 1. morph/ability/fire-immunity
    // ==================================================================

    /**
     * {@code morph/ability/fire-immunity}. A fresh player is NOT fire immune;
     * morphing as a {@code blaze} (a fire-immune {@link EntityType}) makes
     * {@code player.fireImmune() == true}; demorphing and waiting out that
     * transition returns {@code fireImmune() == false}.
     */
    @GameTest(maxTicks = 260)
    public void fireImmunity(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity blaze = spawn(helper, EntityTypes.BLAZE, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(player.fireImmune(),
                            "baseline: an un-morphed player must NOT be fire immune");
                    helper.assertTrue(Morph.acquireTarget(player, blaze, false, true),
                            "acquiring a fresh blaze should succeed and start the transition");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "the player should be morphed (current present) right after the acquire");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling the effect");
                    helper.assertTrue(player.fireImmune(),
                            "morphed as a blaze the player MUST be fire immune but fireImmune()==false");
                    helper.assertTrue(Morph.demorph(player),
                            "demorph must return true for a morphed, non-morphing player");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the demorph transition must be over before sampling the effect");
                    helper.assertFalse(player.fireImmune(),
                            "after demorph the fire immunity must be gone but fireImmune()==true");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 2. morph/ability/step
    // ==================================================================

    /**
     * {@code morph/ability/step}. An un-morphed player's
     * {@code STEP_HEIGHT} attribute is the ~0.6 default; morphing as a
     * {@code horse} (1.0 step) raises {@code getAttributeValue(STEP_HEIGHT) > 0.6};
     * after demorph it returns to ~0.6 ({@code <= 0.6001}).
     */
    @GameTest(maxTicks = 260)
    public void step(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity horse = spawn(helper, EntityTypes.HORSE, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(
                            player.getAttributeValue(Attributes.STEP_HEIGHT) <= 0.6001,
                            "baseline: an un-morphed player's STEP_HEIGHT must be ~0.6 but is "
                                    + player.getAttributeValue(Attributes.STEP_HEIGHT));
                    helper.assertTrue(Morph.acquireTarget(player, horse, false, true),
                            "acquiring a fresh horse should succeed and start the transition");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "the player should be morphed right after the acquire");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling the effect");
                    double sh = player.getAttributeValue(Attributes.STEP_HEIGHT);
                    helper.assertTrue(sh > 0.6,
                            "morphed as a horse the player's STEP_HEIGHT must exceed 0.6 but is " + sh);
                    helper.assertTrue(Morph.demorph(player),
                            "demorph must return true for a morphed, non-morphing player");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the demorph transition must be over before sampling the effect");
                    double sh = player.getAttributeValue(Attributes.STEP_HEIGHT);
                    helper.assertTrue(sh <= 0.6001,
                            "after demorph STEP_HEIGHT must return to ~0.6 (<=0.6001) but is " + sh);
                })
                .thenSucceed();
    }

    /** Step height belongs to the FORM: camel (1.5) to horse (1.0) kept 1.5,
     *  and adult to baby kept the adult's step, because STEP never left the
     *  ability set so it was never re-applied. */
    @GameTest(maxTicks = 80)
    public void stepFollowsTheFormBetweenTwoSteppers(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        com.deds.morph.MorphVariant camel = com.deds.morph.MorphVariant.ofType(
                com.deds.api.id.BId.of("minecraft", "camel"));
        com.deds.morph.MorphVariant horse = com.deds.morph.MorphVariant.ofType(
                com.deds.api.id.BId.of("minecraft", "horse"));
        Mob foal = spawn(helper, EntityTypes.HORSE, 1, 1);
        foal.setBaby(true);
        com.deds.morph.MorphVariant babyHorse = Morph.variantOf(foal);
        java.util.List<com.deds.morph.MorphVariant> all = java.util.List.of(camel, horse, babyHorse);
        helper.startSequence()
                .thenExecuteAfter(1, () -> Morph.STATE.set(player,
                        new MorphState(java.util.Optional.of(camel), all)))
                .thenExecuteAfter(4, () -> {
                    double sh = player.getAttributeValue(Attributes.STEP_HEIGHT);
                    helper.assertTrue(Math.abs(sh - 1.5) < 1e-3, "as a camel STEP_HEIGHT must be 1.5 but is " + sh);
                    Morph.STATE.set(player, new MorphState(java.util.Optional.of(horse), all));
                })
                .thenExecuteAfter(4, () -> {
                    double sh = player.getAttributeValue(Attributes.STEP_HEIGHT);
                    helper.assertTrue(Math.abs(sh - 1.0) < 1e-3, "camel to horse must give 1.0, not keep 1.5 - is " + sh);
                    Morph.STATE.set(player, new MorphState(java.util.Optional.of(babyHorse), all));
                })
                .thenExecuteAfter(4, () -> {
                    double sh = player.getAttributeValue(Attributes.STEP_HEIGHT);
                    helper.assertTrue(sh <= 0.6001, "a baby horse gets no step (~0.6) but is " + sh);
                })
                .thenSucceed();
    }

    // ==================================================================
    // 3. morph/ability/fly
    // ==================================================================

    /**
     * {@code morph/ability/fly}. A non-creative player cannot fly
     * ({@code getAbilities().mayfly == false}); morphing as a {@code bat} sets
     * {@code mayfly == true}; after demorph (still non-creative) it returns to
     * {@code false}. The baseline assertion also pins the "not creative"
     * precondition — a creative mock would already have {@code mayfly == true}.
     */
    @GameTest(maxTicks = 260)
    public void fly(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity bat = spawn(helper, EntityTypes.BAT, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    // A gametest mock ServerPlayer defaults to a fly-capable game
                    // mode; force a deterministic non-creative baseline by setting
                    // the ability fields directly (no onUpdateAbilities packet, so a
                    // mock's fake connection can't throw). The fire-immunity test's
                    // baseline passing on an equally-fresh player proves abilities do
                    // NOT leak to unmorphed players — this only pins the game mode.
                    player.getAbilities().instabuild = false;
                    player.getAbilities().mayfly = false;
                    player.getAbilities().flying = false;
                    helper.assertFalse(player.getAbilities().mayfly,
                            "baseline: a non-creative player must NOT be able to fly");
                    helper.assertFalse(player.getAbilities().instabuild,
                            "baseline: the player must not be in creative (instabuild) "
                                    + "or the fly/demorph teardown could not be observed");
                    helper.assertTrue(Morph.acquireTarget(player, bat, false, true),
                            "acquiring a fresh bat should succeed and start the transition");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "the player should be morphed right after the acquire");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling the effect");
                    helper.assertTrue(player.getAbilities().mayfly,
                            "morphed as a bat the player MUST be allowed to fly (mayfly==true)");
                    helper.assertTrue(Morph.demorph(player),
                            "demorph must return true for a morphed, non-morphing player");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the demorph transition must be over before sampling the effect");
                    helper.assertFalse(player.getAbilities().mayfly,
                            "after demorph a non-creative player must lose flight (mayfly==false)");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 4. morph/ability/poison-resistance
    // ==================================================================

    /**
     * {@code morph/ability/poison-resistance}. Morphed as a {@code spider}, a
     * {@code POISON} effect added to the player is auto-stripped within a few
     * ticks: {@code addEffect} is accepted and the effect is briefly present,
     * then {@code hasEffect(POISON) == false} after the ability ticks. A 200-tick
     * duration rules out a false pass via natural expiry.
     */
    @GameTest(maxTicks = 200)
    public void poisonResistance(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity spider = spawn(helper, EntityTypes.SPIDER, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, spider, false, true),
                            "acquiring a fresh spider should succeed and start the transition");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "the player should be morphed right after the acquire");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before adding the effect");
                    boolean added = player.addEffect(
                            new MobEffectInstance(MobEffects.POISON, 200, 0));
                    helper.assertTrue(added,
                            "the POISON effect must be accepted onto the player "
                                    + "(the ability strips it per tick, it does not block the add)");
                    helper.assertTrue(player.hasEffect(MobEffects.POISON),
                            "the just-added POISON effect must be present on the same tick "
                                    + "(nothing has ticked yet)");
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertFalse(player.hasEffect(MobEffects.POISON),
                            "morphed as a spider the player's POISON must be auto-removed "
                                    + "within a few ticks but hasEffect(POISON)==true");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 5. morph/ability/wither-resistance
    // ==================================================================

    /**
     * {@code morph/ability/wither-resistance}. Morphed as a {@code zombie}
     * (undead), a {@code WITHER} effect added to the player is auto-stripped
     * within a few ticks. A 200-tick duration rules out a false pass via natural
     * expiry.
     */
    @GameTest(maxTicks = 200)
    public void witherResistance(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, zombie, false, true),
                            "acquiring a fresh zombie should succeed and start the transition");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "the player should be morphed right after the acquire");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before adding the effect");
                    boolean added = player.addEffect(
                            new MobEffectInstance(MobEffects.WITHER, 200, 0));
                    helper.assertTrue(added,
                            "the WITHER effect must be accepted onto the player "
                                    + "(the ability strips it per tick, it does not block the add)");
                    helper.assertTrue(player.hasEffect(MobEffects.WITHER),
                            "the just-added WITHER effect must be present on the same tick "
                                    + "(nothing has ticked yet)");
                })
                .thenExecuteAfter(10, () -> {
                    helper.assertFalse(player.hasEffect(MobEffects.WITHER),
                            "morphed as a zombie the player's WITHER must be auto-removed "
                                    + "within a few ticks but hasEffect(WITHER)==true");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 6. morph/hitbox/adopt
    // ==================================================================

    /**
     * {@code morph/hitbox/adopt}. Morphing as a {@code cow} makes the player
     * report the COW's collision box through {@code getDimensions(Pose.STANDING)}
     * — width/height match the live cow's {@code getBbWidth()/getBbHeight()}
     * (within {@link #EPS}), and differ from a fresh un-morphed player's vanilla
     * 0.6×1.8 box (cow is ~0.9×1.4). After demorph the box reverts to the
     * un-morphed player's dimensions.
     *
     * <p>Unlike the per-tick ability effects, this binds to a pure read of morph
     * state ({@code getDimensions(Pose)}) and does not depend on a per-player
     * ability tick — only on the transition completing (already proven for mock
     * players by the sibling wave-2 tests).</p>
     */
    @GameTest(maxTicks = 260)
    public void hitboxAdopt(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        ServerPlayer unmorphed = mockPlayer(helper); // live 0.6×1.8 reference
        LivingEntity cow = spawn(helper, EntityTypes.COW, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    EntityDimensions ref = unmorphed.getDimensions(Pose.STANDING);
                    EntityDimensions me = player.getDimensions(Pose.STANDING);
                    helper.assertTrue(
                            Math.abs(me.width() - ref.width()) < EPS
                                    && Math.abs(me.height() - ref.height()) < EPS,
                            "baseline: an un-morphed player must share the vanilla box "
                                    + ref.width() + "x" + ref.height() + " but is "
                                    + me.width() + "x" + me.height());
                    // sanity: the cow really is a differently-sized box than the player.
                    helper.assertTrue(
                            Math.abs(cow.getBbWidth() - ref.width()) > EPS
                                    || Math.abs(cow.getBbHeight() - ref.height()) > EPS,
                            "sanity: the cow box " + cow.getBbWidth() + "x" + cow.getBbHeight()
                                    + " must differ from the vanilla player box");
                    helper.assertTrue(Morph.acquireTarget(player, cow, false, true),
                            "acquiring a fresh cow should succeed and start the transition");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "the player should be morphed right after the acquire");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling the box");
                    EntityDimensions me = player.getDimensions(Pose.STANDING);
                    EntityDimensions ref = unmorphed.getDimensions(Pose.STANDING);

                    // matches the morph mob's box ...
                    helper.assertTrue(Math.abs(me.width() - cow.getBbWidth()) < EPS,
                            "morphed as a cow the player width must match the cow ("
                                    + cow.getBbWidth() + ") but is " + me.width());
                    helper.assertTrue(Math.abs(me.height() - cow.getBbHeight()) < EPS,
                            "morphed as a cow the player height must match the cow ("
                                    + cow.getBbHeight() + ") but is " + me.height());
                    // ... and no longer the vanilla player box.
                    helper.assertTrue(
                            Math.abs(me.width() - ref.width()) > EPS
                                    || Math.abs(me.height() - ref.height()) > EPS,
                            "morphed dimensions " + me.width() + "x" + me.height()
                                    + " must DIFFER from a fresh player's "
                                    + ref.width() + "x" + ref.height());

                    helper.assertTrue(Morph.demorph(player),
                            "demorph must return true for a morphed, non-morphing player");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the demorph transition must be over before sampling the box");
                    EntityDimensions me = player.getDimensions(Pose.STANDING);
                    EntityDimensions ref = unmorphed.getDimensions(Pose.STANDING);
                    helper.assertTrue(
                            Math.abs(me.width() - ref.width()) < EPS
                                    && Math.abs(me.height() - ref.height()) < EPS,
                            "after demorph the box must revert to the vanilla player "
                                    + ref.width() + "x" + ref.height() + " but is "
                                    + me.width() + "x" + me.height());
                })
                .thenSucceed();
    }

    // ==================================================================
    // 7. morph/api/player-tick-end
    // ==================================================================

    /**
     * {@code morph/api/player-tick-end}. Compile-time reference to the new D5 API
     * surface: {@code com.deds.api.event.ServerEvents.PLAYER_TICK_END} must exist
     * and be typed {@code Event<ServerPlayer>} (the per-morphed-player server
     * post-tick hook the abilities are wired onto). The strongly-typed local is
     * the whole point — if the field is absent or mis-typed the build fails to
     * compile; the runtime body is a trivial non-null check.
     */
    @GameTest(maxTicks = 10)
    public void apiPlayerTickEndExists(GameTestHelper helper) {
        Event<ServerPlayer> playerTickEnd = ServerEvents.PLAYER_TICK_END;
        helper.assertTrue(playerTickEnd != null,
                "ServerEvents.PLAYER_TICK_END must exist as a non-null Event<ServerPlayer>");
        helper.succeed();
    }

    // 8. morph/ability/float — NOTE: the float slow-fall is now a CLIENT-side
    // clamp (MorphAbilitiesClient) at the mob's exact terminal velocity, because
    // player movement is client-authoritative. It is therefore playtest-only and
    // NOT server-gametestable (a headless server cannot observe the local client's
    // motion). The previous SLOW_FALLING-effect approach was removed (it gave the
    // wrong/inconsistent fall speed), so there is no longer a server-observable
    // float assertion here.

    // ==================================================================
    // 9. morph/ability/swim — strictly-aquatic land suffocation (playtest bug fix)
    // ==================================================================

    /**
     * {@code morph/ability/swim} land half. A strictly-aquatic morph
     * ({@code cod} — a fish) suffocates on land like a fish out of water: its air
     * supply drains below the full player maximum over a few ticks (so the vanilla
     * air bubbles empty). The in-water infinite-oxygen half is unchanged.
     */
    @GameTest(maxTicks = 260)
    public void strictlyAquaticLosesAirOnLand(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity cod = spawn(helper, EntityTypes.COD, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, cod, false, true),
                            "acquiring a fresh cod should succeed and start the transition");
                })
                .thenExecuteAfter(settle() + 40, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling air");
                    helper.assertFalse(player.isInWater(),
                            "precondition: the test player must be on land, not in water");
                    helper.assertTrue(
                            player.getAirSupply() < player.getMaxAirSupply(),
                            "a cod-morphed player on land must lose air (fish out of water) "
                                    + "but air is " + player.getAirSupply() + "/"
                                    + player.getMaxAirSupply());
                })
                .thenSucceed();
    }

    // ==================================================================
    // 10. morph/ability/hostile — hostile mob ignores a hostile morph (bug fix)
    // ==================================================================

    /**
     * {@code morph/ability/hostile}. With the default {@code hostileAbilityMode},
     * a hostile mob may NOT hold a player morphed as a hostile mob as its target:
     * {@code setTarget(morphedPlayer)} is cancelled ({@code getTarget()} stays
     * null). An un-morphed player is still a valid target (control), proving the
     * cancel is specific to the hostile morph.
     */
    @GameTest(maxTicks = 260)
    public void hostileMobIgnoresHostileMorph(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 1, 1);
        Mob attacker = spawn(helper, EntityTypes.ZOMBIE, 3, 1);
        LivingEntity cow = spawn(helper, EntityTypes.COW, 5, 1); // control target

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, zombie, false, true),
                            "acquiring a fresh zombie should succeed and start the transition");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before testing targeting");

                    // getTargetUnchecked() reads the raw target field so it isolates
                    // exactly what the setTarget hook does. A hostile mob targeting
                    // the zombie-morphed player is cancelled (field never set).
                    attacker.setTarget(player);
                    helper.assertTrue(attacker.getTargetUnchecked() == null,
                            "a hostile mob must NOT hold a zombie-morphed player as its target "
                                    + "(default hostileAbilityMode) but target is "
                                    + attacker.getTargetUnchecked());

                    // Control: a normal (non-morph) target still lands — the hook
                    // is specific to hostile-morphed players, not all targeting.
                    attacker.setTarget(cow);
                    helper.assertTrue(attacker.getTargetUnchecked() == cow,
                            "a hostile mob's setTarget must still land for a normal target "
                                    + "but target is " + attacker.getTargetUnchecked());
                })
                .thenSucceed();
    }

    // ==================================================================
    // 11. morph/ability/swim — derivation excludes land undead (bug fix)
    // ==================================================================

    /**
     * {@code morph/ability/swim} derivation. A plain {@code zombie} must NOT
     * derive {@code SWIM} (it would otherwise get dolphin's-grace in water) —
     * the {@code can_breathe_under_water} tag contains all undead, so SWIM keys
     * off the {@code aquatic} tag / fish-squid classes / drowned instead. A
     * {@code drowned} (water-dwelling undead) and a {@code squid} DO derive SWIM.
     */
    @GameTest(maxTicks = 60)
    public void swimDerivationExcludesLandUndead(GameTestHelper helper) {
        LivingEntity zombie = spawn(helper, EntityTypes.ZOMBIE, 1, 1);
        LivingEntity drowned = spawn(helper, EntityTypes.DROWNED, 3, 1);
        LivingEntity squid = spawn(helper, EntityTypes.SQUID, 5, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(
                            MorphAbility.deriveAbilities(zombie)
                                    .contains(MorphAbility.SWIM),
                            "a plain zombie must NOT derive SWIM but did: "
                                    + MorphAbility.deriveAbilities(zombie));
                    helper.assertTrue(
                            MorphAbility.deriveAbilities(drowned)
                                    .contains(MorphAbility.SWIM),
                            "a drowned (water undead) must derive SWIM but did not: "
                                    + MorphAbility.deriveAbilities(drowned));
                    helper.assertTrue(
                            MorphAbility.deriveAbilities(squid)
                                    .contains(MorphAbility.SWIM),
                            "a squid must derive SWIM but did not: "
                                    + MorphAbility.deriveAbilities(squid));
                })
                .thenSucceed();
    }

    // ==================================================================
    // 12. morph/ability/swim — SWIM morph breathes underwater (no bubbles)
    // ==================================================================

    /**
     * {@code morph/ability/swim} breathing half. While morphed as a SWIM mob
     * ({@code cod}) the player reports {@code canBreatheUnderwater() == true} —
     * the exact hook vanilla {@code baseTick} consults to decide air loss, so a
     * fish morph never loses air (and the bubble HUD never shows) underwater. A
     * fresh player is {@code false}; after demorph it returns to {@code false}.
     */
    @GameTest(maxTicks = 260)
    public void swimMorphBreathesUnderwater(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        LivingEntity cod = spawn(helper, EntityTypes.COD, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(player.canBreatheUnderwater(),
                            "baseline: an un-morphed player must not breathe underwater");
                    helper.assertTrue(Morph.acquireTarget(player, cod, false, true),
                            "acquiring a fresh cod should succeed and start the transition");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before sampling breathing");
                    helper.assertTrue(player.canBreatheUnderwater(),
                            "morphed as a cod the player MUST breathe underwater (no air loss / "
                                    + "no bubbles) but canBreatheUnderwater()==false");
                    helper.assertTrue(Morph.demorph(player),
                            "demorph must return true for a morphed, non-morphing player");
                })
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the demorph transition must be over before sampling breathing");
                    helper.assertFalse(player.canBreatheUnderwater(),
                            "after demorph the player must no longer breathe underwater");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 13. morph/ability/fly — flight derived for ALL flyers (behavior wave)
    // ==================================================================

    /** Builds a never-spawned dummy of {@code minecraft:<path>} via the shared
     *  builder (no world placement, so mob size never matters). */
    private static LivingEntity dummy(GameTestHelper helper, String path) {
        LivingEntity d = MorphEntities.create(
                MorphVariant.ofType(BId.of("minecraft", path)), helper.getLevel());
        helper.assertTrue(d != null, "MorphEntities.create must build a " + path);
        return d;
    }

    /**
     * {@code morph/ability/fly} derivation. Every flyer derives {@code FLY}: vex
     * (restored constructor no-gravity), phantom (explicit set), ghast/happy_ghast
     * (FLYING_SPEED attribute), allay/bee/parrot (FlyingMoveControl+nav), bat
     * (explicit set). This is the reported "vex/phantom can't fly" fix.
     */
    @GameTest(maxTicks = 40)
    public void flightDerivedForAllFlyers(GameTestHelper helper) {
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    for (String flyer : new String[]{"vex", "phantom", "ghast",
                            "happy_ghast", "allay", "bee", "parrot", "bat"}) {
                        LivingEntity d = dummy(helper, flyer);
                        helper.assertTrue(
                                MorphAbility.deriveAbilities(d)
                                        .contains(MorphAbility.FLY),
                                flyer + " must derive FLY but derived "
                                        + MorphAbility.deriveAbilities(d));
                    }
                    // control: a pig is not a flyer.
                    helper.assertFalse(
                            MorphAbility.deriveAbilities(dummy(helper, "pig"))
                                    .contains(MorphAbility.FLY),
                            "a pig must NOT derive FLY");
                })
                .thenSucceed();
    }

    // 14. morph/render/vex-no-gravity — REMOVED. The spec assumed Vex.<init>
    // calls setNoGravity(true); javap of the 26.2 jar proved it does NOT (the
    // constructor only installs VexMoveControl, and createAttributes sets no
    // GRAVITY). So a vex dummy's isNoGravity() is false and Vex flight cannot come
    // from that flag — Vex is handled by the explicit flyer set instead (asserted
    // by flightDerivedForAllFlyers). The MorphEntities no-gravity restore remains
    // a defensive generic net for modded constructor-noGravity flyers, but no
    // vanilla mob exercises it, so there is no server-observable assertion for it.

    // ==================================================================
    // 15. morph/ability/strider-fire-immune — open-question verification
    // ==================================================================

    /**
     * {@code morph/ability/fire-immunity} for strider (spec open question). A
     * strider has no {@code fireImmune()} override — its immunity comes from the
     * {@code EntityType.STRIDER} builder flag. Verify {@code type.fireImmune()} →
     * {@code FIRE_IMMUNITY} is derived at runtime (so a strider morph does not burn
     * / walks lava unharmed); if this fails, strider needs explicit fire handling.
     */
    @GameTest(maxTicks = 20)
    public void striderDerivesFireImmunity(GameTestHelper helper) {
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    LivingEntity strider = dummy(helper, "strider");
                    helper.assertTrue(
                            MorphAbility.deriveAbilities(strider)
                                    .contains(MorphAbility.FIRE_IMMUNITY),
                            "a strider must derive FIRE_IMMUNITY (type.fireImmune()) "
                                    + "so it does not burn, but derived "
                                    + MorphAbility.deriveAbilities(strider));
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
