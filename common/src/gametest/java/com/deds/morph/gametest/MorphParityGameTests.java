package com.deds.morph.gametest;

import com.deds.api.event.InteractionEvents;
import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;
import com.deds.morph.MorphConfig;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphInteractionEffects;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The code paths the NeoForge port touched, pinned on BOTH loaders: every test
 * here runs on Fabric and on NeoForge, so each one also pins the Fabric
 * behaviour the NeoForge side has to match.
 *
 * <ul>
 * <li><b>A PvP kill through the real {@code ServerPlayer.die}.</b> Until now
 *     every kill test killed a MOB, and the PvP acquisition tests fired
 *     {@code CombatEvents.PLAYER_KILLED_LIVING} by hand, so the one kill hook
 *     that fires for a player victim (inside {@code ServerPlayer.die}, at its
 *     {@code getKillCredit()} call: Fabric API's on Fabric, Ded's mirror on
 *     NeoForge) and its ordering against {@code ServerPlayerDieMixin} had no
 *     test at all.</li>
 * <li><b>A real interact packet through {@code handleInteract}.</b>
 *     {@code MorphInteractionGameTests} calls the router directly; this drives
 *     the server's own packet handler on a mock player's real listener, so
 *     the USE_ENTITY hook (Fabric API's, or Ded's NeoForge mirror) and its
 *     cancel semantics are what is under test.</li>
 * <li><b>{@code neoforge:spawn_type} is not identity.</b> NeoForge saves how a
 *     mob was spawned; {@code Morph.TRANSIENT_KEYS} strips it so one kind of
 *     mob stays one morph.</li>
 * <li><b>Shears on a sheep morph that cannot be sheared.</b> NeoForge removed
 *     the shears branch from {@code Sheep.mobInteract}, and its shears answer
 *     PASS where vanilla's removed branch answered CONSUME; the NeoForge
 *     {@code MorphLoader} puts CONSUME back.</li>
 * <li><b>The turtle helmet and a SWIM morph.</b> NeoForge rewrote the helmet's
 *     "eyes out of water" test in {@code Player.tick} in terms of drowning,
 *     which a SWIM morph cannot do; a NeoForge-only mixin restores vanilla's
 *     answer.</li>
 * </ul>
 *
 * <p>Recreation of iChun's Morph; all credit for the original to iChun.</p>
 */
public final class MorphParityGameTests implements CustomTestMethodInvoker {

    private static final BId ZOMBIE_ID = BId.of("minecraft", "zombie");
    private static final MorphVariant ZOMBIE = MorphVariant.ofType(ZOMBIE_ID);
    private static final MorphVariant COW =
            MorphVariant.ofType(BId.of("minecraft", "cow"));

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * A SURVIVAL mock server player on a real (embedded) connection, the
     * {@code MorphWave9GameTests} fixture: {@code player.connection} is the
     * server's own {@code ServerGamePacketListenerImpl}, so packets can be
     * handed to it exactly as the network layer would.
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
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

    /** Spawns a free-will-less mob on a fresh stone pedestal at (x,2,z). */
    private static <E extends Mob> E spawn(GameTestHelper helper,
            EntityType<E> type, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(type, new BlockPos(x, 2, z));
    }

    /** Seeds a COMMITTED morph with no transition lock (the wave-4 pattern). */
    private static void seed(ServerPlayer player, MorphVariant... variants) {
        Morph.clearTransitionLock(player);
        Morph.STATE.set(player, new MorphState(
                variants.length == 0 ? Optional.empty()
                        : Optional.of(variants[0]),
                List.of(variants)));
    }

    private static MorphConfig withLoseOnDeath(MorphConfig c, int value) {
        return new MorphConfig(c.childMorphs(), c.playerMorphs(), c.bossMorphs(),
                c.blacklistedMobs(), c.whitelistedPlayers(),
                c.disableEarlyGameFlight(), value, c.instaMorph(),
                c.abilities(), c.hostileAbilityMode(),
                c.hostileAbilityDistanceCheck(), c.canSleepMorphed(),
                c.allowMorphSelection(), c.sortMorphs(), c.interactions(), c.ai());
    }

