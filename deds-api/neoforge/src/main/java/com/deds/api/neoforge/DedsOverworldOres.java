package com.deds.api.neoforge;

import com.mojang.serialization.MapCodec;

import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The NeoForge side of {@code WorldgenRegistrar.addOreToOverworld}: ONE biome
 * modifier for every Ded's mod, shipped as
 * {@code data/deds_api/neoforge/biome_modifier/overworld_ores.json} in this
 * jar, so no mod needs a NeoForge-only JSON of its own.
 *
 * <p>It reproduces Fabric's {@code BiomeModifications.addFeature(
 * BiomeSelectors.tag(c:is_overworld), UNDERGROUND_ORES, key)} exactly:</p>
 * <ul>
 * <li>every biome in {@code c:is_overworld} (the same tag, with the same
 *     contents on both loaders), in the ADD phase;</li>
 * <li>each placed feature appended at the END of the underground-ores step,
 *     in the order Fabric applies modifiers, which is by the feature's id
 *     (and so is the order generation seeds by);</li>
 * <li>a feature already in the step is skipped, never doubled (NeoForge's
 *     stock {@code add_features} modifier would double it);</li>
 * <li>a key with no datapack JSON throws Fabric's own
 *     {@code IllegalArgumentException("Couldn't find holder for ...")} when
 *     the server applies modifiers, before any chunk generates.</li>
 * </ul>
 *
 * <p>NeoForge applies modifiers at the start of every server's
 * {@code initServer} (integrated, dedicated and gametest), right after it
 * records the current server, so the server's own registries resolve the
 * keys. It refreshes the generators' per-step feature lists itself. A
 * datapack can switch the whole modifier off by overriding the JSON with
 * {@code {"type": "neoforge:none"}}, which Fabric has no equivalent of.</p>
 */
public final class DedsOverworldOres implements BiomeModifier {

    public static final DedsOverworldOres INSTANCE = new DedsOverworldOres();

    public static final MapCodec<DedsOverworldOres> CODEC = MapCodec.unit(INSTANCE);

    /** The serializer id the shipped JSON names. */
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("deds_api", "overworld_ores");

    private static final TagKey<Biome> IS_OVERWORLD = TagKey.create(
            Registries.BIOME, Identifier.fromNamespaceAndPath("c", "is_overworld"));

    private static final List<ResourceKey<PlacedFeature>> KEYS =
            new CopyOnWriteArrayList<>();

    private DedsOverworldOres() {
    }

    static void add(ResourceKey<PlacedFeature> key) {
        KEYS.add(key);
    }

    @Override
    public void modify(Holder<Biome> biome, Phase phase,
            ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.ADD || KEYS.isEmpty() || !biome.is(IS_OVERWORLD)) {
            return;
        }
        HolderLookup.RegistryLookup<PlacedFeature> features =
                ServerLifecycleHooks.getCurrentServer().registryAccess()
                        .lookupOrThrow(Registries.PLACED_FEATURE);
        List<Holder<PlacedFeature>> step = builder.getGenerationSettings()
                .getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES);
        for (ResourceKey<PlacedFeature> key : KEYS.stream().distinct()
                .sorted(Comparator.comparing(ResourceKey::identifier)).toList()) {
            Holder.Reference<PlacedFeature> holder = features.get(key)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Couldn't find holder for " + key));
            if (!step.contains(holder)) {
                step.add(holder);
            }
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CODEC;
    }
}
