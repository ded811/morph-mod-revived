package com.deds.api.client;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-{@link Direction} face-material sampling for an arbitrary block state
 * (Ded's API v1.5) — the "what does that block look like on each side?" half
 * of the shared camouflage / framed-block client glue.
 *
 * <p>Used by model wrappers that keep their OWN baked geometry (a door panel,
 * a stair step, a Carpenter's slope) and re-texture every quad with another
 * block's sprite: sample the donor state once, then rebake each quad with
 * {@code materialBake(face.material(), BAKE_LOCK_UV)} and adopt
 * {@code face.tintIndex()} so biome colouring still flows.</p>
 *
 * <p><b>The backend's cache is mandatory, not an optimisation.</b> Secret
 * Rooms measured the BE→donor→model→parts derivation re-running up to four
 * times per mesh pass (emit + geometry key + particle + material flags) for
 * every camo block with ONE appearance slot; Carpenter's Blocks has seven.
 * Face materials are position-independent, so one entry serves every block
 * wearing that state. The trade-off is that a weighted-variant donor model is
 * sampled once instead of re-rolled per emission — a stable appearance,
 * arguably the better camouflage. The platform clears the cache at the start
 * of every model bake, so no sprite from a previous atlas survives a resource
 * reload.</p>
 *
 * <p>Thread note: called from chunk-meshing worker threads; the backend's
 * cache is concurrent and every value it holds is immutable.</p>
 *
 * <p>Lifted from Secret Rooms' {@code CamoRetexturedBlockStateModel} when
 * Carpenter's Blocks became the third consumer (MOD-COOKBOOK §14). The
 * sampling implementation lives in the platform backend so this facade stays
 * free of loader imports, exactly like {@link ClientKeys} — see
 * ARCHITECTURE.md's boundary rule.</p>
 */
public final class BlockFaceSampler {

    /** Sprite + tint index for one face of a sampled block state. */
    public record Face(Material.Baked material, int tintIndex) {
    }

    /** Loader-side backend, installed by the platform's client entrypoint. */
    public interface Backend {
        BlockStateModel modelOf(BlockState state);

        Face[] facesOf(BlockState state, RandomSource random);
    }

    private static Backend backend;

    private BlockFaceSampler() {
    }

    /** Internal: installed by the platform client bootstrap. */
    public static void install(Backend impl) {
        backend = impl;
    }

    /** The currently baked model of {@code state}. */
    public static BlockStateModel modelOf(BlockState state) {
        checkInstalled();
        return backend.modelOf(state);
    }

    /**
     * The per-{@link Direction} face materials of {@code state}, indexed by
     * {@link Direction#get3DDataValue()}, or {@code null} when the state's
     * model cannot enumerate parts (an exotic dynamic model) — in which case
     * the caller should fall back to its own textures.
     *
     * <p>Faces with no quad on that side borrow the model's particle sprite,
     * so the returned array never contains nulls.</p>
     */
    public static Face[] facesOf(BlockState state, RandomSource random) {
        checkInstalled();
        return backend.facesOf(state, random);
    }

    private static void checkInstalled() {
        if (backend == null) {
            throw new IllegalStateException("BlockFaceSampler used before the "
                    + "platform client bootstrap ran (client side only)");
        }
    }
}
