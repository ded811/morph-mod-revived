package com.deds.api.internal.client;

import com.deds.api.client.BlockFaceSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link BlockFaceSampler} backend both loaders install: samples a block
 * state's model for its per-{@link Direction} sprite + tint index, and caches
 * the answer per state until the next model bake. The algorithm is pure
 * vanilla; each loader calls {@link #invalidate()} at the start of every model
 * reload (Fabric from its model-loading plugin; NeoForge from
 * {@code ModelEvent.RegisterStandalone}, posted once per reload before
 * baking).
 *
 * <p>That alone is not enough: the new models are only published at the END
 * of the reload, and the level keeps meshing meanwhile, so a camo block
 * re-meshed during the reload would cache faces sampled from the OLD models,
 * whose sprites belong to the old atlas, and keep them afterwards. So every
 * cached answer also belongs to the model set it was sampled from, and the
 * first call that sees a different live set drops the cache.</p>
 *
 * <p>The cache is the load-bearing part: see the facade's javadoc. Not API:
 * mod code may never import {@code com.deds.api.internal}.</p>
 */
public final class CachingBlockFaceSampler implements BlockFaceSampler.Backend {

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Per-donor-state face materials; cleared on every model (re)bake. */
    private final ConcurrentHashMap<BlockState, BlockFaceSampler.Face[]> cache =
            new ConcurrentHashMap<>();

    /** Cache sentinel for donors whose model parts cannot be enumerated. */
    private static final BlockFaceSampler.Face[] UNCACHEABLE =
            new BlockFaceSampler.Face[0];

    /** The live model set the cached answers were sampled from. */
    private volatile BlockStateModelSet sampledFrom;

    /** Called by the loader at the start of every model bake. */
    public void invalidate() {
        cache.clear();
    }

    @Override
    public BlockStateModel modelOf(BlockState state) {
        return Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(state);
    }

    @Override
    public BlockFaceSampler.Face[] facesOf(BlockState state,
            RandomSource random) {
        BlockStateModelSet live = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet();
        if (live != sampledFrom) {
            synchronized (this) {
                if (live != sampledFrom) {
                    cache.clear();
                    sampledFrom = live;
                }
            }
        }
        BlockFaceSampler.Face[] faces = cache.computeIfAbsent(state, donor -> {
            BlockFaceSampler.Face[] captured = capture(live.get(donor), random);
            return captured == null ? UNCACHEABLE : captured;
        });
        // A copy: a caller writing into its array must not change the cache
        // everyone else reads. Six references; the Faces themselves are shared.
        return faces == UNCACHEABLE ? null : faces.clone();
    }

    private static BlockFaceSampler.Face[] capture(BlockStateModel model,
            RandomSource random) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        try {
            model.collectParts(random, parts);
        } catch (RuntimeException e) {
            return null;
        }
        BlockFaceSampler.Face[] faces =
                new BlockFaceSampler.Face[DIRECTIONS.length];
        boolean gaps = false;
        for (Direction direction : DIRECTIONS) {
            BakedQuad quad = firstQuad(parts, direction);
            if (quad == null) {
                gaps = true;
            } else {
                faces[direction.get3DDataValue()] = new BlockFaceSampler.Face(
                        new Material.Baked(quad.materialInfo().sprite(), false),
                        quad.materialInfo().tintIndex());
            }
        }
        if (gaps) {
            BlockFaceSampler.Face fallback =
                    new BlockFaceSampler.Face(model.particleMaterial(), -1);
            for (int i = 0; i < faces.length; i++) {
                if (faces[i] == null) {
                    faces[i] = fallback;
                }
            }
        }
        return faces;
    }

    private static BakedQuad firstQuad(List<BlockStateModelPart> parts,
            Direction direction) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(direction);
            if (!quads.isEmpty()) {
                return quads.get(0);
            }
        }
        return null;
    }
}
