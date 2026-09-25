package com.deds.api.fabric.client;

import com.deds.api.Deds;
import com.deds.api.client.BlockFaceSampler;
import com.deds.api.client.BlockModelWrappers;
import com.deds.api.client.BlockTints;
import com.deds.api.client.ClientKeys;
import com.deds.api.internal.client.KeyDispatcher;
import com.deds.api.internal.client.RawKeyMapping;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
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
 * Fabric CLIENT entrypoint of Ded's API: installs the
 * {@link ClientKeys} backend (key mappings + per-mod controls category +
 * the press-dispatch tick loop). Fabric does not run this ahead of
 * dependent mods' client entrypoints (a dependency on {@code deds_api} gives
 * no ordering), so {@link ClientKeys} and {@link BlockTints} queue the
 * registrations made before it runs, and {@link BlockModelWrappers} holds
 * its registry itself.
 *
 * <p>Only the Fabric half lives here: the controls category (vanilla
 * {@code KeyMapping.Category.register}), the registration through
 * {@code KeyMappingHelper}, and running the dispatch loop at
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
        FabricBlockFaceSampler sampler = new FabricBlockFaceSampler();
        BlockFaceSampler.install(sampler);

        ModelLoadingPlugin.register(pluginContext -> {
            // models are about to (re)bake: cached face sprites from the
            // previous atlas would be stale
            sampler.invalidate();
            pluginContext.modifyBlockModelAfterBake().register(
                    (model, context) -> {
                        BlockStateModel current = model;
                        for (BlockModelWrappers.Wrapper wrapper
                                : BlockModelWrappers.registered()) {
                            BlockStateModel next =
                                    wrapper.wrap(context.state(), current);
                            if (next != null) {
                                current = next;
                            }
                        }
                        return current;
                    });
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