    /** The full save of an entity, without its id: what {@code Morph.variantOf} reads. */
    private static CompoundTag save(LivingEntity entity) {
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, entity.level().registryAccess());
        entity.saveWithoutId(out);
        return out.buildResult();
    }

    // ==================================================================
    // PvP: the player-victim kill hook, through ServerPlayer.die
    // ==================================================================

    /**
     * A player kills a MORPHED player through the real {@code ServerPlayer.die}
     * (with a {@code playerAttack} source, so the killer holds the kill credit):
     * the killer acquires, and instantly wears, the morph the victim WORE; and
     * with {@code loseMorphsOnDeath=2} the victim then loses exactly that morph.
     *
     * <p>The ordering is the point. Acquisition fires from inside
     * {@code ServerPlayer.die} (at {@code getKillCredit()}), the lose-on-death
     * strip from its TAIL. Were the strip first, the victim would already be
     * back in their own form when the killer's acquisition reads them, and the
     * killer would get the victim's PLAYER form instead of the zombie: the
     * "not a player form" assertion below catches exactly that. Were the kill
     * hook missing (a loader bridge that only covers {@code LivingEntity.die},
     * which {@code ServerPlayer.die} never calls), the killer would get
     * nothing.</p>
     *
     * <p>{@code die} is called directly rather than through
     * {@code hurtServer}: a mock player is invulnerable to damage for reasons
     * unrelated to what is under test (its client never "loaded", and mocks
     * are stamped creative), while {@code die} is the exact method both kill
     * hooks and {@code ServerPlayerDieMixin} live in.</p>
     */
    @GameTest(maxTicks = 60)
    public void aPvpKillHandsOverTheWornMorphBeforeTheVictimLosesIt(
            GameTestHelper helper) {
        MorphConfig original = Morph.CONFIG.get();
        try {
            Morph.CONFIG.set(withLoseOnDeath(original, 2));
            ServerPlayer killer = mockPlayer(helper, "pvp-killer");
            ServerPlayer victim = mockPlayer(helper, "pvp-victim");
            seed(killer);
            seed(victim, ZOMBIE, COW); // seed() wears the FIRST variant

            helper.assertTrue(Morph.STATE.get(killer).acquired().isEmpty(),
                    "precondition: the killer must start with no morphs");
            helper.assertTrue(Morph.STATE.get(victim).current()
                            .equals(Optional.of(ZOMBIE)),
                    "precondition: the victim must be wearing the zombie");

            victim.die(victim.damageSources().playerAttack(killer));

            MorphState k = Morph.STATE.get(killer);
            helper.assertTrue(k.acquired().stream().noneMatch(MorphVariant::isPlayer),
                    "the killer acquired the victim's PLAYER form, so the victim "
                            + "had already lost the zombie when the kill fired: "
                            + "the lose-on-death strip ran before the acquisition "
                            + "(acquired " + k.acquired() + ")");
            helper.assertTrue(k.ownsType(ZOMBIE_ID),
                    "a PvP kill through ServerPlayer.die must hand the killer the "
                            + "morph the victim wore (the player-victim kill hook "
                            + "at getKillCredit), but the killer owns "
                            + k.acquired());
            helper.assertTrue(k.current().isPresent()
                            && k.current().get().type().equals(ZOMBIE_ID),
                    "instaMorph: the killer must be wearing the zombie right "
                            + "after the kill, but wears " + k.current());

            MorphState v = Morph.STATE.get(victim);
            helper.assertTrue(v.acquired().equals(List.of(COW)),
                    "loseMorphsOnDeath=2 must still strip the victim's WORN "
                            + "morph (zombie) after the kill and keep the rest, "
                            + "list is " + v.acquired());
            helper.assertTrue(v.current().isEmpty(),
                    "loseMorphsOnDeath=2 must demorph the victim, who wears "
                            + v.current());
        } finally {
            Morph.CONFIG.set(original);
        }
        helper.succeed();
    }

    // ==================================================================
    // USE_ENTITY: a real interact packet through handleInteract
    // ==================================================================

    /** The entity the probe listener watches; null = watch nothing. */
    private static final AtomicReference<Entity> ARMED = new AtomicReference<>();
    /** What the probe listener answers for the armed entity. */
    private static final AtomicReference<InteractionResult> ANSWER =
            new AtomicReference<>(InteractionResult.PASS);
    /** Every USE_ENTITY the probe saw for the armed entity. */
    private static final List<InteractionEvents.UseEntity> SEEN =
            new CopyOnWriteArrayList<>();
    private static final AtomicBoolean PROBE_REGISTERED = new AtomicBoolean();

    /**
     * Registers the probe listener once per run. Ded's events cannot be
     * unregistered, so it stays for the rest of the run, which is why it
     * answers PASS (changes nothing) for every entity but the one a test armed.
     * Registered after Morph's own router, so the router still sees every
     * interaction first, exactly as before.
     */
    private static void registerUseEntityProbe() {
        if (PROBE_REGISTERED.compareAndSet(false, true)) {
            InteractionEvents.USE_ENTITY.registerReturning(ctx -> {
                if (ctx.target() != ARMED.get()) {
                    return InteractionResult.PASS;
                }
                SEEN.add(ctx);
                return ANSWER.get();
            });
        }
    }

    private static boolean holdsMilk(ServerPlayer player) {
        return player.getInventory().contains(new ItemStack(Items.MILK_BUCKET));
    }

    /**
     * A player's interact packet, handed to the server's own
     * {@code handleInteract} on a mock player's real listener, reaches
     * {@code InteractionEvents.USE_ENTITY} with Fabric API's payload (the
     * player, its level, the hand, the target, and an ABSOLUTE hit: the
     * packet's entity-relative location plus the target's position); a result
     * other than PASS cancels vanilla's own interaction, and PASS lets it run.
     *
     * <p>Vanilla's interaction here is milking a cow with an empty bucket, so
     * "cancelled" and "ran" are both visible: no milk bucket anywhere after
     * the FAIL, a milk bucket after the PASS. Morph's router answers PASS for
     * a cow (it only handles morphed players), so the probe listener decides.
     * The mock's client first reports that it finished loading, with the
     * same packet a real client sends ({@code handleInteract} ignores a
     * player whose client has not loaded, and a mock's never does by itself);
     * nothing else about the packet path is faked.</p>
     */
    @GameTest(maxTicks = 40)
    public void anInteractPacketReachesUseEntityAndANonPassResultCancelsVanilla(
            GameTestHelper helper) {
        registerUseEntityProbe();
        Cow cow = spawn(helper, EntityTypes.COW, 1, 1);
        ServerPlayer player = mockPlayer(helper, "interact-packet");
        // Mock players are stamped creative (Playbook §8); a creative bucket
        // would stay a bucket and ADD a milk bucket. holdsMilk checks the
        // whole inventory either way, but take the plain survival path.
        player.getAbilities().instabuild = false;
        player.snapTo(cow.getX() + 1.5, cow.getY(), cow.getZ(), 90.0f, 0.0f);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));

        Vec3 relative = new Vec3(0.25, 0.75, -0.125);
        ServerboundInteractPacket packet = new ServerboundInteractPacket(
                cow.getId(), InteractionHand.MAIN_HAND, relative, false);
        try {
            SEEN.clear();
            ANSWER.set(InteractionResult.FAIL);
            ARMED.set(cow);
            player.connection.handleInteract(packet);

            helper.assertTrue(SEEN.size() == 1,
                    "the interact packet must reach USE_ENTITY exactly once, "
                            + "saw " + SEEN.size());
            InteractionEvents.UseEntity ctx = SEEN.get(0);
            helper.assertTrue(ctx.player() == player,
                    "USE_ENTITY must carry the interacting player");
            helper.assertTrue(ctx.level() == player.level(),
                    "USE_ENTITY must carry the player's level");
            helper.assertTrue(ctx.hand() == InteractionHand.MAIN_HAND,
                    "USE_ENTITY must carry the packet's hand, got " + ctx.hand());
            helper.assertTrue(ctx.target() == cow,
                    "USE_ENTITY must carry the packet's target entity");
            EntityHitResult hit = ctx.hit();
            Vec3 expected = relative.add(cow.getX(), cow.getY(), cow.getZ());
            helper.assertTrue(hit != null && hit.getEntity() == cow
                            && hit.getLocation().distanceToSqr(expected) < 1.0e-12,
                    "USE_ENTITY's hit must be the target at the ABSOLUTE point "
                            + expected + ", got " + (hit == null ? null
                                    : hit.getEntity() + " @ " + hit.getLocation()));
            helper.assertTrue(!holdsMilk(player)
                            && player.getItemInHand(InteractionHand.MAIN_HAND)
                                    .is(Items.BUCKET),
                    "a FAIL from USE_ENTITY must cancel vanilla's interaction, "
                            + "but the cow was milked anyway");

            ANSWER.set(InteractionResult.PASS);
            player.connection.handleInteract(packet);
            helper.assertTrue(SEEN.size() == 2,
                    "the second packet must reach USE_ENTITY too, saw "
                            + SEEN.size());
            helper.assertTrue(holdsMilk(player),
                    "a PASS from USE_ENTITY must let vanilla's interaction run "
                            + "(milking the cow), but there is no milk bucket; "
                            + "hand holds " + player.getItemInHand(
                                    InteractionHand.MAIN_HAND));
        } finally {
            ARMED.set(null);
            ANSWER.set(InteractionResult.PASS);
        }
        helper.succeed();
    }

    // ==================================================================
    // neoforge:spawn_type is not part of a morph's identity
    // ==================================================================

    /**
     * Two zombies that differ only in NeoForge's {@code neoforge:spawn_type}
     * save key are ONE morph: the key is in {@code Morph.TRANSIENT_KEYS}, and
     * a variant never carries it.
     *
     * <p>The key goes onto the second zombie the way a saved mob carries it:
     * the zombie is loaded back from its own save plus the key. NeoForge's
     * {@code Mob} reads it into its spawn reason and writes it out again on
     * every save, so on NeoForge this proves the strip (without it the two
     * variants differ); Fabric's {@code Mob} ignores the unknown key, so there
     * it pins that Fabric identity is untouched. Which of the two a run is, is
     * asserted against the running loader: if NeoForge ever stops carrying the
     * key (a rename, or a move in a later build), this test fails instead of
     * quietly degrading to the Fabric case while {@code TRANSIENT_KEYS} strips
     * a key that no longer exists.</p>
     */
    @GameTest(maxTicks = 40)
    public void aSpawnReasonSaveKeyIsNotPartOfTheMorph(GameTestHelper helper) {
        Zombie plain = spawn(helper, EntityTypes.ZOMBIE, 1, 1);
        Zombie tagged = spawn(helper, EntityTypes.ZOMBIE, 4, 1);

        CompoundTag withKey = save(tagged);
        withKey.putString("neoforge:spawn_type", "NATURAL");
        tagged.load(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), withKey));
        boolean carried = save(tagged).contains("neoforge:spawn_type");
        boolean neoForge = "neoforge".equals(com.deds.api.Deds.platform().loaderName());
        helper.assertTrue(carried == neoForge, neoForge
                ? "NeoForge's Mob no longer carries neoforge:spawn_type through a save: "
                        + "its spawn-reason key has moved, so update Morph.TRANSIENT_KEYS "
                        + "and this test"
                : "Fabric's Mob unexpectedly kept neoforge:spawn_type through a save");

        MorphVariant plainVariant = Morph.variantOf(plain);
        MorphVariant taggedVariant = Morph.variantOf(tagged);
        helper.assertTrue(!taggedVariant.data().contains("neoforge:spawn_type"),
                "a morph variant must never carry neoforge:spawn_type (the mob's "
                        + "save " + (carried ? "does" : "does not") + " have it)");
        helper.assertTrue(plainVariant.equals(taggedVariant),
                "two zombies that differ only in neoforge:spawn_type must be ONE "
                        + "morph (the mob's save " + (carried ? "does" : "does not")
                        + " carry the key); got " + plainVariant + " vs "
                        + taggedVariant);
        helper.succeed();
    }

    // ==================================================================
    // shears on a sheep morph that cannot be sheared: CONSUME, not PASS
    // ==================================================================

    /**
     * Shears on a BABY sheep morph, and on an already SHORN one, are consumed:
     * {@code MorphInteractionEffects.run} answers exactly
     * {@code InteractionResult.CONSUME} and nothing drops.
     *
     * <p>That is vanilla's answer: {@code Sheep.mobInteract} with shears on a
     * sheep that is not {@code readyForShearing()} returns CONSUME, so Morph
     * starts its harvest cooldown and the server stops handling the click
     * (Fabric runs that code as it is). NeoForge disabled that branch and
     * shears in {@code ShearsItem.interactLivingEntity}, which answers PASS
     * for a sheep that cannot be shorn, so the click would go on to other
     * handlers; the NeoForge {@code MorphLoader.afterMobInteract} turns a
     * PASS on a Sheep back into CONSUME. On Fabric this pins vanilla; on
     * NeoForge it pins that branch.</p>
     *
     * <p>The variants come from real sheep through {@code Morph.variantOf}, as
     * a kill would record them (a baby keeps its age, a shorn sheep its
     * {@code Sheared} flag), and the copies built from them are checked to
     * really be a baby and shorn before the shears go in. Each case has its
     * own victim: the harvest cooldown is per victim and item, and a second
     * click on the same victim would answer FAIL for the cooldown instead.</p>
     */
    @GameTest(maxTicks = 40)
    public void shearsOnABabyOrShornSheepMorphAreConsumed(GameTestHelper helper) {
        Sheep babySheep = spawn(helper, EntityTypes.SHEEP, 1, 1);
        babySheep.setBaby(true);
        Sheep shornSheep = spawn(helper, EntityTypes.SHEEP, 4, 1);
        shornSheep.setSheared(true);
        MorphVariant baby = Morph.variantOf(babySheep);
        MorphVariant shorn = Morph.variantOf(shornSheep);

        ServerLevel level = helper.getLevel();
        helper.assertTrue(MorphEntities.create(baby, level) instanceof Sheep s
                        && s.isBaby() && !s.isSheared(),
                "precondition: the baby sheep's variant must build a baby, unshorn "
                        + "sheep (variant " + baby + ")");
        helper.assertTrue(MorphEntities.create(shorn, level) instanceof Sheep s
                        && !s.isBaby() && s.isSheared(),
                "precondition: the shorn sheep's variant must build an adult, shorn "
                        + "sheep (variant " + shorn + ")");

        ServerPlayer babyVictim = mockPlayer(helper, "baby-sheep-morph");
        ServerPlayer shornVictim = mockPlayer(helper, "shorn-sheep-morph");
        ServerPlayer shearer = mockPlayer(helper, "shearer");
        babyVictim.snapTo(helper.absoluteVec(new Vec3(2.0, 2.0, 4.0)));
        shornVictim.snapTo(helper.absoluteVec(new Vec3(5.0, 2.0, 4.0)));
        shearer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));

        int itemsBefore = itemsNear(helper, babyVictim) + itemsNear(helper, shornVictim);
        InteractionResult babyResult = MorphInteractionEffects.run(babyVictim, baby,
                shearer, InteractionHand.MAIN_HAND);
        InteractionResult shornResult = MorphInteractionEffects.run(shornVictim, shorn,
                shearer, InteractionHand.MAIN_HAND);

        helper.assertTrue(InteractionResult.CONSUME.equals(babyResult),
                "shears on a BABY sheep morph must answer CONSUME, as vanilla's "
                        + "Sheep.mobInteract does for a sheep that is not ready for "
                        + "shearing, but answered " + babyResult);
        helper.assertTrue(InteractionResult.CONSUME.equals(shornResult),
                "shears on a SHORN sheep morph must answer CONSUME, as vanilla's "
                        + "Sheep.mobInteract does for a sheep that is not ready for "
                        + "shearing, but answered " + shornResult);
        helper.assertTrue(itemsNear(helper, babyVictim) + itemsNear(helper, shornVictim)
                        == itemsBefore,
                "a sheep morph that cannot be shorn must drop nothing");
        helper.succeed();
    }

    private static int itemsNear(GameTestHelper helper, ServerPlayer anchor) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                anchor.getBoundingBox().inflate(3.0)).size();
    }

    // ==================================================================
    // the turtle helmet gives a SWIM morph underwater nothing
    // ==================================================================

    /**
     * A turtle-helmeted player wearing a SWIM morph (a cod) with their eyes in
     * water does NOT get Water Breathing; a turtle-helmeted player with no
     * morph and their head out of the water DOES.
     *
     * <p>Vanilla gives the helmet's Water Breathing only while the wearer's
     * eyes are out of water ({@code !isEyeInFluid(WATER)} in
     * {@code Player.tick}), so that it lasts ten seconds after going under.
     * NeoForge rewrote that test as "eyes not in a fluid the player can drown
     * in", and a SWIM morph cannot drown (Morph's breathing mixin), so on
     * NeoForge a fish-shaped player underwater would count as dry and wear
     * the effect the whole time; Morph's NeoForge-only
     * {@code PlayerTurtleHelmetMixin} restores the vanilla answer. On Fabric
     * this pins vanilla.</p>
     *
     * <p>Both players are ticked with {@code ServerPlayer.doTick}, the method
     * the server's connection tick runs for a real player: a mock player's
     * embedded connection is never ticked, so {@code Player.tick} (where the
     * helmet check lives) would otherwise never run for it. The control
     * proves those ticks reach the helmet check at all: without it, a doTick
     * that skipped {@code Player.tick} would pass the SWIM half by doing
     * nothing.</p>
     */
    @GameTest(maxTicks = 80)
    public void aSwimMorphUnderwaterGetsNoTurtleHelmetWaterBreathing(
            GameTestHelper helper) {
        waterBasin(helper);
        helper.setBlock(new BlockPos(7, 1, 2), Blocks.STONE); // the control's dry spot
        ServerPlayer swimmer = mockPlayer(helper, "turtle-swimmer");
        ServerPlayer control = mockPlayer(helper, "turtle-control");
        MorphVariant cod = MorphVariant.ofType(BId.of("minecraft", "cod"));
        seed(swimmer, cod);
        swimmer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
        control.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));

        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    place(helper, swimmer, new Vec3(3.5, 2.0, 2.5));
                    place(helper, control, new Vec3(7.5, 2.0, 2.5));
                })
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(MorphAbilities.activeAbilities(swimmer)
                                    .contains(MorphAbility.SWIM),
                            "precondition: a cod morph must grant the SWIM ability");
                    helper.assertTrue(swimmer.isEyeInFluid(FluidTags.WATER),
                            "precondition: the swimmer's eyes must be in water");
                    helper.assertTrue(!control.isEyeInFluid(FluidTags.WATER)
                                    && !control.isInWater(),
                            "precondition: the control must be out of the water");
                    helper.assertTrue(swimmer.getEffect(MobEffects.WATER_BREATHING) == null
                                    && control.getEffect(MobEffects.WATER_BREATHING)
                                            == null,
                            "precondition: nobody has Water Breathing before the "
                                    + "player ticks");
                })
                .thenExecuteAfter(1, () -> tickBoth(swimmer, control))
                .thenExecuteAfter(1, () -> tickBoth(swimmer, control))
                .thenExecuteAfter(1, () -> tickBoth(swimmer, control))
                .thenExecuteAfter(1, () -> {
                    helper.assertTrue(control.getEffect(MobEffects.WATER_BREATHING) != null,
                            "control: a turtle helmet must give Water Breathing to a "
                                    + "player whose head is out of the water (if "
                                    + "this fails, doTick never reached the helmet "
                                    + "check in Player.tick)");
                    helper.assertTrue(swimmer.isEyeInFluid(FluidTags.WATER),
                            "the swimmer's eyes must still be in water after its "
                                    + "ticks");
                    helper.assertTrue(swimmer.getEffect(MobEffects.WATER_BREATHING) == null,
                            "a SWIM morph with its eyes in water must NOT get the "
                                    + "turtle helmet's Water Breathing (vanilla gives "
                                    + "it only out of the water), but it has "
                                    + swimmer.getEffect(MobEffects.WATER_BREATHING));
                })
                .thenSucceed();
    }

    /** Builds a stone basin holding a 3x1 column of water (x2..4, y2..3,
     *  z=2), MorphWave6GameTests' fixture, so a mock placed at (3.5, 2, 2.5)
     *  is genuinely eye-in-water. */
    private static void waterBasin(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= 3; y++) {
                    boolean wall = x == 1 || x == 5 || z == 1 || z == 3;
                    helper.setBlock(new BlockPos(x, y, z),
                            wall ? Blocks.STONE : Blocks.WATER);
                }
            }
        }
    }

    /**
     * Puts a mock at a test-relative position and refreshes its fluid state
     * through the protected {@code Entity.updateFluidInteraction()} (what
     * {@code baseTick} runs), so the preconditions read the truth before the
     * first {@code doTick}.
     */
    private static void place(GameTestHelper helper, ServerPlayer player, Vec3 at) {
        Vec3 pos = helper.absoluteVec(at);
        player.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        player.setHealth(20.0f);
        try {
            Method m = Entity.class.getDeclaredMethod("updateFluidInteraction");
            m.setAccessible(true);
            m.invoke(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("reflective updateFluidInteraction failed", e);
        }
    }

    private static void tickBoth(ServerPlayer first, ServerPlayer second) {
        first.doTick();
        second.doTick();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
