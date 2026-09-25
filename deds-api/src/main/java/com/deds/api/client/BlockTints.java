package com.deds.api.client;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.IntList;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Biome-tint delegation for blocks that render as another block (Ded's API
 * v1.5) — the third piece of the shared camouflage / framed-block client glue.
 *
 * <p><b>The dual path is a bug fix, not a style choice — do not re-derive
 * it.</b> The loader's tint registry only knows the dynamic tint factories
 * mods registered with it; vanilla blocks (grass, leaves, water-adjacent
 * foliage) carry their tints in vanilla's own static {@code BlockColors}
 * tint-source lists. Consulting only one of the two makes every biome-coloured
 * disguise render grey. Secret Rooms shipped it that way and it was a
 * user-reported bug (2026-07-20); {@link #collect} is that fix, lifted
 * verbatim.</p>
 *
 * <p>Call {@link #registerDelegating} from a mod's CLIENT entrypoint to make a
 * set of blocks adopt the tints of whatever they currently look like; call
 * {@link #collect} directly when a model wants to fold the tint into its own
 * vertex colours instead of going through a tint index.</p>
 */
public final class BlockTints {

    /**
     * Resolves the block state whose tints a block at a position should
     * borrow.
     */
    @FunctionalInterface
    public interface AppearanceSource {

        /**
         * @param level the render view
         * @param pos   the position being tinted
         * @param state the block's own state
         * @return the state whose tints to use, or {@code null} for none (the
         *         block then contributes no tints, as vanilla would)
         */
        BlockState appearanceAt(BlockAndTintGetter level, BlockPos pos,
                BlockState state);
    }

    /** Loader-side backend, installed by the platform's client entrypoint. */
    public interface Backend {
        void collect(BlockState state, BlockAndTintGetter level, BlockPos pos,
                IntList output);

        void registerDelegating(AppearanceSource source, List<Block> blocks);
    }

    private static Backend backend;

    /**
     * Registrations made before the platform client bootstrap ran. Fabric does
     * NOT order {@code deds_api}'s client entrypoint ahead of its consumers'
     * (observed 2026-07-26), so a mod may legitimately register first; those
     * are replayed on install.
     */
    private static final List<Runnable> PENDING = new CopyOnWriteArrayList<>();

    private BlockTints() {
    }

    /** Internal: installed by the platform client bootstrap. */
    public static void install(Backend impl) {
        backend = impl;
        for (Runnable pending : PENDING) {
            pending.run();
        }
        PENDING.clear();
    }

    /**
     * Appends the tint colours {@code state} would use at {@code pos} to
     * {@code output}, consulting BOTH the loader's dynamic tint registry and
     * vanilla's static tint sources (see the class javadoc).
     */
    public static void collect(BlockState state, BlockAndTintGetter level,
            BlockPos pos, IntList output) {
        checkInstalled();
        backend.collect(state, level, pos, output);
    }

    /**
     * Registers a tint factory for {@code blocks} that resolves each block's
     * current appearance through {@code source} and collects THAT state's
     * tints, so a grass-covered disguise takes the biome colour instead of the
     * grey default.
     */
    public static void registerDelegating(AppearanceSource source,
            List<Block> blocks) {
        List<Block> copy = List.copyOf(blocks);
        if (backend == null) {
            PENDING.add(() -> backend.registerDelegating(source, copy));
            return;
        }
        backend.registerDelegating(source, copy);
    }

    private static void checkInstalled() {
        if (backend == null) {
            throw new IllegalStateException("BlockTints used before the "
                    + "platform client bootstrap ran (client side only)");
        }
    }
}
