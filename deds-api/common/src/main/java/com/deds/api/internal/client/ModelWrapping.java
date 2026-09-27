package com.deds.api.internal.client;

import com.deds.api.client.BlockModelWrappers;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs every registered {@link BlockModelWrappers.Wrapper} over one baked
 * block-state model: the loop both loader backends call from their post-bake
 * hook (Fabric's {@code modifyBlockModelAfterBake}, NeoForge's
 * {@code ModelEvent.ModifyBakingResult}), so the two cannot drift apart.
 *
 * <p>Wrappers run in registration order, each seeing the previous result
 * ({@code null} means unchanged). <b>Each wrapper runs on its own</b>: one
 * that throws is skipped for that state and the chain goes on with the model
 * as it was before it, so one mod's broken wrapper costs only its own work,
 * never another mod's (the promise {@link BlockModelWrappers} makes). Only
 * ordinary failures are caught ({@link RuntimeException}, and
 * {@link LinkageError} for a wrapper that references a class that is not
 * there); anything more serious still stops the bake.</p>
 *
 * <p>Logging: the first failure of each wrapper in a model reload is logged
 * with its stack trace and the state it failed on; later failures of the same
 * wrapper in that reload are not, so a wrapper that throws for every state
 * writes one error, not one per block state. The loaders call
 * {@link #beginReload} at the start of every model reload.</p>
 *
 * <p>Thread note: Fabric may bake states in parallel, so this is called from
 * several threads at once; it keeps no state but the concurrent set below.
 * Not API: mod code may never import {@code com.deds.api.internal}.</p>
 */
public final class ModelWrapping {

    private static final Logger LOGGER = LoggerFactory.getLogger("deds_api");

    /** Wrappers whose failure this reload has already logged. */
    private static final Set<BlockModelWrappers.Wrapper> LOGGED = ConcurrentHashMap.newKeySet();

    private ModelWrapping() {
    }

    /** From the loader, at the start of every model reload. */
    public static void beginReload() {
        LOGGED.clear();
    }

    /** The model {@code state} renders with: its baked model, wrapped. */
    public static BlockStateModel apply(BlockState state, BlockStateModel baked) {
        BlockStateModel current = baked;
        for (BlockModelWrappers.Wrapper wrapper : BlockModelWrappers.registered()) {
            BlockStateModel next;
            try {
                next = wrapper.wrap(state, current);
            } catch (RuntimeException | LinkageError e) {
                if (LOGGED.add(wrapper)) {
                    LOGGER.error("Ded's API: the block model wrapper {} failed for {}; it is "
                            + "skipped for that state and the other wrappers still apply. "
                            + "Further failures of this wrapper are not logged until the next "
                            + "resource reload.", wrapper, state, e);
                }
                continue;
            }
            if (next != null) {
                current = next;
            }
        }
        return current;
    }
}
