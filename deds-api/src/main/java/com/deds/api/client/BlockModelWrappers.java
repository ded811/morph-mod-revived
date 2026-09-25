package com.deds.api.client;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Post-bake block-model wrapping (Ded's API v1.5) — the registration half of
 * the shared "camouflage / framed block" client glue. Call from a mod's CLIENT
 * entrypoint only.
 *
 * <p>Every revived mod that draws a block as <em>something else</em> needs the
 * same three-line dance: hook the loader's model-loading plugin, listen for
 * "this block state's model just baked", and hand back a wrapper model. That
 * plugin is 100 % loader-specific ({@code ModelLoadingPlugin} on Fabric, a
 * different bus on NeoForge), so the platform owns it; the wrapper models
 * themselves stay in each mod, because what they draw is mod-specific.</p>
 *
 * <p>Lifted from Secret Rooms' {@code SecretRoomsClient} when Carpenter's
 * Blocks became the third consumer (MOD-COOKBOOK §14, 3rd-consumer rule).</p>
 *
 * <p>Wrappers are applied in registration order, each one seeing the previous
 * one's result, so two mods can never silently drop each other's wrapper.</p>
 *
 * <p><b>Order-independent by construction.</b> This class holds the registry
 * itself and the platform merely reads it at bake time, so a mod may register
 * before or after the platform's client bootstrap runs. That matters: Fabric
 * does NOT order {@code deds_api}'s client entrypoint ahead of its consumers'
 * (observed 2026-07-26 — Secret Rooms' ran first and crashed on an
 * install-before-use check), and models bake long after every entrypoint has
 * finished either way.</p>
 */
public final class BlockModelWrappers {

    /**
     * Decides what model a block state renders with, after its own model has
     * baked.
     */
    @FunctionalInterface
    public interface Wrapper {

        /**
         * @param state the block state whose model just baked
         * @param model the model baked so far (already wrapped by any wrapper
         *              registered before this one)
         * @return the model to use — return {@code model} unchanged for block
         *         states this wrapper does not care about; {@code null} is
         *         treated as "unchanged"
         */
        BlockStateModel wrap(BlockState state, BlockStateModel model);
    }

    private static final List<Wrapper> WRAPPERS = new CopyOnWriteArrayList<>();

    private BlockModelWrappers() {
    }

    /**
     * Registers a model wrapper consulted after every block model bakes (and
     * again after every resource reload). Register once, from the client
     * entrypoint.
     */
    public static void register(Wrapper wrapper) {
        WRAPPERS.add(wrapper);
    }

    /**
     * Internal: every registered wrapper, in registration order. Read by the
     * platform inside its post-bake hook — never call this from mod code.
     */
    public static List<Wrapper> registered() {
        return WRAPPERS;
    }
}
