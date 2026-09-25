package com.deds.morph.gametest;

import com.deds.api.event.InteractionEvents;
import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphInteractionEffects;
import com.deds.morph.MorphInteractions;
import com.deds.morph.MorphRideable;
import com.deds.morph.MorphSandbox;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Server gametests for Morph wave-3 behavior wave 2 (interactions + mounting,
 * feature ids {@code morph/interact/*}, {@code morph/ride/*}), mod id
 * {@code deds_morph}. Source of truth: {@code
 * docs/specs/morph/wave3/behavior-interactions.md} §9.
 *
 * <p>Production tests call {@link MorphInteractionEffects#run} directly with a
 * built variant (the victim is only a position/UUID anchor — {@code run} does not
 * consult the committed morph), so no ~80-tick morph transition is needed; the
 * router + committed-variant gating is covered end-to-end by
 * {@link #routerMilksMorphedVictim} and the ride-offset mixin by
 * {@link #rideSeatAtMorphOffset}, which DO morph a victim.</p>
 */
public final class MorphInteractionGameTests implements CustomTestMethodInvoker {

    /** Registered mock server player, de-registered before the test ends. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runBeforeTestEnd(() ->
                helper.getLevel().getServer().getPlayerList().remove(player));
        return player;
    }

    /** Spawns a free-will-less mob on a fresh stone pedestal at (x,2,z). */
    private static <E extends Mob> E spawn(GameTestHelper helper,
            EntityType<E> type, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(type, new BlockPos(x, 2, z));
    }

    private static int settle() {
        return Morph.TRANSITION_TICKS + 20;
    }

    private static MorphVariant variant(String path) {
        return MorphVariant.ofType(BId.of("minecraft", path));
    }

    private static int countNear(GameTestHelper helper, ServerPlayer anchor,
            Class<? extends net.minecraft.world.entity.Entity> type) {
        return helper.getLevel().getEntitiesOfClass(type,
                anchor.getBoundingBox().inflate(6.0)).size();
    }

    /** Runs a production interaction directly (no morph needed — the variant is
     *  passed): interactor holds {@code item}, victim is the drop/position anchor. */
    private static InteractionResult runProduction(ServerPlayer victim,
            ServerPlayer interactor, String mobPath,
            net.minecraft.world.item.Item item) {
        interactor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        return MorphInteractionEffects.run(victim, variant(mobPath), interactor,
                InteractionHand.MAIN_HAND);
    }

    // ==================================================================
    // morph/interact/milk-cow
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void milkCow(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    InteractionResult r =
                            runProduction(victim, interactor, "cow", Items.BUCKET);
                    helper.assertTrue(r.consumesAction(),
                            "milking a cow-morph with an empty bucket must consume the "
                                    + "action but was " + r);
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/interact/stew-mooshroom
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void stewMooshroom(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    InteractionResult r = runProduction(victim, interactor,
                            "mooshroom", Items.BOWL);
                    helper.assertTrue(r.consumesAction(),
                            "bowl on a mooshroom-morph must yield stew (consume) but was "
                                    + r);
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/interact/shear-sheep-drops
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void shearSheepDrops(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    // Anchor the victim (and thus the drop position) in a loaded
                    // test chunk so spawnAtLocation's ItemEntities are counted.
                    victim.snapTo(helper.absoluteVec(new Vec3(2.0, 2.0, 2.0)));
                    int before = countNear(helper, victim, ItemEntity.class);
                    InteractionResult r = runProduction(victim, interactor,
                            "sheep", Items.SHEARS);
                    helper.assertTrue(r.consumesAction(),
                            "shearing a sheep-morph must consume the action but was " + r);
                    helper.assertTrue(countNear(helper, victim, ItemEntity.class)
                                    > before,
                            "shearing a sheep-morph must drop wool item entities");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/interact/shear-mooshroom-no-transform
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void shearMooshroomNoTransform(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    victim.snapTo(helper.absoluteVec(new Vec3(2.0, 2.0, 2.0)));
                    int cowsBefore = countNear(helper, victim, Cow.class);
                    int itemsBefore = countNear(helper, victim, ItemEntity.class);
                    InteractionResult r = runProduction(victim, interactor,
                            "mooshroom", Items.SHEARS);
                    helper.assertTrue(r.consumesAction(),
                            "shearing a mooshroom-morph must consume the action but was "
                                    + r);
                    helper.assertTrue(countNear(helper, victim, Cow.class)
                                    == cowsBefore,
                            "shearing a mooshroom-morph must NOT spawn a Cow (sandbox "
                                    + "suppresses convertTo) — the player stays a mooshroom");
                    helper.assertTrue(countNear(helper, victim, ItemEntity.class)
                                    > itemsBefore,
                            "shearing a mooshroom-morph must still drop mushrooms");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/interact/shear-sandbox-suppresses-mobspawn
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void sandboxSuppressesMobSpawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.LOAD);
                    helper.assertTrue(cow != null, "the control cow must build");
                    cow.snapTo(at.x, at.y, at.z);
                    ItemEntity item = new ItemEntity(level, at.x, at.y, at.z,
                            new ItemStack(Items.STICK));
                    boolean cowAdded;
                    boolean itemAdded;
                    MorphSandbox.begin();
                    try {
                        cowAdded = level.addFreshEntity(cow);
                        itemAdded = level.addFreshEntity(item);
                    } finally {
                        MorphSandbox.end();
                    }
                    helper.assertFalse(cowAdded,
                            "the sandbox must suppress a non-item entity (Cow) spawn");
                    helper.assertTrue(itemAdded,
                            "the sandbox must allow an ItemEntity spawn (drops pass)");
                    item.discard();
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/interact/nonwhitelist-item-passes
    // ==================================================================

    @GameTest(maxTicks = 20)
    public void nonWhitelistItemPasses(GameTestHelper helper) {
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertFalse(MorphInteractionEffects.isProductionItem(
                                    new ItemStack(Items.SADDLE)),
                            "a saddle must NOT be a production item (else it would be "
                                    + "consumed onto the transient dummy)");
                    helper.assertTrue(MorphInteractionEffects.isProductionItem(
                                    new ItemStack(Items.BUCKET)),
                            "an empty bucket must be a production item");
                    helper.assertFalse(MorphInteractionEffects.isProductionItem(
                                    ItemStack.EMPTY),
                            "an empty hand is not a production item");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/ride/mount-horse-morph + passenger-does-not-control
    // ==================================================================

    @GameTest(maxTicks = 40)
    public void mountAndNoControl(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer rider = mockPlayer(helper);
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    InteractionResult r = MorphRideable.mount(rider, victim);
                    helper.assertTrue(r.consumesAction(),
                            "mounting a rideable morph must succeed but was " + r);
                    helper.assertTrue(rider.isPassenger(),
                            "the rider must become a passenger");
                    helper.assertTrue(victim.isVehicle(),
                            "the morphed player must become a vehicle");
                    helper.assertTrue(victim.getFirstPassenger() == rider,
                            "the rider must be the morphed player's passenger");
                    helper.assertTrue(victim.getControllingPassenger() == null,
                            "a mounted passenger must NOT control the morphed player "
                                    + "(getControllingPassenger must be null)");
                    rider.stopRiding(); // cleanup before test end
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/ride/mount-requires-rideable-type + happy-ghast-fallback
    // ==================================================================

    @GameTest(maxTicks = 20)
    public void isMountableByType(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(MorphRideable.isMountable(variant("horse"), level),
                            "a horse morph must be mountable (CAN_EQUIP_SADDLE)");
                    helper.assertTrue(MorphRideable.isMountable(variant("pig"), level),
                            "a pig morph must be mountable (CAN_EQUIP_SADDLE)");
                    helper.assertTrue(
                            MorphRideable.isMountable(variant("happy_ghast"), level),
                            "a happy_ghast morph must be mountable (explicit fallback)");
                    helper.assertFalse(MorphRideable.isMountable(variant("cow"), level),
                            "a cow morph must NOT be mountable");
                    helper.assertFalse(MorphRideable.isMountable(variant("llama"), level),
                            "a llama morph must NOT be mountable (excluded)");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/ride/seat-at-morph-offset
    // ==================================================================

    /**
     * A horse-morph vehicle seats a passenger at the morph's PASSENGER offset —
     * the per-player-position offset differs from a vanilla (un-morphed) player's
     * seat offset, proving {@code LivingEntityRidingPositionMixin} fired.
     */
    @GameTest(maxTicks = 220)
    public void rideSeatAtMorphOffset(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer rider = mockPlayer(helper);
        ServerPlayer unmorphed = mockPlayer(helper); // control vehicle
        LivingEntity horse = spawn(helper, EntityTypes.HORSE, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertTrue(
                        Morph.acquireTarget(victim, horse, false, true),
                        "acquiring a horse should succeed"))
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(victim),
                            "the acquire transition must be over");
                    Vec3 morphedOff = victim.getPassengerRidingPosition(rider)
                            .subtract(victim.position());
                    Vec3 vanillaOff = unmorphed.getPassengerRidingPosition(rider)
                            .subtract(unmorphed.position());
                    helper.assertTrue(morphedOff.distanceToSqr(vanillaOff) > 0.01,
                            "a horse-morph vehicle must seat the rider at the morph's "
                                    + "PASSENGER offset, not the vanilla player point "
                                    + "(morphed=" + morphedOff + " vanilla=" + vanillaOff
                                    + ")");
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/interact/router (end-to-end: committed-variant gating + routing)
    // ==================================================================

    @GameTest(maxTicks = 160)
    public void routerMilksMorphedVictim(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        LivingEntity cow = spawn(helper, EntityTypes.COW, 1, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertTrue(
                        Morph.acquireTarget(victim, cow, false, true),
                        "acquiring a cow should succeed"))
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(victim),
                            "the acquire transition must be over");
                    interactor.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(Items.BUCKET));
                    InteractionResult milk = MorphInteractions.onUseEntity(
                            new InteractionEvents.UseEntity(interactor,
                                    victim.level(), InteractionHand.MAIN_HAND,
                                    victim, null));
                    helper.assertTrue(milk.consumesAction(),
                            "the router must milk a cow-morphed victim for a bucket-"
                                    + "holding interactor but was " + milk);

                    // empty hand on a non-rideable (cow) morph → PASS (not mountable)
                    interactor.setItemInHand(InteractionHand.MAIN_HAND,
                            ItemStack.EMPTY);
                    InteractionResult empty = MorphInteractions.onUseEntity(
                            new InteractionEvents.UseEntity(interactor,
                                    victim.level(), InteractionHand.MAIN_HAND,
                                    victim, null));
                    helper.assertTrue(empty == InteractionResult.PASS,
                            "empty-hand on a non-rideable (cow) morph must PASS but was "
                                    + empty);
                })
                .thenSucceed();
    }

    // ==================================================================
    // morph/ride/mount-happy-ghast-morph (bug fix: harness-rideable + multi-seat)
    // ==================================================================

    /**
     * A happy-ghast morph carries a rider seated ON TOP, and (unlike a horse) more
     * than one — happy ghast exposes multiple PASSENGER seats. Also asserts
     * mountability is decided by TYPE (no dummy built): the previous order probed a
     * dummy for happy ghast on every interaction (incl. the client prediction),
     * which made happy-ghast mounting unreliable while horses (saddle tag) worked.
     */
    @GameTest(maxTicks = 160)
    public void mountHappyGhastMorphMultiRider(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer rider1 = mockPlayer(helper);
        ServerPlayer rider2 = mockPlayer(helper);
        MorphVariant hg = variant("happy_ghast");
        Morph.STATE.set(victim, new MorphState(java.util.Optional.of(hg),
                java.util.List.of(hg)));
        helper.startSequence()
                .thenExecuteAfter(settle(), () -> {
                    helper.assertFalse(Morph.isMorphing(victim),
                            "the morph transition must be over before mounting");
                    helper.assertTrue(
                            MorphRideable.isMountable(hg, victim.level()),
                            "a happy-ghast morph must be mountable");
                    helper.assertTrue(MorphEntities.profileOf(hg, victim.level())
                                    .seatCount() >= 2,
                            "a happy ghast must expose multiple passenger seats");

                    // First rider mounts and is seated ON TOP (elevated).
                    helper.assertTrue(
                            MorphRideable.mount(rider1, victim).consumesAction(),
                            "the first rider must mount a happy-ghast morph");
                    helper.assertTrue(rider1.isPassenger() && victim.isVehicle(),
                            "rider1 must be a passenger and the victim a vehicle");
                    Vec3 seat1 = victim.getPassengerRidingPosition(rider1)
                            .subtract(victim.position());
                    helper.assertTrue(seat1.y > 1.0,
                            "the rider must be seated on top of the happy ghast "
                                    + "(elevated) but the seat offset was " + seat1);

                    // Second rider mounts too — happy ghast has multiple seats.
                    helper.assertTrue(
                            MorphRideable.mount(rider2, victim).consumesAction(),
                            "a happy-ghast morph must carry a SECOND rider (multi-seat)");
                    helper.assertTrue(rider2.isPassenger(),
                            "rider2 must also become a passenger");
                    helper.assertTrue(victim.getPassengers().size() == 2,
                            "the happy-ghast morph must carry two riders but has "
                                    + victim.getPassengers().size());
                    Vec3 seat2 = victim.getPassengerRidingPosition(rider2)
                            .subtract(victim.position());
                    helper.assertTrue(seat1.distanceToSqr(seat2) > 0.01,
                            "the two riders must sit at DISTINCT seats (seat1=" + seat1
                                    + " seat2=" + seat2 + ")");
                    rider1.stopRiding();
                    rider2.stopRiding();
                })
                .thenSucceed();
    }

    // ==================================================================
    // The sandboxed copy must not keep, sell or seat anything
    // ==================================================================

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** An allay takes whatever it is handed; the copy would have kept it. */
    @GameTest(maxTicks = 20)
    public void allayCopyKeepsNoItem(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        runProduction(victim, interactor, "allay",
                net.minecraft.world.item.Items.BUCKET);
        int buckets = count(interactor, net.minecraft.world.item.Items.BUCKET);
        helper.assertTrue(buckets == 1,
                "the bucket must come back from an allay-morph's copy, but the "
                        + "interactor has " + buckets);
        helper.succeed();
    }

    /** A SURVIVAL mock (the helper's mock is stamped creative, where nothing is
     *  consumed), built the vanilla way: real Connection on an EmbeddedChannel. */
    private static ServerPlayer survivalPlayer(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.randomUUID(), "test-survival-player");
        net.minecraft.server.network.CommonListenerCookie cookie =
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault()) {
            @Override
            public net.minecraft.world.level.GameType gameMode() {
                return net.minecraft.world.level.GameType.SURVIVAL;
            }
        };
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(
                net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelHandler[] {connection});
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.runBeforeTestEnd(() -> server.getPlayerList().remove(player));
        player.getAbilities().instabuild = false;
        return player;
    }

    /** The same in survival, where the allay really takes the bucket: it must
     *  come back, exactly once. */
    @GameTest(maxTicks = 20)
    public void allayCopyReturnsTheItemInSurvival(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = survivalPlayer(helper);
        InteractionResult result = runProduction(victim, interactor, "allay",
                net.minecraft.world.item.Items.BUCKET);
        int buckets = count(interactor, net.minecraft.world.item.Items.BUCKET);
        helper.assertTrue(buckets == 1,
                "a survival player must get the bucket back from an allay-morph's "
                        + "copy, exactly once - has " + buckets + " (result " + result + ")");
        helper.succeed();
    }

    /** A bucket on a sulfur-cube-shaped player filled a Sulfur Cube Bucket: a
     *  real, placeable mob minted from a player, every cooldown. */
    @GameTest(maxTicks = 20)
    public void sulfurCubeCopyCannotBeBucketed(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        InteractionResult result = runProduction(victim, interactor, "sulfur_cube",
                net.minecraft.world.item.Items.BUCKET);
        helper.assertTrue(result == InteractionResult.PASS,
                "a sulfur-cube-morph must not be bucketed - " + result);
        helper.assertTrue(interactor.getMainHandItem().is(net.minecraft.world.item.Items.BUCKET),
                "the interactor must still hold an empty bucket, not " + interactor.getMainHandItem());
        helper.succeed();
    }

    /** A villager-shaped player is not a shop: no trade screen, no use. */
    @GameTest(maxTicks = 20)
    public void villagerCopyIsNoShop(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        InteractionResult result = runProduction(victim, interactor, "villager",
                net.minecraft.world.item.Items.BUCKET);
        helper.assertTrue(result == InteractionResult.PASS,
                "a villager-morph must not be interacted with through its copy - "
                        + result);
        helper.assertTrue(interactor.containerMenu == interactor.inventoryMenu,
                "no trade screen may open on a villager-morph");
        helper.succeed();
    }

    /** A camel's right-click seats the player - on the copy, which is not in
     *  the world. Nothing may be mounted during a sandboxed interaction. */
    @GameTest(maxTicks = 20)
    public void camelCopySeatsNobody(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer interactor = mockPlayer(helper);
        runProduction(victim, interactor, "camel",
                net.minecraft.world.item.Items.BUCKET);
        helper.assertFalse(interactor.isPassenger(),
                "the interactor must not end up riding a camel-morph's copy (vehicle "
                        + interactor.getVehicle() + ")");
        helper.succeed();
    }

    /** A rider leaving the server used to take its "mount" with it - here the
     *  morphed player, who vanished from the world until they relogged. */
    @GameTest(maxTicks = 160)
    public void riderLeavingKeepsTheMorphedPlayer(GameTestHelper helper) {
        ServerPlayer victim = mockPlayer(helper);
        ServerPlayer rider = helper.makeMockServerPlayerInLevel(); // removed below
        MorphVariant horse = variant("horse");
        Morph.STATE.set(victim, new MorphState(java.util.Optional.of(horse),
                java.util.List.of(horse)));
        helper.startSequence()
                .thenExecuteAfter(settle(), () -> {
                    helper.assertTrue(MorphRideable.mount(rider, victim).consumesAction(),
                            "the rider must mount the horse-morph");
                    helper.getLevel().getServer().getPlayerList().remove(rider);
                    helper.assertFalse(victim.isRemoved(),
                            "the morphed player must stay in the world when its rider "
                                    + "leaves (removal " + victim.getRemovalReason() + ")");
                    helper.assertFalse(victim.isVehicle(),
                            "the leaving rider must have got off");
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
