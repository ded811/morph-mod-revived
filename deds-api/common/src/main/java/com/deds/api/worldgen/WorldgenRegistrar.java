package com.deds.api.worldgen;

/**
 * Worldgen for one mod (Ded's API v2.5) — {@code ModContext.worldgen()}.
 *
 * <p><b>Deliberately one method.</b> On 26.2 a world feature's entire shape —
 * which block, vein size, tries per chunk, height band — lives in datapack
 * JSON that the game's own registry loader reads
 * ({@code data/<modid>/worldgen/configured_feature/} and
 * {@code data/<modid>/worldgen/placed_feature/}, shipped inside the mod jar).
 * A mod needs no loader surface to <em>define</em> a feature. What JSON alone
 * cannot do is put that feature into biomes the mod does not own — vanilla's
 * biomes list their features in vanilla's own JSON, and editing someone
 * else's biome file is not a thing a datapack can do additively. That
 * injection is the one half that is genuinely loader surface, and it is the
 * only half this seam carries. It is <b>not</b> a worldgen framework: no
 * biome creation, no carvers, no structures, no dimension surface, no
 * runtime feature construction — each of those waits for a real consumer
 * (docs/MOD-COOKBOOK.md §14, docs/API-COMPATIBILITY.md §1).</p>
 *
 * <p>Its first consumer is the Thermal Expansion revival's wave-1 ores
 * (copper, tin, silver, lead, ferrous), whose vein sizes and height bands
 * transcribe the original pack's CoFHWorld config — the numbers live in that
 * mod's placed-feature JSON, not here. The 1.6.4 shape this replaces is
 * {@code IWorldGenerator}-era code registering an ore generator that ran
 * during chunk population; the modern equivalent of "runs with the other
 * ores" is the underground-ores generation step this seam pins.</p>
 *
 * <p>Registration is init-time state, like every other registrar on
 * {@code ModContext}: call this during mod initialization. Membership is
 * applied when a world's registries load, so it affects every world opened
 * afterwards — including chunks not yet generated in existing worlds;
 * already-generated chunks are untouched, as with any worldgen change.</p>
 */
public interface WorldgenRegistrar {

    /**
     * Adds a placed feature — a datapack JSON the mod ships under its own
     * namespace at {@code data/<modid>/worldgen/placed_feature/<name>.json} —
     * to every overworld biome at the underground-ores generation step.
     *
     * <p>{@code placedFeatureName} resolves in <b>this mod's own
     * namespace</b>: the feature id is {@code <modid>:<name>}, i.e. exactly
     * the JSON at the path above (normally sitting beside the
     * {@code configured_feature} JSON it references). "Every overworld
     * biome" means every biome carrying the {@code c:is_overworld}
     * convention tag: all of vanilla's, and any modded biome that declares
     * itself overworld the standard way — which is what 1.6.4 ore
     * generators reached by running for whatever chunk came. (Deliberately
     * NOT "whatever the running server's overworld can produce": that
     * answer changes per server — a void or single-biome world would
     * silently narrow it — where the tag answers the same everywhere.)</p>
     *
     * <p><b>The name is not checked here.</b> A typo, or a forgotten JSON,
     * registers fine and then fails at <em>world load</em>, when the
     * platform resolves the key against the loaded registry — an
     * {@code IllegalArgumentException} naming the missing key (verified
     * against the platform code in use, not assumed). If a world refuses to
     * load right after adding an ore, check the JSON's path and the name
     * passed here before anything else.</p>
     *
     * <p>Adding the same placed feature twice does not double its
     * generation: the platform skips a feature already present in that
     * biome's step (also verified). Two <em>different</em> placed features
     * over the same configured feature do stack — that is the standard way
     * to give one ore two height bands.</p>
     *
     * @param placedFeatureName the placed feature's name in this mod's
     *                          namespace — the {@code <name>} of the shipped
     *                          {@code worldgen/placed_feature/<name>.json}
     */
    void addOreToOverworld(String placedFeatureName);
}
