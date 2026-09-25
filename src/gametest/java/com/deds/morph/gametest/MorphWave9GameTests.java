package com.deds.morph.gametest;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphList;
import com.deds.morph.MorphSort;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;
import com.deds.morph.SwimParams;
import com.deds.morph.api.Ability;
import com.deds.morph.api.AbilityRegistry;
import com.deds.morph.api.MorphApi;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server gametests for Morph <b>wave 9</b> — the seven items the owner approved
 * from the 2026-07-29 source audit (docs/specs/morph/SPEC.md §5).
 *
 * <p>Feature ids pinned here: {@code morph/mech/hurt-sound} (item 8),
 * {@code morph/sync/list-privacy} (item 4),
 * {@code morph/persist/lenient-decode} (item 3),
 * {@code morph/ability/swim-params} (item 2), {@code morph/selector/sort}
 * (item 6), {@code morph/api/register} + {@code morph/api/query} (item 1).
 * Item 7 (ability-icon scrolling) ships NO code this wave — it is an options
 * write-up for the owner, and none of its three options is headless-testable
 * anyway.</p>
 *
 * <p>Mod id {@code deds_morph}. Recreation of iChun's Morph; all credit to
 * iChun.</p>
 */
public final class MorphWave9GameTests implements CustomTestMethodInvoker {

    private static final MorphVariant PIG =
            MorphVariant.ofType(BId.of("minecraft", "pig"));
    private static final MorphVariant COW =
            MorphVariant.ofType(BId.of("minecraft", "cow"));
    private static final MorphVariant ZOMBIE =
            MorphVariant.ofType(BId.of("minecraft", "zombie"));

    // ------------------------------------------------------------------
    // item 1 — the third-party ability the API tests drive
    // ------------------------------------------------------------------

    /** {@code appliesTo} target: a GOAT, chosen because no other morph gametest
     *  touches one, so this ability can never perturb another test's set. */
    private static final BId GOAT = BId.of("minecraft", "goat");

    /** Two DISTINCT goat variants — morphing between them must NOT fire
     *  {@code kill()} (the original's contract, {@code O:morph/api/Ability.java:71-75}). */
    private static final MorphVariant GOAT_A = goat(true);
    private static final MorphVariant GOAT_B = goat(false);

    private static MorphVariant goat(boolean screaming) {
        CompoundTag data = new CompoundTag();
        data.putBoolean("IsScreamingGoat", screaming);
        return new MorphVariant(GOAT, data);
    }

    static final AtomicInteger TICKS = new AtomicInteger();
    static final AtomicInteger KILLS = new AtomicInteger();
    static final AtomicInteger INSTANCES = new AtomicInteger();

    /**
     * A minimal third-party {@link Ability} — exactly what another mod would
     * write against {@code com.deds.morph.api}. Registered in a static
     * initializer, which Fabric runs when it constructs this gametest
     * entrypoint, i.e. before any morph variant has been probed.
     */
    static final class CountingAbility implements Ability {
        @Override
        public BId id() {
            return BId.of("deds_morph_test", "counting");
        }

        @Override
        public boolean appliesTo(LivingEntity dummy) {
            return GOAT.toString().equals(
                    net.minecraft.world.entity.EntityType
                            .getKey(dummy.getType()).toString());
        }

        @Override
        public Instance createInstance(Player parent) {
            INSTANCES.incrementAndGet();
            return new Instance() {
                @Override
                public void tick() {
                    TICKS.incrementAndGet();
                }

                @Override
                public void kill() {
                    KILLS.incrementAndGet();
                }
            };
        }
    }

