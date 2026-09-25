package com.deds.morph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Morph gameplay config — the original 0.7.1 {@code Morph.cfg} "gameplay"
 * category, ported to JSON (docs/specs/morph/SPEC.md wave-2 field table).
 *
 * <p>Every field loads via {@code optionalFieldOf(name, default)} so partial
 * or legacy JSON still parses (missing keys fall back to the 0.7.1 default).
 * Booleans were 0/1 ints in the original; multi-value ints
 * ({@code disableEarlyGameFlight} 0/1/2, {@code loseMorphsOnDeath} 0/1/2,
 * {@code hostileAbilityMode} 0-4) keep their int range; the CSV strings
 * ({@code blacklistedMobs}, {@code whitelistedPlayers}) become JSON string
 * lists.</p>
 *
 * <p><b>{@code sortMorphs}</b> (wave-9 item 6) is the one CLIENT display
 * preference in this record. The original kept it under {@code [client]} and only
 * wrote it when {@code proxy instanceof ClientProxy}
 * ({@code O:morph/common/Morph.java:162-191}); we have no client-only config
 * file, and because every side reads its OWN
 * {@code config/deds_morph/morph.json}, a value set here behaves exactly like a
 * per-client preference — the same way the {@code abilities} gate already drives
 * the selector's icon pass client-side. Modes are documented on
 * {@link MorphSort}.</p>
 *
 * <p><b>{@code childMorphs} defaults to TRUE here and the original's default
 * is 0</b> (the original disabled it "due to improper morph transitions"). The
 * user asked for baby morphs on by default on 2026-09-24.</p>
 *
 * <p><b>{@code canSleepMorphed} defaults to TRUE here and the original's
 * default is 0 — deviation D10-3.</b> The user asked for sleeping while morphed to work and signed
 * off the wave-8 lie-down; wiring the option with the original's default would
 * have un-shipped an approved feature in the same change that made the option
 * real. Set it to {@code false} for the original behaviour, refusal string
 * included ({@link MorphSleep}).</p>
 *
 * <p>Wave-3 behavior (interactions + mounting) fields live in a nested
 * {@link Interactions} group (a {@code "interactions"} JSON object) — both to
 * keep them cohesive and because {@code RecordCodecBuilder.group} caps at 16
 * components. Delegating accessors ({@link #mobInteractions()} etc.) keep call
 * sites flat.</p>
 */
public record MorphConfig(
        boolean childMorphs,
        boolean playerMorphs,
        boolean bossMorphs,
        List<String> blacklistedMobs,
        List<String> whitelistedPlayers,
        int disableEarlyGameFlight,
        int loseMorphsOnDeath,
        boolean instaMorph,
        boolean abilities,
        int hostileAbilityMode,
        int hostileAbilityDistanceCheck,
        boolean canSleepMorphed,
        boolean allowMorphSelection,
        int sortMorphs,
        Interactions interactions,
        Ai ai) {

    public MorphConfig {
        blacklistedMobs = List.copyOf(blacklistedMobs);
        whitelistedPlayers = List.copyOf(whitelistedPlayers);
    }

    /** The default whitelist of held items whose {@code mobInteract} only yields
     *  a consumable (milk/stew/shear drops) with no keep-worthy mob side-effect. */
    public static final List<String> DEFAULT_PRODUCTION_ITEMS = List.of(
            "minecraft:bucket", "minecraft:bowl", "minecraft:shears");

    /**
     * Wave-3 behavior config (milk/stew/shear a morph, ride a rideable morph).
     *
     * @param mobInteractions     master gate for production interactions
     * @param rideableMorphs      master gate for mounting a rideable morph
     * @param requireSaddleToRide opt-in: gate mounting on the morph carrying a
     *                            saddle in its NBT (default off — ride freely)
     * @param harvestCooldownTicks per-(victim,interaction) cooldown, the
     *                            "wool regrows"/no-dupe analog (default 1200 = 60s)
     * @param productionItems     the whitelisted held-item ids (pack-extendable)
     */
    public record Interactions(
            boolean mobInteractions,
            boolean rideableMorphs,
            boolean requireSaddleToRide,
            int harvestCooldownTicks,
            List<String> productionItems) {

        public Interactions {
            productionItems = List.copyOf(productionItems);
        }

        public static final Interactions DEFAULTS = new Interactions(
                true, true, false, 1200, DEFAULT_PRODUCTION_ITEMS);

        public static final Codec<Interactions> CODEC =
                RecordCodecBuilder.create(instance -> instance.group(
                        Codec.BOOL.optionalFieldOf("mobInteractions", true)
                                .forGetter(Interactions::mobInteractions),
                        Codec.BOOL.optionalFieldOf("rideableMorphs", true)
                                .forGetter(Interactions::rideableMorphs),
                        Codec.BOOL.optionalFieldOf("requireSaddleToRide", false)
                                .forGetter(Interactions::requireSaddleToRide),
                        Codec.INT.optionalFieldOf("harvestCooldownTicks", 1200)
                                .forGetter(Interactions::harvestCooldownTicks),
                        Codec.STRING.listOf()
                                .optionalFieldOf("productionItems",
                                        DEFAULT_PRODUCTION_ITEMS)
                                .forGetter(Interactions::productionItems)
                ).apply(instance, Interactions::new));
    }

    /**
     * Wave-3 AI-relationship config (mobs fear/hunt a morph as its shape).
     *
     * @param aiRelationships     master gate (avoid + hunt + brain-fear)
     * @param aiRelationshipsHunt the hunt half only (mobs target a matching morph)
     * @param aiRelationshipRange max scan/target distance (blocks), capped by the
     *                            goal's own follow distance
     */
    public record Ai(boolean aiRelationships, boolean aiRelationshipsHunt,
            int aiRelationshipRange) {

        public static final Ai DEFAULTS = new Ai(true, true, 24);

        public static final Codec<Ai> CODEC =
                RecordCodecBuilder.create(instance -> instance.group(
                        Codec.BOOL.optionalFieldOf("aiRelationships", true)
                                .forGetter(Ai::aiRelationships),
                        Codec.BOOL.optionalFieldOf("aiRelationshipsHunt", true)
                                .forGetter(Ai::aiRelationshipsHunt),
                        Codec.INT.optionalFieldOf("aiRelationshipRange", 24)
                                .forGetter(Ai::aiRelationshipRange)
                ).apply(instance, Ai::new));
    }

    // Delegating accessors so call sites stay flat (config.mobInteractions()).
    public boolean mobInteractions() {
        return interactions.mobInteractions();
    }

    public boolean aiRelationships() {
        return ai.aiRelationships();
    }

    public boolean aiRelationshipsHunt() {
        return ai.aiRelationshipsHunt();
    }

    public int aiRelationshipRange() {
        return ai.aiRelationshipRange();
    }

    public boolean rideableMorphs() {
        return interactions.rideableMorphs();
    }

    public boolean requireSaddleToRide() {
        return interactions.requireSaddleToRide();
    }

    public int harvestCooldownTicks() {
        return interactions.harvestCooldownTicks();
    }

    public List<String> productionItems() {
        return interactions.productionItems();
    }

    /** The 0.7.1 shipped defaults. */
    public static MorphConfig defaults() {
        return new MorphConfig(
                true,           // childMorphs — original 0, on by user request
                true,           // playerMorphs = 1
                false,          // bossMorphs = 0
                List.of(),      // blacklistedMobs = ""
                List.of(),      // whitelistedPlayers = ""
                0,              // disableEarlyGameFlight = 0
                0,              // loseMorphsOnDeath = 0 (keep all)
                true,           // instaMorph = 1
                true,           // abilities = 1
                0,              // hostileAbilityMode 0 = ON (all hostiles ignore a hostile morph); the original's 0 was off
                6,              // hostileAbilityDistanceCheck = 6
                true,           // canSleepMorphed — DEVIATION D10-3, see below
                true,           // allowMorphSelection = 1
                0,              // sortMorphs = 0 (order of acquisition)
                Interactions.DEFAULTS,
                Ai.DEFAULTS);
    }

    public static final Codec<MorphConfig> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("childMorphs", true)
                            .forGetter(MorphConfig::childMorphs),
                    Codec.BOOL.optionalFieldOf("playerMorphs", true)
                            .forGetter(MorphConfig::playerMorphs),
                    Codec.BOOL.optionalFieldOf("bossMorphs", false)
                            .forGetter(MorphConfig::bossMorphs),
                    Codec.STRING.listOf()
                            .optionalFieldOf("blacklistedMobs", List.of())
                            .forGetter(MorphConfig::blacklistedMobs),
                    Codec.STRING.listOf()
                            .optionalFieldOf("whitelistedPlayers", List.of())
                            .forGetter(MorphConfig::whitelistedPlayers),
                    Codec.INT.optionalFieldOf("disableEarlyGameFlight", 0)
                            .forGetter(MorphConfig::disableEarlyGameFlight),
                    Codec.INT.optionalFieldOf("loseMorphsOnDeath", 0)
                            .forGetter(MorphConfig::loseMorphsOnDeath),
                    Codec.BOOL.optionalFieldOf("instaMorph", true)
                            .forGetter(MorphConfig::instaMorph),
                    Codec.BOOL.optionalFieldOf("abilities", true)
                            .forGetter(MorphConfig::abilities),
                    Codec.INT.optionalFieldOf("hostileAbilityMode", 0)
                            .forGetter(MorphConfig::hostileAbilityMode),
                    Codec.INT.optionalFieldOf("hostileAbilityDistanceCheck", 6)
                            .forGetter(MorphConfig::hostileAbilityDistanceCheck),
                    Codec.BOOL.optionalFieldOf("canSleepMorphed", true)
                            .forGetter(MorphConfig::canSleepMorphed),
                    Codec.BOOL.optionalFieldOf("allowMorphSelection", true)
                            .forGetter(MorphConfig::allowMorphSelection),
                    Codec.INT.optionalFieldOf("sortMorphs", 0)
                            .forGetter(MorphConfig::sortMorphs),
                    Interactions.CODEC
                            .optionalFieldOf("interactions", Interactions.DEFAULTS)
                            .forGetter(MorphConfig::interactions),
                    Ai.CODEC.optionalFieldOf("ai", Ai.DEFAULTS)
                            .forGetter(MorphConfig::ai)
            ).apply(instance, MorphConfig::new));

    /** Same config with a different {@code sortMorphs} mode (wave-9 item 6 —
     *  used by the layer-3 harness to photograph each mode, and the seam a
     *  future "cycle sort order" key would drive). */
    public MorphConfig withSortMorphs(int mode) {
        return new MorphConfig(childMorphs, playerMorphs, bossMorphs,
                blacklistedMobs, whitelistedPlayers, disableEarlyGameFlight,
                loseMorphsOnDeath, instaMorph, abilities, hostileAbilityMode,
                hostileAbilityDistanceCheck, canSleepMorphed,
                allowMorphSelection, mode, interactions, ai);
    }

    /** Same config with a different {@code allowMorphSelection} (wave 10 — the
     *  seam the layer-3 harness drives to prove the strip really refuses to
     *  open, the same way {@link #withSortMorphs} exists for the sort modes). */
    public MorphConfig withAllowMorphSelection(boolean allowed) {
        return new MorphConfig(childMorphs, playerMorphs, bossMorphs,
                blacklistedMobs, whitelistedPlayers, disableEarlyGameFlight,
                loseMorphsOnDeath, instaMorph, abilities, hostileAbilityMode,
                hostileAbilityDistanceCheck, canSleepMorphed,
                allowed, sortMorphs, interactions, ai);
    }

    /** Same config with a replaced whitelist (for {@code /morph whitelist}). */
    public MorphConfig withWhitelistedPlayers(List<String> players) {
        return new MorphConfig(childMorphs, playerMorphs, bossMorphs,
                blacklistedMobs, players, disableEarlyGameFlight,
                loseMorphsOnDeath, instaMorph, abilities, hostileAbilityMode,
                hostileAbilityDistanceCheck, canSleepMorphed,
                allowMorphSelection, sortMorphs, interactions, ai);
    }
}
