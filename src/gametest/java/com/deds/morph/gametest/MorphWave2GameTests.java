package com.deds.morph.gametest;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Server gametests for Morph wave-2 "commands + config + rules" (mod id
 * {@code deds_morph}). These bind to the frozen public contract on
 * {@link Morph} / {@link MorphState} and the rule semantics of spec
 * {@code docs/specs/morph/wave2/research-config-rules-commands.md} §5.1-5.4.
 *
 * <p><b>Wave-2 variant identity migration.</b> The removeMorph / select /
 * acquire seams now take {@link MorphVariant} (entity type + normalized NBT).
 * SEEDED state uses stable {@link MorphVariant#ofType} values; tests that
 * acquire a REAL mob assert at the type level
 * ({@link MorphState#ownsType}/{@link MorphState#variantsOf} and
 * {@code current().type()}) since a spawned mob's normalized variant may carry
 * surviving additional NBT.</p>
 */
public final class MorphWave2GameTests implements CustomTestMethodInvoker {

    /** Vanilla mob type ids. */
    private static final BId PIG_ID = BId.of("minecraft", "pig");
    private static final BId COW_ID = BId.of("minecraft", "cow");
    private static final BId SHEEP_ID = BId.of("minecraft", "sheep");

    /** Stable default-variant identities for SEEDED (non-acquire) state. */
    private static final MorphVariant PIG = MorphVariant.ofType(PIG_ID);
    private static final MorphVariant COW = MorphVariant.ofType(COW_ID);

    // ------------------------------------------------------------------
    // helpers (house style, mirrored from MorphGameTests)
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

    /** True if {@code current} is present and names a variant of {@code type}. */
    private static boolean wearsType(MorphState state, BId type) {
        return state.current().isPresent()
                && state.current().get().type().equals(type);
    }

    /**
     * Repositions a mock player for a view-ray test. {@code teleportTo} routes
     * through the (fake) connection and does not reliably move a mock player,
     * so use {@code setPos} (moves the entity server-side immediately) and set
     * both current and old pos/rot so {@code getEyePosition}/{@code getViewVector}
     * are stable this tick.
     */
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

    /**
     * Points a player's look EXACTLY at an entity's bounding-box center from
     * the player's actual eye position — robust to eye height and any mock
     * placement quirk (the only requirement is the target being within reach).
     */
    private static void aimAt(ServerPlayer p, net.minecraft.world.entity.Entity target) {
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

    // ------------------------------------------------------------------
    // 1. morph/cmd/demorph
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/cmd/demorph. Morph a player via the {@code acquireTarget}
     * seam (starts a transition); assert the worn form is present; wait out
     * that transition; {@code demorph(p) == true}; after &gt;80 ticks the worn
     * form is empty (back to own form). Calling {@code demorph} on a player who
     * was never morphed returns {@code false}.
     */
    @GameTest(maxTicks = 260)
    public void demorphReturnsToOwnFormThenFailsWhenUnmorphed(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        ServerPlayer neverMorphed = mockPlayer(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 2, 1));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, pig, false, true),
                            "seam-morph via acquireTarget should succeed for a fresh pig");
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "player should be morphed (current present) right after the acquire");
                })
                // wait out the acquire transition — demorph is blocked while morphing.
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the acquire transition must be over before demorph");
                    helper.assertTrue(Morph.demorph(player),
                            "demorph must return true for a morphed, non-morphing player");
                })
                // after the demorph transition completes the worn form is the own (empty) form.
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertTrue(state(helper, player).current().isEmpty(),
                            "after demorph completes the worn form must be empty (own form) but is "
                                    + state(helper, player).current());
                    helper.assertFalse(Morph.demorph(neverMorphed),
                            "demorph on a player who was never morphed must return false");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 2. morph/cmd/clear
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/cmd/clear. A player with &gt;=2 acquired morphs (one worn) is
     * cleared; {@code STATE.get(p)} must equal {@code MorphState.EMPTY}. The
     * multi-morph precondition is seeded through the contract's
     * {@code Morph.STATE} attachment. The seed is a raw {@code STATE.set}, so it
     * starts no transition; the test asserts {@code !isMorphing} before clearing
     * to prove clear is not merely being blocked.
     */
    @GameTest(maxTicks = 60)
    public void clearWipesAllMorphsToEmpty(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(
                Optional.of(PIG), List.of(PIG, COW)));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    MorphState before = state(helper, player);
                    helper.assertTrue(before.acquired().size() >= 2 && before.current().isPresent(),
                            "precondition: player has >=2 morphs and is morphed but is " + before);
                    helper.assertFalse(Morph.isMorphing(player),
                            "a STATE.set seed must not itself start a transition (else clear is merely blocked)");
                    helper.assertTrue(Morph.clear(player),
                            "clear must return true for a non-morphing player");
                    helper.assertTrue(state(helper, player).equals(MorphState.EMPTY),
                            "clear must reset STATE to MorphState.EMPTY but is " + state(helper, player));
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 2b. morph/lifecycle — cross-world lock reset + admin unstick (CRITICAL)
    // ------------------------------------------------------------------

    /**
     * BUG 1 (cross-world permanent morph lock). The transition lock
     * ({@code Morph.transitionEnd}) must never wedge morphing: a fresh player has
     * no stale lock (morphing works immediately) and any lock a morph starts is
     * strictly time-bounded — it clears itself, so a later select proceeds.
     *
     * <p>The permanent break was a static {@code UUID→tick} map surviving a world
     * exit while a new integrated server reset {@code getTickCount()} to 0, so a
     * stale future deadline read as {@code isMorphing()==true} forever across
     * worlds. That cross-world reset is fixed by {@link Morph#resetServerState()}
     * wired to server START/STOP in {@code onInitialize} (it fires — clearing the
     * now-empty maps — at this very gametest server's startup); it is not called
     * here because it mutates JVM-global static state that the concurrently-ticking
     * tests share (a mid-transition peer would fail its {@code isMorphing} check).
     * The per-player half of the same clearing is covered by the clear/demorph
     * unstick tests below.</p>
     */
    @GameTest(maxTicks = 140)
    public void freshPlayerNoStaleLockAndLockIsBounded(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        helper.assertFalse(Morph.isMorphing(player),
                "a fresh player must have no stale transition lock");
        Morph.STATE.set(player, new MorphState(Optional.empty(), List.of(PIG)));
        Morph.select(player, Optional.of(PIG)); // morphing works on a fresh server

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(state(helper, player).current().isPresent(),
                            "morphing must work on a fresh server (select applied)");
                    helper.assertTrue(Morph.isMorphing(player),
                            "the select must start a bounded transition lock");
                })
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 20, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the transition lock must expire (never permanent)");
                    Morph.select(player, Optional.empty());
                    helper.assertTrue(state(helper, player).current().isEmpty(),
                            "after the lock clears a select must proceed again but current is "
                                    + state(helper, player).current());
                })
                .thenSucceed();
    }

    /**
     * BUG 2 (clear force-unstick). {@code Morph.clear} must reset a player even
     * when mid-transition (an admin clearing a stuck player must always work): it
     * is no longer {@code isMorphing}-gated — it wipes STATE to EMPTY, drops the
     * lock, and always returns true.
     */
    @GameTest(maxTicks = 60)
    public void clearForceResetsEvenWhileMorphing(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(Optional.of(PIG), List.of(PIG, COW)));
        Morph.select(player, Optional.of(COW)); // pig→cow: start a transition lock

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.isMorphing(player),
                            "precondition: the pig→cow switch must be mid-transition");
                    helper.assertTrue(Morph.clear(player),
                            "clear must force-reset even mid-morph and always return true");
                    helper.assertTrue(state(helper, player).equals(MorphState.EMPTY),
                            "clear must wipe STATE to EMPTY even mid-morph but is "
                                    + state(helper, player));
                    helper.assertFalse(Morph.isMorphing(player),
                            "clear must also drop the transition lock (admin unstick)");
                })
                .thenSucceed();
    }

    /**
     * BUG 2 (demorph force-unstick + not-in-morph). {@code Morph.demorph} drops any
     * stale/expired lock first so it unsticks a player who is (or appears) mid-morph
     * (returns true), yet still returns false for a genuinely-unmorphed player.
     */
    @GameTest(maxTicks = 60)
    public void demorphUnsticksLockedPlayerElseFalse(GameTestHelper helper) {
        ServerPlayer stuck = mockPlayer(helper);
        ServerPlayer clean = mockPlayer(helper);
        Morph.STATE.set(stuck, new MorphState(Optional.empty(), List.of(PIG)));
        Morph.select(stuck, Optional.of(PIG)); // start a transition lock

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.isMorphing(stuck),
                            "precondition: the stuck player is locked mid-transition");
                    helper.assertTrue(Morph.demorph(stuck),
                            "demorph must unstick a locked/mid-morph player (drop lock "
                                    + "+ demorph) and return true");
                    helper.assertFalse(Morph.demorph(clean),
                            "demorph on a never-morphed player must return false "
                                    + "(\"not in morph\")");
                })
                .thenSucceed();
    }

    /**
     * BUG 3 (rejoin hitbox). A morphed player's REAL collision box
     * ({@code getBbWidth()}, not just the {@code getDimensions} report) must adopt
     * the morph server-side from the tick alone — no crouch/pose change. This is
     * the cross-world-rejoin shape: persisted {@code STATE} carries the morph while
     * the transient ability maps are empty, so {@code MorphAbilities.commit}
     * re-applies + snaps the box on the first non-morphing tick (once BUG 1 no
     * longer wedges {@code isMorphing}). Seeded via a raw {@code STATE.set} (no
     * transition), starting from the vanilla player box.
     */
    @GameTest(maxTicks = 100)
    public void morphedPlayerBoxAdoptsServerSideWithoutCrouch(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        // A live cow supplies the reference collision box.
        LivingEntity cow = helper.spawnWithNoFreeWill(
                EntityTypes.COW, new BlockPos(3, 2, 1));
        float playerWidth = player.getBbWidth();
        Morph.STATE.set(player, new MorphState(Optional.of(COW), List.of(COW)));

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertFalse(Morph.isMorphing(player),
                        "a raw STATE.set must not start a transition"))
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(
                            Math.abs(player.getBbWidth() - cow.getBbWidth()) < 1.0e-3f,
                            "the morphed player's REAL box must adopt the cow width "
                                    + cow.getBbWidth() + " server-side (rejoin hitbox) "
                                    + "but is " + player.getBbWidth());
                    helper.assertTrue(
                            Math.abs(player.getBbWidth() - playerWidth) > 1.0e-3f,
                            "the box must no longer be the vanilla player width "
                                    + playerWidth + " (adopted without crouching)");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 3. morph/cmd/morphtarget
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/cmd/morphtarget. A living pig is placed in the player's look
     * vector within reach 4.0 (player oriented to face it); {@code morphTarget}
     * returns true; a pig-type variant is owned AND worn AND the pig entity is
     * STILL ALIVE (morphtarget does not kill/discard). Aiming a fresh player at
     * empty air returns false and does not morph it.
     */
    @GameTest(maxTicks = 100)
    public void morphTargetMorphsLookedAtLivingMobWithoutDiscarding(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        ServerPlayer other = mockPlayer(helper); // the empty-air player
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE); // player stand
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE); // pig pedestal (top y=3)
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.STONE); // empty-air player stand (off the ray)
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(3, 3, 1));

        Vec3 stand = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        // parked at z=3.5 — two blocks off the player's z=1.5 view ray, so it can
        // never be the entity the first player's morphTarget hits.
        Vec3 airStand = helper.absoluteVec(new Vec3(5.5, 2.0, 3.5));

        helper.startSequence()
                // park 'other' off the ray line, aimed straight up at empty air.
                .thenExecuteAfter(2, () -> {
                    place(other, airStand, 0f, -90f);
                })
                // let the pig settle onto the pedestal, then aim and morph in one synchronous step.
                .thenExecuteAfter(2, () -> {
                    place(player, stand, -90f, 0f);
                    aimAt(player, pig); // look dead-on at the pig from the real eye

                    helper.assertTrue(pig.isAlive(),
                            "the pig must be alive before morphTarget");
                    helper.assertTrue(Morph.morphTarget(player),
                            "morphTarget must return true with a living pig within look-reach 4.0");

                    MorphState s = state(helper, player);
                    helper.assertTrue(s.ownsType(PIG_ID),
                            "the looked-at pig must be acquired but state is " + s);
                    helper.assertTrue(wearsType(s, PIG_ID),
                            "the looked-at pig must be the worn morph but current is " + s.current());
                    helper.assertTrue(pig.isAlive() && !pig.isRemoved(),
                            "morphTarget must NOT discard the target — the pig must still be alive");
                })
                // the empty-air player: false, and no morph applied.
                .thenExecute(() -> {
                    place(other, airStand, 0f, -90f); // straight up, no entity in the column
                    helper.assertFalse(Morph.morphTarget(other),
                            "morphTarget must return false when the player aims at empty air");
                    helper.assertTrue(state(helper, other).current().isEmpty(),
                            "a failed morphTarget must leave the player un-morphed");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 4. morph/rule/transition-lock (§5.1)
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/rule/transition-lock. Acquiring a morph starts the ~80-tick
     * lock; while {@code isMorphing(p)}, {@code select(other)},
     * {@code acquireTarget(...)} and {@code removeMorph(...)} must all be
     * no-ops (select leaves the worn form, acquire returns false and adds
     * nothing, remove returns {@code MORPHING}). After &gt;80 ticks
     * {@code isMorphing(p) == false} and the same {@code removeMorph} call now
     * mutates (returns {@code REMOVED}).
     */
    @GameTest(maxTicks = 200)
    public void transitionLockBlocksMutationsUntilItClears(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        // owns COW up front (a valid select/remove target), own form worn, no transition.
        Morph.STATE.set(player, new MorphState(Optional.empty(), List.of(COW)));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 2, 1));
        LivingEntity sheep = helper.spawnWithNoFreeWill(EntityTypes.SHEEP, new BlockPos(3, 2, 1));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, pig, false, true),
                            "acquireTarget of a fresh pig should succeed and start the transition");
                    helper.assertTrue(wearsType(state(helper, player), PIG_ID),
                            "the acquire should morph the player into the pig");
                    helper.assertTrue(Morph.isMorphing(player),
                            "a committed acquire must start the ~80-tick transition lock");
                })
                // still inside the lock: every mutating seam is a no-op.
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.isMorphing(player),
                            "should still be morphing a few ticks after the acquire");
                    MorphState before = state(helper, player);

                    Morph.select(player, Optional.of(COW));
                    helper.assertTrue(wearsType(state(helper, player), PIG_ID),
                            "select(other) must NOT change the worn form while morphing");

                    helper.assertFalse(Morph.acquireTarget(player, sheep, false, true),
                            "acquireTarget must be blocked (return false) while morphing");
                    helper.assertFalse(state(helper, player).ownsType(SHEEP_ID),
                            "a blocked acquire must not add the sheep");

                    Morph.RemoveResult rr = Morph.removeMorph(player, COW);
                    helper.assertTrue(rr == Morph.RemoveResult.MORPHING,
                            "removeMorph must return MORPHING while morphing but was " + rr);

                    helper.assertTrue(state(helper, player).equals(before),
                            "no seam may mutate morph state while the lock is held (was "
                                    + before + ", now " + state(helper, player) + ")");
                })
                // after the lock clears the very same removeMorph call now mutates.
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the transition lock must clear after >80 ticks");
                    Morph.RemoveResult rr = Morph.removeMorph(player, COW);
                    helper.assertTrue(rr == Morph.RemoveResult.REMOVED,
                            "after the lock clears removeMorph of an owned non-current variant must succeed "
                                    + "(REMOVED) but was " + rr);
                    helper.assertFalse(state(helper, player).ownsType(COW_ID),
                            "the cow must be gone after the now-successful removeMorph");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 5. morph/rule/remove-current-blocked (§5.2)
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/rule/remove-current-blocked. With variant A acquired AND worn,
     * {@code removeMorph(p, A)} returns {@code IS_CURRENT} and A is still owned
     * afterwards (you cannot remove the morph you are wearing).
     */
    @GameTest(maxTicks = 60)
    public void removeCurrentWornMorphIsBlocked(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(Optional.of(PIG), List.of(PIG)));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "seed must not start a transition (else result would be MORPHING)");
                    Morph.RemoveResult rr = Morph.removeMorph(player, PIG);
                    helper.assertTrue(rr == Morph.RemoveResult.IS_CURRENT,
                            "removing the currently-worn morph must return IS_CURRENT but was " + rr);
                    helper.assertTrue(state(helper, player).owns(PIG),
                            "the worn morph must still be owned after the blocked remove");
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 6. morph/rule/remove-original-blocked (§5.2)
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/rule/remove-original-blocked. The own/original form is the
     * demorphed state ({@code current == empty}) — structurally never a
     * {@code MorphVariant} in {@code acquired}. With the own form worn and one
     * real morph acquired: {@code acquired} lists only that real variant, and
     * {@code removeMorph} of any NON-owned variant returns {@code NOT_OWNED}
     * without changing state. Since there is no variant that names the own form,
     * it cannot be removed.
     */
    @GameTest(maxTicks = 60)
    public void ownFormCannotBeRemoved(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Morph.STATE.set(player, new MorphState(Optional.empty(), List.of(PIG)));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    MorphState s = state(helper, player);
                    helper.assertTrue(s.current().isEmpty(),
                            "the own form is represented by an empty current, not a variant");
                    helper.assertTrue(s.acquired().equals(List.of(PIG)),
                            "acquired must hold only real mob variants with no synthetic own-form entry but is "
                                    + s.acquired());
                    helper.assertFalse(Morph.isMorphing(player),
                            "seed must not start a transition (else result would be MORPHING)");

                    Morph.RemoveResult rr = Morph.removeMorph(player, COW); // never acquired
                    helper.assertTrue(rr == Morph.RemoveResult.NOT_OWNED,
                            "removeMorph of a non-owned variant must return NOT_OWNED but was " + rr);

                    MorphState after = state(helper, player);
                    helper.assertTrue(after.current().isEmpty() && after.acquired().equals(List.of(PIG)),
                            "a NOT_OWNED remove must not change the state but is " + after);
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------
    // 7. morph/rule/duplicate-prevention (§5.3) — now per-variant
    // ------------------------------------------------------------------

    /**
     * §5.4 morph/rule/duplicate-prevention. Acquiring the same variant twice
     * appends it exactly once. Two identical pigs normalize to the same variant,
     * so the second acquire is gated by per-variant identity dedupe, not by the
     * lock (the two acquisitions are spaced past {@code Morph.TRANSITION_TICKS +
     * 10} so only dedupe can stop the second).
     */
    @GameTest(maxTicks = 160)
    public void acquiringSameTypeTwiceAddsNoDuplicate(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        LivingEntity pig1 = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 2, 1));
        LivingEntity pig2 = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(3, 2, 1));

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(Morph.acquireTarget(player, pig1, false, true),
                            "the first pig acquire should succeed");
                    MorphState s = state(helper, player);
                    helper.assertTrue(s.acquired().size() == 1 && s.ownsType(PIG_ID),
                            "after one pig acquire, acquired should hold exactly one pig variant but is "
                                    + s.acquired());
                })
                // let the lock clear so ONLY dedupe can stop the second same-variant acquire.
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the transition must be over before the second acquire");
                    Morph.acquireTarget(player, pig2, false, true); // duplicate variant
                })
                // "nothing new appears" cannot be waited for — sample after a short fixed delay.
                .thenExecuteAfter(5, () -> {
                    MorphState s = state(helper, player);
                    helper.assertTrue(s.variantsOf(PIG_ID).size() == 1,
                            "minecraft:pig must appear exactly once after two identical acquires but is "
                                    + s.variantsOf(PIG_ID));
                    helper.assertTrue(s.acquired().size() == 1,
                            "duplicate prevention: acquired size must stay 1 but is " + s.acquired());
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
