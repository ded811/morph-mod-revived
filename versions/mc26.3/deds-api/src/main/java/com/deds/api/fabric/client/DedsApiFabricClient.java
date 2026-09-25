package com.deds.api.fabric.client;

import com.deds.api.Deds;
import com.deds.api.client.BlockFaceSampler;
import com.deds.api.client.BlockModelWrappers;
import com.deds.api.client.BlockTints;
import com.deds.api.client.ClientKeys;

import com.mojang.blaze3d.platform.InputConstants;

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

import org.lwjgl.sdl.SDLMouse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fabric CLIENT entrypoint of Ded's API: installs the
 * {@link ClientKeys} backend (key mappings + per-mod controls category +
 * the press-dispatch tick loop). Runs before dependent mods' client
 * entrypoints (they declare a dependency on {@code deds_api}).
 */
@Environment(EnvType.CLIENT)
public final class DedsApiFabricClient implements ClientModInitializer {

    private static final List<PressKey> KEYS = new CopyOnWriteArrayList<>();
    private static final List<HeldKey> HELD_KEYS = new CopyOnWriteArrayList<>();
    private static final Map<String, KeyMapping.Category> CATEGORIES =
            new ConcurrentHashMap<>();

    /** A press binding: vanilla click-consumption for keyboard binds, RAW
     *  rising-edge polling while MOUSE-bound (see the tick loop). */
    private static final class PressKey {
        final RawKeyMapping key;
        final Runnable onPress;
        boolean wasDown;

        PressKey(RawKeyMapping key, Runnable onPress) {
            this.key = key;
            this.onPress = onPress;
        }
    }

    /** A held-state binding: RAW down-state polled per tick, edges dispatched. */
    private static final class HeldKey {
        final RawKeyMapping key;
        final Consumer<Boolean> onChange;
        boolean wasDown;

        HeldKey(RawKeyMapping key, Consumer<Boolean> onChange) {
            this.key = key;
            this.onChange = onChange;
        }
    }

    /**
     * A {@link KeyMapping} that exposes its CURRENT binding ({@code key} is
     * {@code protected}, javap-verified) so the held-state loop can poll the
     * physical device directly. Held bindings must NOT poll
     * {@link KeyMapping#isDown()}: opening any screen calls
     * {@code KeyMapping.releaseAll()} ({@code Gui.setScreen}, javap-verified),
     * which would fabricate a release edge the player never made — and the
     * restore on close ({@code MouseHandler.grabMouse} → {@code setAll()}) is
     * gated behind {@code InputQuirks.RESTORE_KEY_STATE_AFTER_MOUSE_GRAB}
     * (false on macOS) and skips MOUSE-bound keys entirely. Raw GLFW state has
     * none of those quirks — exactly the original TrailMix mechanism
     * ({@code TickHandlerClient.isPressed}: raw {@code Keyboard.isKeyDown} /
     * {@code Mouse.isButtonDown}).
     */
    private static final class RawKeyMapping extends KeyMapping {
        RawKeyMapping(String name, InputConstants.Type type, int code,
                KeyMapping.Category category) {
            super(name, type, code, category);
        }

        /** The CURRENT binding (follows controls-screen rebinds). */
        InputConstants.Key boundKey() {
            return this.key;
        }
    }

