package com.deds.api.fabric.client;

import com.deds.api.Deds;
import com.deds.api.client.BlockEntityRenderers;
import com.deds.api.client.BlockFaceSampler;
import com.deds.api.client.BlockModelWrappers;
import com.deds.api.client.BlockTints;
import com.deds.api.client.ClientKeys;
import com.deds.api.client.FluidRenderers;
import com.deds.api.internal.client.CachingBlockFaceSampler;
import com.deds.api.internal.client.KeyDispatcher;
import com.deds.api.internal.client.ModelWrapping;
import com.deds.api.internal.client.RawKeyMapping;
import com.deds.api.internal.client.VanillaBlockEntityRenderers;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.IntList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fabric CLIENT entrypoint of Ded's API: installs the client backends (keys,
 * block-model glue, fluid rendering, block-entity renderers). Fabric does not
 * run this ahead of dependent mods' client entrypoints (a dependency on
 * {@code deds_api} gives no ordering), so {@link ClientKeys},
 * {@link BlockTints}, {@link FluidRenderers} and {@link BlockEntityRenderers}
 * queue the registrations made before it runs, and {@link BlockModelWrappers}
 * holds its registry itself.
 *
 * <p>Only the Fabric half of the key machinery lives here: the controls
 * category (vanilla {@code KeyMapping.Category.register}), the registration
 * through {@code KeyMappingHelper}, and running the dispatch loop at
 * {@code END_CLIENT_TICK}. The mappings themselves, the bindings and the loop
 * are the shared {@link KeyDispatcher}, so every loader dispatches keys
 * identically.</p>
 */
@Environment(EnvType.CLIENT)
public final class DedsApiFabricClient implements ClientModInitializer {

    private static final Map<String, KeyMapping.Category> CATEGORIES =
            new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        Deds.LOGGER.info("Ded's API client initializing on Fabric");

        installBlockModelGlue();
        installFluidRendering();
        // v2.8 — the block-entity-renderer seam. Order-independent by the
        // same queue-and-replay contract the two calls above use: every
        // binding a mod registered before this line is replayed right here,
        // because Fabric does not order the API's client entrypoint ahead of
        // a mod's. Thermal Expansion's portable tanks are the first consumer;
        // without this line they draw their glass and no fluid.
        BlockEntityRenderers.install(new VanillaBlockEntityRenderers());