    static {
        AbilityRegistry.register(new CountingAbility());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A SURVIVAL mock server player (creative mocks break several paths). */
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

    /**
     * Invokes {@code LivingEntity.getHurtSound(DamageSource)} — {@code protected},
     * javap-verified — REFLECTIVELY, so the call dispatches virtually through
     * {@code Player}'s override and therefore through the
     * {@code PlayerHurtSoundMixin} HEAD inject. (Doing it via the mod's own
     * {@code @Invoker} would test the invoker, not the mixin.)
     */
    private static SoundEvent hurtSoundOf(LivingEntity entity, DamageSource source) {
        try {
            Method m = LivingEntity.class.getDeclaredMethod("getHurtSound",
                    DamageSource.class);
            m.setAccessible(true);
            return (SoundEvent) m.invoke(entity, source);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("reflective getHurtSound failed", e);
        }
    }

    /** The registered Fabric attachment behind one of our player-data keys. */
    private static AttachmentType<?> attachment(String name) {
        try {
            Class<?> registry = Class.forName(
                    "net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl");
            Method get = registry.getMethod("get", Identifier.class);
            return (AttachmentType<?>) get.invoke(null,
                    Identifier.fromNamespaceAndPath(Morph.MOD_ID, name));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "could not read attachment " + name, e);
        }
    }

    /** The attachment's sync predicate — {@code all()} vs {@code targetOnly()}
     *  is EXACTLY the privacy property under test. */
    private static AttachmentSyncPredicate syncPredicateOf(AttachmentType<?> type) {
        try {
            Method accessor = type.getClass().getMethod("syncPredicate");
            accessor.setAccessible(true);
            return (AttachmentSyncPredicate) accessor.invoke(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read syncPredicate", e);
        }
    }

    // ==================================================================
    // item 8 — morph/mech/hurt-sound
    // ==================================================================

    /**
     * {@code morph/mech/hurt-sound} (wave-9 item 8). The original swapped a
     * morphed player's {@code "damage.hit"} for the morph's own
     * {@code getHurtSound()} ({@code O:morph/common/core/EventHandler.java:670-687}),
     * keyed on {@code info.nextState} — the form being morphed INTO, so the new
     * voice starts at transition START. Ours reads
     * {@code MorphState.current()}, which the server flips at exactly that
     * instant, so the timing is reproduced without an {@code isMorphing} gate.
     *
     * <p>Non-vacuous: the zombie assertion can only pass if the mixin fired AND
     * the {@code @Invoker} dispatched virtually into {@code Zombie}
     * ({@code ZOMBIE_HURT}) rather than the {@code LivingEntity} base
     * ({@code GENERIC_HURT}) or {@code Monster} ({@code HOSTILE_HURT}). The
     * unmorphed control pins the other side.</p>
     */
    @GameTest(maxTicks = 200)
    public void aMorphedPlayerYelpsLikeTheMob(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper, "hurt-sound-morph");
        ServerPlayer control = mockPlayer(helper, "hurt-sound-control");
        DamageSource source = player.damageSources().generic();

        SoundEvent vanilla = hurtSoundOf(control, source);
        helper.assertTrue(vanilla == hurtSoundOf(player, source),
                "precondition: two unmorphed players must sound identical");
        helper.assertTrue(vanilla != SoundEvents.ZOMBIE_HURT,
                "precondition: an unmorphed player must NOT already sound like a"
                        + " zombie (that would make the test vacuous)");

        Morph.STATE.set(player, new MorphState(Optional.of(ZOMBIE),
                List.of(ZOMBIE)));
        helper.assertTrue(hurtSoundOf(player, source) == SoundEvents.ZOMBIE_HURT,
                "a zombie morph must hurt with SoundEvents.ZOMBIE_HURT but was "
                        + hurtSoundOf(player, source));
        helper.assertTrue(hurtSoundOf(control, source) == vanilla,
                "an UNMORPHED bystander must keep the vanilla player hurt sound");

        // A variant whose type does not exist: MorphEntities.create returns null,
        // the profile degrades to BROKEN (hurtSound == null) and we must fall
        // THROUGH to vanilla, never silence the player.
        MorphVariant broken =
                MorphVariant.ofType(BId.of("deds_morph", "no_such_mob"));
        Morph.STATE.set(player, new MorphState(Optional.of(broken),
                List.of(broken)));
        helper.assertTrue(hurtSoundOf(player, source) == vanilla,
                "an unbuildable morph must fall back to the vanilla hurt sound");

        // Demorph restores the player voice.
        Morph.STATE.set(player, MorphState.EMPTY);
        helper.assertTrue(hurtSoundOf(player, source) == vanilla,
                "a demorphed player must sound like a player again");
        helper.succeed();
    }

