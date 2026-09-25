package com.deds.api.fabric.client;

import com.deds.api.client.BlockFaceSampler;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
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
 * Fabric backend of {@link BlockFaceSampler}: samples a block state's model
 * for its per-{@link Direction} sprite + tint index, and caches the answer per
 * state until the next model bake.
 *
 * <p>The cache is the load-bearing part — see the facade's javadoc. It lives
 * here rather than in {@code com.deds.api.client} only because this class
 * carries the loader's {@code @Environment} stripping annotation; the
 * algorithm is pure vanilla.</p>
 */
@Environment(EnvType.CLIENT)
final class FabricBlockFaceSampler implements BlockFaceSampler.Backend {

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Per-donor-state face materials; cleared on every model (re)bake. */
    private final ConcurrentHashMap<BlockState, BlockFaceSampler.Face[]> cache =
            new ConcurrentHashMap<>();

    /** Cache sentinel for donors whose model parts cannot be enumerated. */
    private static final BlockFaceSampler.Face[] UNCACHEABLE =
            new BlockFaceSampler.Face[0];

    /** Called by the model-loading plugin at the start of every bake. */
    void invalidate() {
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
        BlockFaceSampler.Face[] faces = cache.computeIfAbsent(state, donor -> {
            BlockFaceSampler.Face[] captured = capture(modelOf(donor), random);
            return captured == null ? UNCACHEABLE : captured;
        });
        return faces == UNCACHEABLE ? null : faces;
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