    @Override
    public void onInitializeClient() {
        Deds.LOGGER.info("Ded's API client initializing on Fabric");

        installBlockModelGlue();

        ClientKeys.install(new ClientKeys.Backend() {
            @Override
            public void register(String modId, String name,
                    ClientKeys.InputType type, int defaultCode,
                    Runnable onPress) {
                KEYS.add(new PressKey((RawKeyMapping)
                        mapping(modId, name, type, defaultCode), onPress));
            }

            @Override
            public void registerHeld(String modId, String name,
                    int defaultKey, Consumer<Boolean> onChange) {
                HELD_KEYS.add(new HeldKey((RawKeyMapping) mapping(modId, name,
                        ClientKeys.InputType.KEYBOARD, defaultKey), onChange));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            boolean screenOpen = minecraft.gui.screen() != null;
            for (PressKey press : KEYS) {
                if (press.key.boundKey().getType()
                        == InputConstants.Type.MOUSE) {
                    // MOUSE-bound presses dispatch from RAW rising edges
                    // (like the held keys): a mouse button shared with a
                    // vanilla bind (TrailMix's middle-mouse fireball vs
                    // pick-block) must fire BOTH consumers, exactly like
                    // the original's raw LWJGL polling — the vanilla click
                    // path's delivery then doesn't matter, so any clicks it
                    // queued on our mapping are DRAINED to prevent a
                    // double-fire.
                    while (press.key.consumeClick()) {
                        // drained — the raw edge below is the one trigger
                    }
                    boolean down = !screenOpen && rawDown(minecraft, press.key);
                    if (down && !press.wasDown) {
                        press.onPress.run();
                    }
                    press.wasDown = down;
                } else {
                    press.wasDown = false;
                    while (press.key.consumeClick()) {
                        press.onPress.run();
                    }
                }
            }
            // Held-state bindings (v1.2): FROZEN while any screen is open —
            // no edges, held stays held (the ClientKeys held-through-GUI
            // contract; the original TrailMix gated its whole key state
            // machine on currentScreen == null). Otherwise poll the RAW
            // device state of the current binding and dispatch BOTH edges;
            // the first poll after a screen closes emits at most one edge
            // per key if its physical state changed during the screen.
            if (screenOpen) {
                return;
            }
            for (HeldKey held : HELD_KEYS) {
                boolean down = rawDown(minecraft, held.key);
                if (down != held.wasDown) {
                    held.wasDown = down;
                    held.onChange.accept(down);
                }
            }
        });
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
     * The RAW physical down-state of a mapping's CURRENT binding, straight
     * from the platform layer — immune to {@code KeyMapping.releaseAll()}/
     * {@code setAll()} screen-transition churn on every platform.
     *
     * <p>26.3: the window layer is SDL, not GLFW. KEYBOARD bindings read
     * {@code InputConstants.isKeyDown(int)} (SDL's keyboard state, by
     * scancode). MOUSE bindings are SDL-numbered on 26.3 -
     * {@code InputConstants.MOUSE_BUTTON_LEFT} 1, {@code MIDDLE} 2,
     * {@code RIGHT} 3, extra buttons from 4 - and buttons 1..5 read SDL's
     * button mask ({@code SDL_GetMouseState}), the SDL twin of
     * {@code glfwGetMouseButton}. Not {@code MouseHandler.isLeftPressed()}
     * and friends: those only update while no screen is open, so a button
     * released inside a screen would read stuck-down after it closes. Any
     * other button falls back to the mapping's own state.</p>
     */
    private static boolean rawDown(Minecraft minecraft, RawKeyMapping mapping) {
        InputConstants.Key bound = mapping.boundKey();
        if (bound.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        if (bound.getType() == InputConstants.Type.KEYBOARD) {
            return InputConstants.isKeyDown(bound.getValue());
        }
        if (bound.getType() == InputConstants.Type.MOUSE) {
            int button = bound.getValue();
            if (button >= SDLMouse.SDL_BUTTON_LEFT && button <= SDLMouse.SDL_BUTTON_X2) {
                return (SDLMouse.SDL_GetMouseState(null, null)
                        & (1 << (button - 1))) != 0;
            }
            return mapping.isDown();
        }
        return mapping.isDown();
    }

    /** Builds + registers one KeyMapping under the mod's controls category. */
    private static KeyMapping mapping(String modId, String name,
            ClientKeys.InputType type, int defaultCode) {
        KeyMapping.Category category = CATEGORIES.computeIfAbsent(modId,
                id -> KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath(id, "main")));
        // ClientKeys.UNBOUND (-1) is the API's "no key". On 26.3 the
        // unbound key is KEYBOARD 0 (InputConstants.UNKNOWN), so UNBOUND is
        // translated to that code and always routes to KEYBOARD regardless
        // of the requested type - MOUSE.getOrCreate(-1) would mint a phantom
        // bound-looking mouse key (isUnbound() false, garbage label, dead
        // Reset). The typed ctor (KeyMapping(String,
        // InputConstants.Type, int, Category), javap-verified) carries mouse
        // -button defaults (v1.2) — left 0, right 1, middle 2 — while staying
        // fully rebindable.
        boolean unbound = defaultCode == ClientKeys.UNBOUND;
        InputConstants.Type vanillaType =
                unbound || type != ClientKeys.InputType.MOUSE
                        ? InputConstants.Type.KEYBOARD : InputConstants.Type.MOUSE;
        // Default codes pass through unchanged: they are the running
        // version's InputConstants values (KEY_*, MOUSE_BUTTON_*), which on
        // 26.3 are SDL scancodes and SDL button numbers (left 1, middle 2,
        // right 3). javac inlines those constants, so a consumer built
        // against 26.3 already hands over 26.3 numbers.
        int vanillaCode = unbound ? InputConstants.UNKNOWN.getValue() : defaultCode;
        return KeyMappingHelper.registerKeyMapping(
                new RawKeyMapping("key." + modId + "." + name, vanillaType,
                        vanillaCode, category));
    }
}
