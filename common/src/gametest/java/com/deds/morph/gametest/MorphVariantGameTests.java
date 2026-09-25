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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Server gametests for Morph wave-2 deliverable 3, "generic variant identity"
 * (feature ids {@code morph/identity/*} + {@code morph/migration/legacy-load}),
 * mod id {@code deds_morph}.
 *
 * <p>Written BLIND against the frozen wave-2 identity contract and the identity
 * semantics of {@code docs/specs/morph/wave2/redesign-variant-identity.md}
 * (&sect;1/&sect;3/&sect;8). These tests bind ONLY to the public seams below and
 * to the spec's normalization rules &mdash; never to any implementation detail
 * (a coder writes {@code MorphVariant}/{@code Morph.variantOf}/the redesigned
 * {@code MorphState} in parallel). Note this is the REDESIGNED contract: the
 * three {@link MorphState} components are now {@link MorphVariant} (type +
 * normalized NBT), not the wave-1 bare {@code BId}.</p>
 *
 * <p>Frozen seams exercised:
 * <ul>
 * <li>{@code com.deds.morph.MorphVariant} &mdash; {@code record(BId type,
 *     CompoundTag data)}; {@code static MorphVariant ofType(BId)};
 *     {@code boolean isDefaultVariant()}; {@code static Codec<MorphVariant>
 *     CODEC}; value-based {@code equals}/{@code hashCode} (equal iff same
 *     {@code type} AND structurally-equal {@code data}).</li>
 * <li>{@code Morph.variantOf(LivingEntity)} &mdash; type + NORMALIZED nbt.</li>
 * <li>{@code Morph.acquireTarget(ServerPlayer, LivingEntity, boolean discard,
 *     boolean forced)}; {@code Morph.STATE} ({@code PlayerDataKey<MorphState>});
 *     {@code Morph.isMorphing(ServerPlayer)}; {@code Morph.TRANSITION_TICKS}
 *     (the ~80-tick transition lock, reused from the wave-2 command contract so
 *     these waits stay pinned to the frozen constant).</li>
 * <li>{@code MorphState.CODEC}; {@code MorphState.owns(MorphVariant)};
 *     {@code MorphState.ownsType(BId)}; {@code MorphState.variantsOf(BId)}.</li>
 * </ul>
 *
 * <p><b>Vanilla-API assumptions (javap-verified against the extracted
 * {@code server-26.2.jar}, never from memory):</b>
 * <ul>
 * <li>{@code Sheep} is {@code net.minecraft.world.entity.animal.sheep.Sheep};
 *     {@code void setColor(DyeColor)} exists; its {@code addAdditionalSaveData}
 *     writes {@code Sheared} (putBoolean) and {@code Color} (store) <i>always</i>
 *     &mdash; so EVERY sheep (even default white) carries non-transient variant
 *     NBT and is never the empty default variant.</li>
 * <li>{@code Slime} is {@code net.minecraft.world.entity.monster.cubemob.Slime}
 *     (NOT {@code animal.Slime}); {@code void setSize(int, boolean)} exists;
 *     {@code AbstractCubeMob.addAdditionalSaveData} persists {@code Size}
 *     (putInt) as a top-level key that survives the strip-only normalization.</li>
 * <li>{@code AgeableMob.setBaby(boolean)} exists (Sheep extends Animal extends
 *     AgeableMob); baby-vs-adult is a kept identity via the Age rule.</li>
 * <li>{@code Spider} ({@code monster.spider.Spider}) does NOT override
 *     {@code addAdditionalSaveData}, is not ageable, and has no variant &mdash;
 *     so a fresh spider's normalized NBT is empty (a genuine default variant).</li>
 * <li>{@code helper.spawnWithNoFreeWill(EntityType&lt;E extends Mob&gt;,
 *     BlockPos)} returns {@code E} and calls {@code finalizeSpawn} &mdash; which
 *     RANDOMIZES sheep colour; every sheep colour used below is therefore set
 *     EXPLICITLY after spawn so identities are deterministic.</li>
 * </ul>
 *
 * <p><b>Deviation from the literal task brief (test 6), with rationale:</b> the
 * brief asked to assert {@code variantOf(a plain sheep).equals(ofType(sheep))}.
 * The two javap facts above make that UNSATISFIABLE against a spec-correct impl:
 * {@code finalizeSpawn} gives the sheep a random colour, and {@code Sheep}
 * serializes {@code Color}+{@code Sheared} unconditionally, both of which the
 * spec's strip-only normalization (&sect;3) KEEPS. So a plain sheep is always a
 * concrete (non-default) variant. {@link #defaultVsVariant} therefore realizes
 * "a freshly spawned unconfigured mob normalizes to the default variant" with a
 * <i>Spider</i> (a mob that genuinely has no surviving variant key) and uses the
 * sheep for the contrasting "a configured mob is a distinct, non-default
 * identity" half &mdash; which is what {@code default-vs-variant} means.</p>
 */
public final class MorphVariantGameTests implements CustomTestMethodInvoker {

    // Vanilla identities = the mob's EntityType registry id.
    private static final BId SHEEP_ID = BId.of("minecraft", "sheep");
    private static final BId SLIME_ID = BId.of("minecraft", "slime");
    private static final BId SPIDER_ID = BId.of("minecraft", "spider");
    private static final BId COW_ID = BId.of("minecraft", "cow");

    // ------------------------------------------------------------------
    // helpers (house style, mirrored from MorphGameTests / MorphWave2GameTests)
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

    /** Places a one-block stone pedestal and spawns a free-will-less sheep on it. */
    private static Sheep sheepOn(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(EntityTypes.SHEEP, new BlockPos(x, 2, z));
    }

    private static Slime slimeOn(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        return helper.spawnWithNoFreeWill(EntityTypes.SLIME, new BlockPos(x, 2, z));
    }

    // ==================================================================
    // 1. morph/identity/variant-distinct-color
    // ==================================================================

    /**
     * &sect;8 morph/identity/variant-distinct. Two sheep set to DIFFERENT
     * {@link DyeColor}s produce NON-equal {@link MorphVariant}s, and both differ
     * from the default sheep variant {@code ofType(sheep)}; re-colouring the
     * second to MATCH the first collapses them back to EQUAL variants (with
     * equal hashCodes &mdash; the contract's value-based identity).
     *
     * <p>Colours are set explicitly because {@code spawnWithNoFreeWill} runs
     * {@code finalizeSpawn}, which assigns a random sheep colour.</p>
     */
    @GameTest(maxTicks = 100)
    public void variantDistinctColour(GameTestHelper helper) {
        Sheep a = sheepOn(helper, 1, 1);
        Sheep b = sheepOn(helper, 3, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    a.setColor(DyeColor.RED);
                    b.setColor(DyeColor.BLUE);

                    MorphVariant va = Morph.variantOf(a);
                    MorphVariant vb = Morph.variantOf(b);
                    MorphVariant def = MorphVariant.ofType(SHEEP_ID);

                    helper.assertTrue(va.type().equals(SHEEP_ID) && vb.type().equals(SHEEP_ID),
                            "both variants must carry the sheep type but were "
                                    + va.type() + " / " + vb.type());
                    helper.assertFalse(va.equals(vb),
                            "a red sheep and a blue sheep must be DIFFERENT variants "
                                    + "(colour is identity) but variantOf compared equal");
                    helper.assertFalse(va.equals(def),
                            "a coloured sheep must NOT equal the default sheep variant");
                    helper.assertFalse(vb.equals(def),
                            "a coloured sheep must NOT equal the default sheep variant");
                    helper.assertFalse(va.isDefaultVariant(),
                            "a red sheep carries Color/Sheared NBT and is not the default variant");
                    helper.assertFalse(vb.isDefaultVariant(),
                            "a blue sheep carries Color/Sheared NBT and is not the default variant");

                    // Same colour => same identity (transient noise aside).
                    b.setColor(DyeColor.RED);
                    MorphVariant vb2 = Morph.variantOf(b);
                    helper.assertTrue(va.equals(vb2),
                            "two sheep of the SAME colour must be EQUAL variants but "
                                    + va + " != " + vb2);
                    helper.assertTrue(va.hashCode() == vb2.hashCode(),
                            "equal variants must have equal hashCodes (value-based identity)");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 2. morph/identity/normalize-collapse
    // ==================================================================

    /**
     * &sect;8 morph/identity/normalize-collapse. Two same-colour sheep stay a
     * SINGLE identity even when one is damaged ({@code setHealth}), moved
     * ({@code setPos}) and given an effect ({@code addEffect}) &mdash; all of
     * {@code Health}/{@code Pos}/{@code active_effects} are transient and
     * normalized away (&sect;3). Separately, an ADULT vs a BABY sheep of the same
     * colour are NON-equal: baby-vs-adult is a kept identity via the Age rule.
     */
    @GameTest(maxTicks = 100)
    public void normalizeCollapse(GameTestHelper helper) {
        Sheep a = sheepOn(helper, 1, 1);
        Sheep b = sheepOn(helper, 3, 1);
        Sheep adult = sheepOn(helper, 1, 3);
        Sheep baby = sheepOn(helper, 3, 3);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    a.setColor(DyeColor.GREEN);
                    b.setColor(DyeColor.GREEN);

                    // Perturb b with purely transient state (all stripped by normalize).
                    b.setHealth(2.0f);
                    Vec3 moved = helper.absoluteVec(new Vec3(4.5, 2.0, 1.5));
                    b.setPos(moved.x, moved.y, moved.z);
                    b.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 0));

                    MorphVariant va = Morph.variantOf(a);
                    MorphVariant vb = Morph.variantOf(b);
                    helper.assertTrue(va.equals(vb),
                            "same-colour sheep must normalize to ONE identity despite "
                                    + "differing health/pos/effects but " + va + " != " + vb);

                    // Baby vs adult of the same colour: distinct identities.
                    adult.setColor(DyeColor.BLACK);
                    baby.setColor(DyeColor.BLACK);
                    baby.setBaby(true);
                    helper.assertTrue(baby.isBaby(),
                            "precondition: setBaby(true) must make the sheep a baby");
                    helper.assertFalse(adult.isBaby(),
                            "precondition: the adult sheep must not be a baby");

                    MorphVariant vAdult = Morph.variantOf(adult);
                    MorphVariant vBaby = Morph.variantOf(baby);
                    helper.assertFalse(vAdult.equals(vBaby),
                            "a baby sheep and an adult sheep of the same colour must be "
                                    + "DIFFERENT variants (age is identity) but compared equal");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 3. morph/identity/slime-size
    // ==================================================================

    /**
     * &sect;8 morph/identity/slime-size. Two slimes of DIFFERENT {@code Size}
     * produce NON-equal variants (Size is a persisted, non-transient key that
     * survives normalization); two slimes of the SAME size produce EQUAL
     * variants. The same-size pair is spawned and sized identically and read on
     * the same tick, so their (unstripped-but-identical) transient jump state
     * cannot diverge.
     */
    @GameTest(maxTicks = 100)
    public void slimeSize(GameTestHelper helper) {
        Slime small = slimeOn(helper, 1, 1);
        Slime large = slimeOn(helper, 3, 1);
        Slime sameA = slimeOn(helper, 1, 3);
        Slime sameB = slimeOn(helper, 3, 3);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    small.setSize(1, true);
                    large.setSize(3, true);
                    sameA.setSize(2, true);
                    sameB.setSize(2, true);

                    MorphVariant vSmall = Morph.variantOf(small);
                    MorphVariant vLarge = Morph.variantOf(large);
                    helper.assertTrue(vSmall.type().equals(SLIME_ID)
                                    && vLarge.type().equals(SLIME_ID),
                            "both slime variants must carry the slime type");
                    helper.assertFalse(vSmall.equals(vLarge),
                            "slimes of different Size must be DIFFERENT variants but "
                                    + "variantOf compared equal");

                    MorphVariant vA = Morph.variantOf(sameA);
                    MorphVariant vB = Morph.variantOf(sameB);
                    helper.assertTrue(vA.equals(vB),
                            "slimes of the SAME Size must be EQUAL variants but "
                                    + vA + " != " + vB);
                })
                .thenSucceed();
    }

    // ==================================================================
    // 4. morph/identity/acquire-variants
    // ==================================================================

    /**
     * &sect;8 morph/identity/acquire-variants (per-variant acquisition &amp;
     * dedupe). A player acquires a RED sheep, then &mdash; past the transition
     * lock &mdash; a BLUE sheep of the same type: {@code acquired} then holds TWO
     * entries, both {@code ownsType(sheep)}, {@code variantsOf(sheep).size()==2}.
     * Re-acquiring the SAME red-sheep variant (past the lock again) adds NO
     * duplicate &mdash; the list stays size 2.
     *
     * <p>{@code discard=false} keeps the sheep alive so the red variant can be
     * re-acquired. Each acquire is spaced by {@code TRANSITION_TICKS + 10} so the
     * next acquire is gated by identity (not the lock). The acquired variants are
     * recomputed from the (stable) live sheep via {@code variantOf} rather than
     * carried across sequence steps.</p>
     */
    @GameTest(maxTicks = 320)
    public void acquireVariants(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        Sheep red = sheepOn(helper, 1, 1);
        Sheep blue = sheepOn(helper, 3, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    red.setColor(DyeColor.RED);
                    blue.setColor(DyeColor.BLUE);

                    helper.assertTrue(state(helper, player).acquired().isEmpty(),
                            "a fresh mock player must start with no acquired variants");

                    MorphVariant redVar = Morph.variantOf(red);
                    helper.assertTrue(Morph.acquireTarget(player, red, false, true),
                            "acquiring a fresh red sheep should succeed");
                    MorphState s = state(helper, player);
                    helper.assertTrue(s.acquired().size() == 1,
                            "one acquire => exactly one acquired variant but is " + s.acquired());
                    helper.assertTrue(s.owns(redVar),
                            "the acquired list must own the red sheep variant");
                    helper.assertTrue(s.ownsType(SHEEP_ID),
                            "ownsType(sheep) must be true after acquiring a sheep");
                    helper.assertTrue(s.variantsOf(SHEEP_ID).size() == 1,
                            "variantsOf(sheep) must be size 1 after one sheep acquire");
                    helper.assertTrue(Morph.isMorphing(player),
                            "a committed acquire must start the transition lock");
                })
                // past the lock: acquire a second, DIFFERENT variant of the same type.
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the first acquire's transition must be over");

                    MorphVariant redVar = Morph.variantOf(red);
                    MorphVariant blueVar = Morph.variantOf(blue);
                    helper.assertFalse(redVar.equals(blueVar),
                            "sanity: the red and blue sheep must be different variants");

                    helper.assertTrue(Morph.acquireTarget(player, blue, false, true),
                            "acquiring a blue sheep (new variant of an owned type) should succeed");
                    MorphState s = state(helper, player);
                    helper.assertTrue(s.acquired().size() == 2,
                            "two distinct variants => TWO acquired entries but is " + s.acquired());
                    helper.assertTrue(s.owns(redVar) && s.owns(blueVar),
                            "both the red and blue sheep variants must be owned");
                    helper.assertTrue(s.ownsType(SHEEP_ID),
                            "ownsType(sheep) must still hold");
                    helper.assertTrue(s.variantsOf(SHEEP_ID).size() == 2,
                            "variantsOf(sheep) must group BOTH sheep variants (size 2) but is "
                                    + s.variantsOf(SHEEP_ID));
                })
                // past the lock again: re-acquire the SAME red variant => no duplicate.
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 10, () -> {
                    helper.assertFalse(Morph.isMorphing(player),
                            "the blue acquire's transition must be over");
                    Morph.acquireTarget(player, red, false, true); // already-owned variant
                })
                // "nothing new appears" cannot be waited FOR; sample after a short delay.
                .thenExecuteAfter(5, () -> {
                    MorphState s = state(helper, player);
                    helper.assertTrue(s.acquired().size() == 2,
                            "re-acquiring an already-owned variant must add NO duplicate "
                                    + "(size stays 2) but acquired is " + s.acquired());
                    helper.assertTrue(s.variantsOf(SHEEP_ID).size() == 2,
                            "variantsOf(sheep) must stay size 2 after the duplicate re-acquire");
                    helper.assertTrue(s.ownsType(SHEEP_ID),
                            "ownsType(sheep) must still hold");
                })
                .thenSucceed();
    }

    // ==================================================================
    // 5. morph/migration/legacy-load
    // ==================================================================

    /**
     * &sect;8 morph/migration/legacy-load. A hand-built wave-1 disk tag &mdash;
     * {@code acquired} as a {@link ListTag} of bare id STRINGS
     * ({@code "minecraft:cow"}, {@code "minecraft:sheep"}) plus a bare-string
     * {@code current} &mdash; parses through {@code MorphState.CODEC}
     * ({@link NbtOps#INSTANCE}) into two DEFAULT {@link MorphVariant}s
     * ({@code isDefaultVariant()} true, correct types), with {@code current}
     * loading too; and it round-trips (re-encode then re-parse yields an equal
     * record). This proves old saves migrate with no datafixer.
     */
    @GameTest(maxTicks = 40)
    public void legacyLoad(GameTestHelper helper) {
        CompoundTag legacy = new CompoundTag();
        ListTag acquired = new ListTag();
        acquired.add(StringTag.valueOf(COW_ID.toString()));    // "minecraft:cow"
        acquired.add(StringTag.valueOf(SHEEP_ID.toString()));  // "minecraft:sheep"
        legacy.put("acquired", acquired);
        legacy.putString("current", COW_ID.toString());        // bare-string current

        MorphState decoded = MorphState.CODEC
                .parse(NbtOps.INSTANCE, legacy)
                .result().orElseThrow(() -> new IllegalStateException(
                        "a legacy bare-id-string record must parse via MorphState.CODEC"));

        // Two acquired entries, both DEFAULT variants of the right type.
        List<MorphVariant> got = decoded.acquired();
        helper.assertTrue(got.size() == 2,
                "legacy acquired must load two entries but is " + got);
        helper.assertTrue(got.get(0).equals(MorphVariant.ofType(COW_ID)),
                "legacy acquired[0] must migrate to the default cow variant but is " + got.get(0));
        helper.assertTrue(got.get(1).equals(MorphVariant.ofType(SHEEP_ID)),
                "legacy acquired[1] must migrate to the default sheep variant but is " + got.get(1));
        helper.assertTrue(got.get(0).isDefaultVariant() && got.get(1).isDefaultVariant(),
                "migrated legacy entries must be default (empty-data) variants");
        helper.assertTrue(decoded.ownsType(COW_ID) && decoded.ownsType(SHEEP_ID),
                "both migrated types must be owned");

        // current migrates to the default cow variant.
        helper.assertTrue(decoded.current().equals(Optional.of(MorphVariant.ofType(COW_ID))),
                "legacy bare-string current must migrate to the default cow variant but is "
                        + decoded.current());

        // Round-trip: re-encode then re-parse is lossless (no datafixer).
        var reEncoded = MorphState.CODEC
                .encodeStart(NbtOps.INSTANCE, decoded)
                .result().orElseThrow(() -> new IllegalStateException(
                        "re-encode of a migrated state must succeed"));
        MorphState roundTripped = MorphState.CODEC
                .parse(NbtOps.INSTANCE, reEncoded)
                .result().orElseThrow(() -> new IllegalStateException(
                        "re-parse of a migrated state must succeed"));
        helper.assertTrue(roundTripped.equals(decoded),
                "a migrated state must round-trip losslessly (was " + decoded
                        + ", round-tripped " + roundTripped + ")");

        helper.succeed();
    }

    // ==================================================================
    // 6. morph/identity/default-vs-variant
    // ==================================================================

    /**
     * &sect;8 morph/identity/default-vs-variant. A freshly-spawned UNCONFIGURED
     * mob with no surviving variant key normalizes to the DEFAULT variant:
     * {@code variantOf(spider).equals(ofType(spider))} and
     * {@code isDefaultVariant()} is true. Conversely a configured sheep is a
     * NON-default variant that is DISTINCT from its own {@code ofType} (the
     * bare-id / migration identity), though it shares the type; and
     * {@code ofType} is value-equal per type and type-distinguishing.
     *
     * <p>Spider stands in for the brief's "plain sheep" here: javap shows a sheep
     * ALWAYS serializes {@code Color}+{@code Sheared} (kept by the strip-only
     * normalization), so no sheep is ever the empty default variant &mdash;
     * whereas {@code Spider} overrides no additional-save, is not ageable, and
     * has no variant, so its normalized NBT is genuinely empty.</p>
     */
    @GameTest(maxTicks = 100)
    public void defaultVsVariant(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Spider spider = helper.spawnWithNoFreeWill(EntityTypes.SPIDER, new BlockPos(1, 2, 1));
        Sheep sheep = sheepOn(helper, 3, 1);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    // Unconfigured, variant-less mob => the default variant.
                    MorphVariant spiderVar = Morph.variantOf(spider);
                    MorphVariant spiderDefault = MorphVariant.ofType(SPIDER_ID);
                    helper.assertTrue(spiderVar.isDefaultVariant(),
                            "a fresh spider has no variant NBT and must be the default variant "
                                    + "but variantOf carried data " + spiderVar.data());
                    helper.assertTrue(spiderVar.equals(spiderDefault),
                            "variantOf(fresh spider) must equal ofType(spider) but was "
                                    + spiderVar);
                    helper.assertTrue(spiderVar.type().equals(SPIDER_ID),
                            "the spider variant must carry the spider type");

                    // Configured mob => a distinct, NON-default variant of the same type.
                    sheep.setColor(DyeColor.RED);
                    MorphVariant sheepVar = Morph.variantOf(sheep);
                    MorphVariant sheepDefault = MorphVariant.ofType(SHEEP_ID);
                    helper.assertFalse(sheepVar.isDefaultVariant(),
                            "a coloured sheep carries variant NBT and is NOT the default variant");
                    helper.assertFalse(sheepVar.equals(sheepDefault),
                            "a concrete sheep variant must DIFFER from the default/migration "
                                    + "sheep identity ofType(sheep)");
                    helper.assertTrue(sheepVar.type().equals(SHEEP_ID),
                            "the sheep variant still carries the sheep type");

                    // ofType: value-based equality, type-distinguishing.
                    helper.assertTrue(sheepDefault.equals(MorphVariant.ofType(SHEEP_ID)),
                            "ofType must be value-equal for the same type");
                    helper.assertFalse(sheepDefault.equals(spiderDefault),
                            "default variants of different types must NOT be equal");
                    helper.assertTrue(sheepDefault.isDefaultVariant()
                                    && spiderDefault.isDefaultVariant(),
                            "ofType must always report isDefaultVariant() == true");
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