    // ==================================================================
    // item 4 — morph/sync/list-privacy
    // ==================================================================

    /**
     * {@code morph/sync/list-privacy} (wave-9 item 4). The original sent the
     * acquired list to its OWNER ONLY
     * ({@code O:morph/common/morph/MorphHandler.java:91,107},
     * {@code sendPacketToPlayer}) while the worn morph rode a separate packet
     * broadcast to everybody ({@code O:morph/common/core/EntityHelper.java:130},
     * {@code sendPacketToAllPlayers}). Ours shipped ONE {@code Sync.ALL}
     * attachment carrying all three components, so a modified client could
     * enumerate what every player owns.
     *
     * <p>This asserts the property itself, not its spelling: it reads back the
     * live Fabric {@code AttachmentSyncPredicate} of each registered attachment
     * and checks who it lets through. Flipping {@code deds_morph:state} back to
     * {@code Sync.ALL} — a change that compiles and breaks nothing else — turns
     * this test RED.</p>
     */
    @GameTest(maxTicks = 200)
    public void theAcquiredListIsSyncedToItsOwnerOnly(GameTestHelper helper) {
        ServerPlayer owner = mockPlayer(helper, "privacy-owner");
        ServerPlayer observer = mockPlayer(helper, "privacy-observer");

        AttachmentType<?> worn = attachment("worn");
        AttachmentType<?> list = attachment("state");
        helper.assertTrue(worn != null && list != null,
                "both morph attachments must be registered");
        helper.assertTrue(worn.isSynced() && list.isSynced(),
                "both halves must still be synced (the split must not have "
                        + "silently made the list server-only)");

        AttachmentSyncPredicate wornPredicate = syncPredicateOf(worn);
        AttachmentSyncPredicate listPredicate = syncPredicateOf(list);
        AttachmentTarget target = (AttachmentTarget) owner;

        helper.assertTrue(wornPredicate.test(target, observer),
                "the WORN morph must still reach every tracking client — "
                        + "otherwise nobody could render anyone else's morph");
        helper.assertTrue(!listPredicate.test(target, observer),
                "the ACQUIRED LIST must never reach a player who does not own "
                        + "it (this is the data-exposure fix)");
        helper.assertTrue(listPredicate.test(target, owner),
                "the acquired list must still reach its OWNER — the selector "
                        + "reads it from the local player");
        helper.succeed();
    }

    /**
     * {@code morph/sync/list-privacy} (wave-9 item 4, composition half).
     * {@code Morph.STATE} is no longer an attachment: it composes
     * {@code Morph.WORN} + {@code Morph.LIST}. Every server seam and every
     * frozen wave-1/2 gametest still talks to it, so the round-trip of all
     * three components must be exact, and each half must land in the right
     * attachment.
     */
    @GameTest(maxTicks = 200)
    public void morphStateComposesOverBothHalves(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper, "compose");

        helper.assertTrue(Morph.STATE.get(player).equals(MorphState.EMPTY),
                "a fresh player must compose to MorphState.EMPTY");

        MorphState seeded = new MorphState(Optional.of(PIG),
                List.of(PIG, COW), List.of(COW));
        Morph.STATE.set(player, seeded);

