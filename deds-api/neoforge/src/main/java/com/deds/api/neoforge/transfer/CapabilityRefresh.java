package com.deds.api.neoforge.transfer;

import com.deds.api.Deds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Keeps NeoForge's capability caches in step with what a Ded's mod's
 * {@code exposeTanks} / {@code exposeStorage} function answers.
 *
 * <p><b>The difference it covers.</b> On Fabric every lookup, cached or not,
 * asks the mod's function again, so a face that becomes connected (a machine
 * whose side configuration changed) is seen at once. NeoForge's
 * {@code BlockCapabilityCache} keeps the answer it got, including "nothing
 * here", until the capability is invalidated, and leaves invalidation to the
 * mod. Ded's handlers are lazy, which covers every change on a connected
 * face; this class covers a face switching between connected and not:</p>
 * <ul>
 * <li>each capability lookup records, per block entity and side, whether the
 *     answer was "connected";</li>
 * <li>a lookup that answers differently from the last recorded answer means
 *     older caches hold a stale answer: the block entity is invalidated at the
 *     end of the server tick;</li>
 * <li>{@code BlockEntity.setChanged()} (which a mod calls after changing its
 *     side configuration, because the change has to be saved) re-asks the
 *     recorded sides and invalidates at once if any answer changed.</li>
 * </ul>
 * <p>So the one thing a mod must do on NeoForge, and every mod already does,
 * is call {@code setChanged()} after changing what its function answers.</p>
 */
public final class CapabilityRefresh {

    public enum Kind {
        FLUID, ENERGY
    }

    /** The mod's connected-or-not answer for one type and kind. */
    private record Tracked(Kind kind, BiPredicate<BlockEntity, Direction> connected) {
    }

    private static final Map<BlockEntityType<?>, List<Tracked>> TYPES =
            new ConcurrentHashMap<>();

    /** Per block entity: kind -> side -> last recorded answer. */
    private static final Map<BlockEntity, Map<Kind, Map<Direction, Boolean>>> ANSWERS =
            new WeakHashMap<>();

    private static final Set<BlockEntity> PENDING = ConcurrentHashMap.newKeySet();

    private static volatile boolean warned;

    private CapabilityRefresh() {
    }

    static void track(BlockEntityType<?> type, Kind kind,
            BiPredicate<BlockEntity, Direction> connected) {
        TYPES.computeIfAbsent(type, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new Tracked(kind, connected));
    }

    /** Called by a capability provider with the answer it just gave. */
    static void answered(BlockEntity blockEntity, Kind kind, Direction side,
            boolean connected) {
        if (!(blockEntity.getLevel() instanceof ServerLevel)) {
            return;
        }
        Boolean previous;
        synchronized (ANSWERS) {
            previous = ANSWERS.computeIfAbsent(blockEntity, b -> new java.util.EnumMap<>(Kind.class))
                    .computeIfAbsent(kind, k -> new java.util.HashMap<>())
                    .put(side, connected);
        }
        if (previous != null && previous != connected) {
            PENDING.add(blockEntity);
        }
    }

    private static final ThreadLocal<Boolean> IN_CHECK =
            ThreadLocal.withInitial(() -> false);

    /** From {@code BlockEntitySetChangedMixin}, for every block entity. */
    public static void onSetChanged(BlockEntity blockEntity) {
        if (TYPES.isEmpty()) {
            return;
        }
        List<Tracked> tracked = TYPES.get(blockEntity.getType());
        if (tracked == null || IN_CHECK.get()
                || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<Kind, Map<Direction, Boolean>> recorded;
        synchronized (ANSWERS) {
            Map<Kind, Map<Direction, Boolean>> byKind = ANSWERS.get(blockEntity);
            if (byKind == null) {
                return; // never looked up, so no cache can be stale
            }
            recorded = new java.util.EnumMap<>(Kind.class);
            byKind.forEach((kind, sides) -> recorded.put(kind, new java.util.HashMap<>(sides)));
        }
        boolean changed = false;
        IN_CHECK.set(true);
        try {
            for (Tracked t : tracked) {
                Map<Direction, Boolean> sides = recorded.get(t.kind());
                if (sides == null) {
                    continue;
                }
                for (Map.Entry<Direction, Boolean> entry : sides.entrySet()) {
                    boolean now = t.connected().test(blockEntity, entry.getKey());
                    if (now != entry.getValue()) {
                        changed = true;
                        synchronized (ANSWERS) {
                            Map<Kind, Map<Direction, Boolean>> byKind = ANSWERS.get(blockEntity);
                            if (byKind != null && byKind.get(t.kind()) != null) {
                                byKind.get(t.kind()).put(entry.getKey(), now);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                Deds.LOGGER.warn("Ded's API: a mod's exposeTanks/exposeStorage "
                        + "function threw while its block entity at {} was saved; "
                        + "its capability caches were not refreshed",
                        blockEntity.getBlockPos(), e);
            }
            return;
        } finally {
            IN_CHECK.set(false);
        }
        if (changed) {
            PENDING.remove(blockEntity);
            level.invalidateCapabilities(blockEntity.getBlockPos());
        }
    }

    /** From DedsApiNeoForge at the end of every server tick. */
    public static void endOfTick() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<BlockEntity> due = new ArrayList<>(PENDING);
        PENDING.removeAll(due);
        for (BlockEntity blockEntity : due) {
            if (!blockEntity.isRemoved()
                    && blockEntity.getLevel() instanceof ServerLevel level) {
                BlockPos pos = blockEntity.getBlockPos();
                level.invalidateCapabilities(pos);
            }
        }
    }
}