        ClientKeys.install(new ClientKeys.Backend() {
            @Override
            public void register(String modId, String name,
                    ClientKeys.InputType type, int defaultCode,
                    Runnable onPress) {
                KeyDispatcher.addPress(
                        mapping(modId, name, type, defaultCode), onPress);
            }

            @Override
            public void registerHeld(String modId, String name,
                    int defaultKey, Consumer<Boolean> onChange) {
                KeyDispatcher.addHeld(mapping(modId, name,
                        ClientKeys.InputType.KEYBOARD, defaultKey), onChange);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(KeyDispatcher::tick);
    }

    /**
     * Installs the shared camouflage / framed-block client glue (v1.5): the
     * post-bake model wrapper hook ({@link BlockModelWrappers}) and the
     * dual-path biome-tint delegation ({@link BlockTints}). Both are pure
     * loader surface — {@code ModelLoadingPlugin} and {@code
     * BlockColorRegistry} — which is why they live here and not in a mod.
     *
     * <p>ONE {@code ModelLoadingPlugin} is registered for the whole game; it
     * drops the face-sampler cache (its sprites belong to the atlas that is
     * about to be replaced) and then runs every registered wrapper in order,
     * each seeing the previous one's result. The wrapper LIST lives in
     * {@link BlockModelWrappers} rather than here, so mods may register before
     * this bootstrap runs — Fabric does not order the API's client entrypoint
     * ahead of theirs.</p>
     */
    private static void installBlockModelGlue() {
        CachingBlockFaceSampler sampler = new CachingBlockFaceSampler();
        BlockFaceSampler.install(sampler);

        ModelLoadingPlugin.register(pluginContext -> {
            // models are about to (re)bake: cached face sprites from the
            // previous atlas would be stale
            sampler.invalidate();
            ModelWrapping.beginReload();
            pluginContext.modifyBlockModelAfterBake().register(
                    (model, context) -> ModelWrapping.apply(context.state(), model));
        });

        BlockTints.install(new BlockTints.Backend() {
            @Override
            public void collect(BlockState state, BlockAndTintGetter level,
                    BlockPos pos, IntList output) {
                // Fabric's getFactory only knows FABRIC-registered dynamic
                // factories; vanilla blocks (grass, leaves) live in vanilla
                // BlockColors' static tint-source lists — consult BOTH or
                // biome tints come out grey (Secret Rooms user-reported bug,
                // 2026-07-20).
                BlockTintsFactory factory = BlockColorRegistry.getFactory(state);
                if (factory != null) {
                    factory.collect(state, level, pos, output);
                    return;
                }
                for (BlockTintSource source : Minecraft.getInstance()
                        .getBlockColors().getTintSources(state)) {
                    output.add(source.colorInWorld(state, level, pos));
                }
            }

            @Override
            public void registerDelegating(BlockTints.AppearanceSource source,
                    List<Block> blocks) {
                BlockColorRegistry.register(
                        (BlockState state, BlockAndTintGetter level,
                                BlockPos pos, IntList output) -> {
                            if (level == null || pos == null) {
                                return;
                            }
                            BlockState appearance =
                                    source.appearanceAt(level, pos, state);
                            if (appearance != null) {
                                collect(appearance, level, pos, output);
                            }
                        },
                        blocks.toArray(new Block[0]));
            }
        });
    }

    /**
     * Installs the fluid-rendering backend (v2.6) — the loader call that gives
     * a modded fluid its sprites in the world.
     *
     * <p>26.2 resolves fluid textures through a code-side model registration
     * ({@code FluidRenderingRegistry.register(still, flowing,
     * FluidModel.Unbaked)}), and a fluid that misses it renders as the MISSING
     * TEXTURE checkerboard — not as an approximation, as a visibly broken
     * block. That is loader surface, so it belongs here and not in a mod
     * (ARCHITECTURE.md's boundary rule); {@link FluidRenderers} is the
     * mod-facing half.</p>
     *
     * <p>{@code FluidModel.Unbaked} takes still, flowing and OVERLAY
     * materials plus a {@link BlockTintSource}. The overlay is the sprite
     * drawn where the fluid meets a non-opaque neighbour — vanilla water has
     * a dedicated one; a fluid without a distinct overlay passes its own
     * flowing sprite, which is what every 1.6.4-era fluid effectively did.
     * The tint source is a per-state ARGB multiply, so an untinted fluid
     * gets a constant white.</p>
     */
    private static void installFluidRendering() {
        FluidRenderers.install((still, flowing, stillTexture, flowingTexture,
                tintArgb) -> {
            Material stillMaterial = new Material(
                    Identifier.fromNamespaceAndPath(stillTexture.namespace(),
                            stillTexture.path()));
            Material flowingMaterial = new Material(
                    Identifier.fromNamespaceAndPath(flowingTexture.namespace(),
                            flowingTexture.path()));
            FluidRenderingRegistry.register(still, flowing,
                    new FluidModel.Unbaked(stillMaterial, flowingMaterial,
                            flowingMaterial, state -> tintArgb));
        });
    }

    /**
     * Builds + registers one KeyMapping under the mod's controls category.
     * The mapping itself (name, type, the UNBOUND routing) is built by the
     * shared {@link KeyDispatcher#newMapping}.
     */
    private static RawKeyMapping mapping(String modId, String name,
            ClientKeys.InputType type, int defaultCode) {
        KeyMapping.Category category = CATEGORIES.computeIfAbsent(modId,
                id -> KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath(id, "main")));
        return (RawKeyMapping) KeyMappingHelper.registerKeyMapping(
                KeyDispatcher.newMapping(modId, name, type, defaultCode,
                        category));
    }
}