        helper.assertTrue(Morph.STATE.get(player).equals(seeded),
                "the composed read must return every component it was given");
        helper.assertTrue(Morph.WORN.get(player).equals(Optional.of(PIG)),
                "the worn morph must land in the PUBLIC half");
        helper.assertTrue(Morph.LIST.get(player).acquired().equals(
                        List.of(PIG, COW)),
                "the acquisition list must land in the PRIVATE half");
        helper.assertTrue(Morph.LIST.get(player).favourites().equals(
                        List.of(COW)),
                "favourites must land in the PRIVATE half");

        // A worn-only change must not disturb the list half, and vice versa.
        Morph.STATE.set(player, seeded.withCurrent(Optional.of(COW)));
        helper.assertTrue(Morph.LIST.get(player).acquired().equals(
                        List.of(PIG, COW)),
                "changing the worn morph must leave the list untouched");
        Morph.STATE.set(player, MorphState.EMPTY);
        helper.assertTrue(Morph.WORN.get(player).isEmpty()
                        && Morph.LIST.get(player).acquired().isEmpty(),
                "clearing must clear both halves");
        helper.succeed();
    }

    // ==================================================================
    // item 3 — morph/persist/lenient-decode
    // ==================================================================

    /**
     * {@code morph/persist/lenient-decode} (wave-9 item 3). The original gave a
     * stored state whose entity could not be rebuilt a Pig fallback so one dead
     * entry never poisoned the list
     * ({@code O:morph/common/morph/MorphState.java:130-150}). Ours makes the
     * LIST codecs element-wise lenient instead — a structurally broken entry is
     * logged and skipped, and an entry whose type id is merely unregistered
     * (a removed mod) is KEPT as a dormant entry, which is strictly better than
     * silently turning it into a pig.
     *
     * <p>Without {@code MorphCodecs.lenientList} the whole list decode fails and
     * the assertions below see zero entries.</p>
     */
    @GameTest(maxTicks = 200)
    public void oneBrokenAcquisitionDoesNotEatTheWholeList(GameTestHelper helper) {
        ListTag acquired = new ListTag();
        acquired.add(variantTag("minecraft:pig"));       // valid
        acquired.add(variantTag("deds_morph:no_such"));  // valid, DORMANT
        acquired.add(new CompoundTag());                 // broken: no "type"
        CompoundTag wrongType = new CompoundTag();
        wrongType.putInt("type", 7);                     // broken: type not a string
        acquired.add(wrongType);

        ListTag favourites = new ListTag();
        favourites.add(variantTag("minecraft:pig"));
        favourites.add(new CompoundTag());               // broken

        CompoundTag raw = new CompoundTag();
        raw.put("acquired", acquired);
        raw.put("favourites", favourites);

        MorphList list = decode(helper, MorphList.CODEC, raw);
        helper.assertTrue(list.acquired().size() == 2,
                "the two decodable acquisitions must survive a broken sibling "
                        + "but the list had " + list.acquired().size());
        helper.assertTrue(list.acquired().get(0).type()
                        .equals(BId.of("minecraft", "pig")),
                "the surviving entries must keep their ORDER");
        helper.assertTrue(list.acquired().get(1).type()
                        .equals(BId.of("deds_morph", "no_such")),
                "an unregistered type id must be KEPT (dormant), not dropped "
                        + "and not turned into a pig");
        helper.assertTrue(list.favourites().size() == 1,
                "favourites must be lenient too");

        // The whole-record codec (used by the frozen state round-trip tests)
        // carries the same leniency, and `current` still round-trips.
        raw.put("current", StringTag.valueOf("minecraft:cow"));
        MorphState state = decode(helper, MorphState.CODEC, raw);
        helper.assertTrue(state.acquired().size() == 2,
                "MorphState.CODEC must be lenient in the same way");
        helper.assertTrue(state.current().equals(Optional.of(COW)),
                "the worn morph must still round-trip beside a damaged list");

        // A corrupt `current` must still fail LOUDLY (the SPEC's explicit rule):
        // only the LIST halves are lenient.
        CompoundTag badCurrent = new CompoundTag();
        badCurrent.put("acquired", new ListTag());
        badCurrent.putInt("current", 7);
        helper.assertTrue(MorphState.CODEC
                        .parse(NbtOps.INSTANCE, badCurrent).result().isEmpty(),
                "a corrupt `current` must FAIL the decode, not be swallowed");
        helper.succeed();
    }

    private static CompoundTag variantTag(String type) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type);
        return tag;
    }

    private static <T> T decode(GameTestHelper helper, Codec<T> codec,
            CompoundTag tag) {
        return codec.parse(NbtOps.INSTANCE, tag).result().orElseThrow(
                () -> new AssertionError("lenient decode failed outright: " + tag));
    }

    // ==================================================================
    // item 2 — morph/ability/swim-params
    // ==================================================================

    /**
     * {@code morph/ability/swim-params} (wave-9 item 2). The original's abilities
     * carried constructor state fed by the pipe-arg mapping format, and the ONLY
     * vanilla mob given swim arguments was the squid:
     * {@code swim(false, 1.2f, 0.4f, true)}
     * ({@code O:morph/common/ability/AbilityHandler.java:67}), with
     * {@code swimSpeed} hard-clamped to 1.22F in both the constructor and
     * {@code parse} ({@code O:morph/common/ability/AbilitySwim.java:53-56,66-69}).
     * Our enum is stateless, so the parameters are derived per morph.
     *
     * <p>Headless half only: the three motion behaviours themselves are
     * client-authoritative (Playbook §1) and live in {@code MorphAbilitiesClient}
     * — playtest / layer 3. What IS checkable here is the derivation, the clamp
     * and the land-slowdown air gate, all pure.</p>
     */
    @GameTest(maxTicks = 200)
    public void swimParametersReproduceTheOriginals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        SwimParams squid = MorphEntities.profileOf(
                MorphVariant.ofType(BId.of("minecraft", "squid")), level).swim();
        helper.assertTrue(!squid.canSurviveOutOfWater() && squid.swimSpeed() == 1.2f
                        && squid.landSpeed() == 0.4f && squid.canMaintainDepth(),
                "a squid morph must derive the original's exact swim arguments "
                        + "(false, 1.2, 0.4, true) but derived " + squid);

        SwimParams cod = MorphEntities.profileOf(
                MorphVariant.ofType(BId.of("minecraft", "cod")), level).swim();
        helper.assertTrue(cod.equals(SwimParams.AQUATIC),
                "a fish is strictly aquatic and takes the same parameters");

        SwimParams turtle = MorphEntities.profileOf(
                MorphVariant.ofType(BId.of("minecraft", "turtle")), level).swim();
        helper.assertTrue(turtle.canSurviveOutOfWater()
                        && !turtle.canMaintainDepth() && turtle.landSpeed() == 1f,
                "an AIR-BREATHING swimmer must not hold depth and must not be "
                        + "slowed on land, but derived " + turtle);

        SwimParams pig = MorphEntities.profileOf(PIG, level).swim();
        helper.assertTrue(pig.equals(SwimParams.NONE),
                "a morph without the SWIM ability must carry no swim parameters");

        // The original's hard clamp, in BOTH places it existed.
        helper.assertTrue(new SwimParams(false, 5.0f, 1f, true).swimSpeed()
                        == SwimParams.MAX_SWIM_SPEED,
                "swimSpeed must be clamped to the original's 1.22F");

        // The land-slowdown air gate (AbilitySwim.java:143): air < 285.
        helper.assertTrue(!SwimParams.AQUATIC.slowsOnLand(300)
                        && !SwimParams.AQUATIC.slowsOnLand(285)
                        && SwimParams.AQUATIC.slowsOnLand(284),
                "the land slowdown must engage only once air drops below 285");
        helper.assertTrue(!SwimParams.AIR_BREATHER.slowsOnLand(0),
                "an air-breathing swimmer is never slowed on land");

        // The SOFT multiply (AbilitySwim.java:100-107): scale only while the
        // component is still inside (-factor, factor).
        helper.assertTrue(Math.abs(SwimParams.softScale(0.1, 0.4f) - 0.04) < 1e-9,
                "a component inside the band must be scaled");
        helper.assertTrue(SwimParams.softScale(0.9, 0.4f) == 0.9,
                "a component already past the band must be left alone");
        helper.succeed();
    }

    // ==================================================================
    // item 6 — morph/selector/sort
    // ==================================================================

    /**
     * {@code morph/selector/sort} (wave-9 item 6). {@code sortMorphs} has
     * <b>FOUR</b> modes, not the three this SPEC assumed — the config comment at
     * {@code O:morph/common/Morph.java:188} names them: 0 acquisition,
     * 1 alphabetical, 2 alphabetical + sorted within groups, 3 most recently
     * used since connecting. Modes 1/2 reorder the columns
     * ({@code O:morph/common/morph/MorphHandler.java:124-149}), mode 2
     * additionally sorts within one ({@code :50-53}), mode 3 floats the
     * just-worn column to the top
     * ({@code O:morph/client/core/PacketHandlerClient.java:135-163}).
     *
     * <p>Pure function, so all four modes are checkable headlessly. Also pins
     * the SPEC's risk (a): modes 0/1/3 must leave intra-column order alone.</p>
     */
    @GameTest(maxTicks = 200)
    public void sortMorphsHasFourModes(GameTestHelper helper) {
        BId sheep = BId.of("minecraft", "sheep");
        // acquisition order: cow, sheep(white), sheep(black), axolotl
        MorphVariant white = sheepOf(sheep, 0);
        MorphVariant black = sheepOf(sheep, 15);
        MorphVariant axolotl = MorphVariant.ofType(BId.of("minecraft", "axolotl"));
        MorphState state = new MorphState(Optional.empty(),
                List.of(COW, white, black, axolotl));

        // Display names, NOT registry ids — the original sorted on
        // getEntityName() and so must we.
        java.util.function.Function<Object, String> label = key -> {
            String path = ((BId) key).path();
            return switch (path) {
                case "cow" -> "Cow";
                case "sheep" -> "Sheep";
                case "axolotl" -> "Axolotl";
                default -> path;
            };
        };

        List<Object> mode0 = keys(MorphSort.sorted(state, MorphSort.ACQUISITION,
                label, null));
        helper.assertTrue(mode0.equals(List.of(COW.type(), sheep,
                        axolotl.type())),
                "mode 0 must be acquisition order but was " + mode0);

        List<Object> mode1 = keys(MorphSort.sorted(state, MorphSort.ALPHABETICAL,
                label, null));
        helper.assertTrue(mode1.equals(List.of(axolotl.type(), COW.type(),
                        sheep)),
                "mode 1 must be alphabetical by DISPLAY NAME but was " + mode1);

        var deep = MorphSort.sorted(state, MorphSort.ALPHABETICAL_DEEP, label,
                null);
        helper.assertTrue(keys(deep).equals(mode1),
                "mode 2 must order the columns exactly like mode 1");
        helper.assertTrue(deep.get(sheep).equals(List.of(white, black)),
                "mode 2 must sort WITHIN a column (Color 0 before Color 15)");

        var shallow = MorphSort.sorted(state, MorphSort.ALPHABETICAL, label, null);
        helper.assertTrue(shallow.get(sheep).equals(List.of(white, black)),
                "mode 1 must leave intra-column order untouched");

        // Mode 3: the column you just wore floats to the top; the rest hold
        // their acquisition order.
        List<Object> mode3 = keys(MorphSort.sorted(state, MorphSort.RECENT,
                label, sheep));
        helper.assertTrue(mode3.equals(List.of(sheep, COW.type(),
                        axolotl.type())),
                "mode 3 must float the most recently worn column but was " + mode3);
        List<Object> mode3none = keys(MorphSort.sorted(state, MorphSort.RECENT,
                label, null));
        helper.assertTrue(mode3none.equals(mode0),
                "mode 3 with nothing worn yet must be acquisition order");
        var mode3map = MorphSort.sorted(state, MorphSort.RECENT, label, sheep);
        helper.assertTrue(mode3map.get(sheep).equals(List.of(white, black)),
                "mode 3 must leave intra-column order untouched");

        // The mode-3 client-side note/forget pair.
        MorphSort.noteWorn(sheep);
        helper.assertTrue(sheep.equals(MorphSort.recentlyWorn()),
                "noteWorn must record the group key");
        MorphSort.forgetRecent();
        helper.assertTrue(MorphSort.recentlyWorn() == null,
                "leaving a world must end \"since connecting\"");

        helper.assertTrue(Morph.CONFIG.get().sortMorphs() == 0,
                "the shipped default must stay 0 (order of acquisition), the "
                        + "original's server default");
        helper.succeed();
    }

    private static MorphVariant sheepOf(BId type, int color) {
        CompoundTag data = new CompoundTag();
        data.putInt("Color", color);
        return new MorphVariant(type, data);
    }

    private static List<Object> keys(
            LinkedHashMap<Object, List<MorphVariant>> groups) {
        return new ArrayList<>(groups.keySet());
    }

    // ==================================================================
    // item 1 — morph/api/register and morph/api/query
    // ==================================================================

    /**
     * {@code morph/api/register} (wave-9 item 1). The original shipped
     * {@code morph/api/Ability.java:18-117} for other mods to extend, and its
     * javadoc states the lifecycle contract this test pins, verbatim:
     * registration instances are CLONED per user ({@code :20-23,77-81}),
     * {@code tick()} runs only while the instance has a parent ({@code :65-69}),
     * and {@code kill()} is called when the ability leaves the set — <b>"This
     * will NOT be called if the parent morphs into another morph that has this
     * type of ability"</b> ({@code :71-75}).
     *
     * <p>{@code CountingAbility} is registered from this class's static
     * initializer, exactly as a consumer mod would from its initializer.</p>
     */
    @GameTest(maxTicks = 300)
    public void aThirdPartyAbilityFollowsTheOriginalLifecycle(
            GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper, "api-lifecycle");
        TICKS.set(0);
        KILLS.set(0);
        INSTANCES.set(0);

        helper.assertTrue(AbilityRegistry.get(BId.of("deds_morph_test",
                        "counting")) != null,
                "the third-party ability must be in the registry");
        helper.assertTrue(MorphEntities.profileOf(GOAT_A, helper.getLevel())
                        .custom().size() == 1,
                "a goat morph's profile must resolve the registered ability");
        helper.assertTrue(MorphEntities.profileOf(PIG, helper.getLevel())
                        .custom().isEmpty(),
                "a pig morph must resolve NO third-party abilities");

        helper.startSequence()
                .thenExecuteAfter(2, () -> Morph.STATE.set(player,
                        new MorphState(Optional.of(GOAT_A),
                                List.of(GOAT_A, GOAT_B, PIG))))
                .thenExecuteAfter(15, () -> {
                    helper.assertTrue(INSTANCES.get() == 1,
                            "exactly one per-player instance must be created "
                                    + "(the original clones its template)");
                    helper.assertTrue(TICKS.get() > 0,
                            "tick() must run while the ability has a parent");
                    helper.assertTrue(KILLS.get() == 0,
                            "kill() must not fire while the ability is held");
                    helper.assertTrue(MorphAbilitiesHasCustom(player),
                            "the ability must be reported as active");
                })
                // Morph to ANOTHER form that ALSO has it: no kill, no new instance.
                .thenExecuteAfter(2, () -> Morph.STATE.set(player,
                        new MorphState(Optional.of(GOAT_B),
                                List.of(GOAT_A, GOAT_B, PIG))))
                .thenExecuteAfter(15, () -> {
                    helper.assertTrue(KILLS.get() == 0,
                            "kill() must NOT fire when morphing between two "
                                    + "forms that both carry the ability "
                                    + "(O:morph/api/Ability.java:71-75)");
                    helper.assertTrue(INSTANCES.get() == 1,
                            "the SAME instance must carry over, not a new one");
                })
                // Morph to a form WITHOUT it: exactly one kill.
                .thenExecuteAfter(2, () -> Morph.STATE.set(player,
                        new MorphState(Optional.of(PIG),
                                List.of(GOAT_A, GOAT_B, PIG))))
                .thenExecuteAfter(15, () -> {
                    helper.assertTrue(KILLS.get() == 1,
                            "kill() must fire exactly once when the ability "
                                    + "leaves the set but fired " + KILLS.get());
                    helper.assertTrue(!MorphAbilitiesHasCustom(player),
                            "the ability must no longer be reported as active");
                })
                .thenExecuteAfter(2, () -> Morph.STATE.set(player,
                        MorphState.EMPTY))
                .thenExecuteAfter(15, () -> helper.assertTrue(KILLS.get() == 1,
                        "a later demorph must not kill the ability a SECOND time"))
                .thenSucceed();
    }

    private static boolean MorphAbilitiesHasCustom(ServerPlayer player) {
        return !com.deds.morph.MorphAbilities.activeCustomAbilities(player)
                .isEmpty();
    }

    /**
     * {@code morph/api/query} (wave-9 item 1). The counterpart to iChun's
     * {@code morph/api/Api.java:18-153}: {@code hasMorph},
     * {@code morphProgress} (which returned {@code morphProgress/80F}, i.e.
     * 0..1), {@code forceMorph} and {@code forceDemorph}. Asserted mid-transition,
     * which is the only interesting moment for {@code morphProgress}.
     */
    @GameTest(maxTicks = 300)
    public void theQueryApiReportsMorphStateAndProgress(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper, "api-query");

        helper.assertTrue(!MorphApi.isMorphed(player),
                "a fresh player is not morphed");
        helper.assertTrue(MorphApi.morphProgress(player) == 1.0f,
                "morphProgress must be 1.0 (settled) when nothing is running");
        helper.assertTrue(MorphApi.wornType(player).isEmpty(),
                "an unmorphed player wears no type");

        Morph.STATE.set(player, new MorphState(Optional.empty(), List.of(COW)));
        Morph.select(player, Optional.of(COW));

        helper.assertTrue(MorphApi.isMorphed(player),
                "the worn morph flips at transition START, like the original's "
                        + "nextState");
        helper.assertTrue(MorphApi.wornType(player)
                        .equals(Optional.of(COW.type())),
                "wornType must report the morph being taken on");
        float atStart = MorphApi.morphProgress(player);
        helper.assertTrue(atStart < 0.2f,
                "progress must start near 0 but was " + atStart);

        helper.startSequence()
                .thenExecuteAfter(40, () -> {
                    float mid = MorphApi.morphProgress(player);
                    helper.assertTrue(mid > atStart && mid < 1.0f,
                            "progress must RISE through the transformation but "
                                    + "read " + mid);
                })
                .thenExecuteAfter(Morph.TRANSITION_TICKS, () -> {
                    helper.assertTrue(MorphApi.morphProgress(player) == 1.0f,
                            "progress must settle at 1.0");
                    helper.assertTrue(MorphApi.forceDemorph(player),
                            "forceDemorph must succeed on a morphed player");
                })
                .thenExecuteAfter(2, () -> helper.assertTrue(
                        !MorphApi.isMorphed(player),
                        "forceDemorph must return the player to their own form"))
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
