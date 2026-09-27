package com.deds.api.neoforge;

import com.deds.api.Deds;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The NeoForge side of {@code Deds.initClient}: every mod's client setup,
 * run at the one moment that matches Fabric's rule "every main entrypoint
 * runs before any client entrypoint".
 *
 * <p>A NeoForge {@code dist = CLIENT} mod constructor runs before any registry
 * event, so before any Ded's mod's {@code onInitialize} (see
 * {@link NeoForgePlatform}). Client code there cannot see the mod's blocks,
 * menus, message types or anything else {@code onInitialize} creates. So the
 * constructor queues its setup here, and the client entry class of Ded's API
 * runs the queue from a {@link net.neoforged.bus.api.EventPriority#LOWEST}
 * listener of {@link EntityAttributeCreationEvent}. NeoForge posts that event
 * once, right after the LAST registry event and only if every registry event
 * succeeded, on the same mod-loading thread, and before the game window, the
 * options (key mappings), the client extensions, the menu screens, the
 * renderers and the first resource load exist. So every mod's content is
 * registered, and every client registration seam is still open.</p>
 *
 * <p>What differs from Fabric, and is documented on {@code Deds.initClient}:
 * the queue runs on FML's mod-loading thread, not the render thread, and
 * {@code Minecraft.getInstance()} is still {@code null} there.</p>
 *
 * <p>Order: mod order (the order NeoForge constructs and posts events in),
 * not the order the constructors happened to call in, because FML constructs
 * unordered mods in parallel.</p>
 */
public final class ClientInitQueue {

    private record Entry(String modId, Runnable init) {
    }

    /** Guards {@link #PENDING} and {@link #ran}. */
    private static final Object LOCK = new Object();

    private static final List<Entry> PENDING = new ArrayList<>();

    private static boolean ran;

    /** The thread running the queue, while it runs; null otherwise. */
    private static volatile Thread running;

    private ClientInitQueue() {
    }

    static void enqueue(String modId, Runnable init) {
        if (!FMLEnvironment.getDist().isClient()) {
            Deds.LOGGER.debug("Ded's API: ignoring client init of mod '{}' on a "
                    + "dedicated server", modId);
            return;
        }
        if (running == Thread.currentThread()) {
            // Called from inside another mod's client setup: that is the
            // moment the queue exists for, so run it now, as Fabric runs a
            // nested call inline, naming this mod if it fails (the outer
            // setup's error then carries it as its cause).
            try {
                init.run();
            } catch (RuntimeException | Error e) {
                throw new IllegalStateException("The client setup of mod '" + modId
                        + "', started with Deds.initClient from another mod's client "
                        + "setup, failed: " + e, e);
            }
            return;
        }
        synchronized (LOCK) {
            if (ran) {
                throw new IllegalStateException("Deds.initClient for mod '"
                        + modId + "' called after every mod's client init has "
                        + "run; call it from the mod's client @Mod constructor");
            }
            PENDING.add(new Entry(modId, init));
        }
    }

    /**
     * Runs every queued client init, once, in mod order. A failure stops
     * loading: FML reports any exception out of an event listener against
     * the mod that owns the listener (here Ded's API, whatever is thrown),
     * so the exception's own message is what names the mod whose client
     * setup failed, and it is the cause shown under NeoForge's error.
     */
    public static void runAll(EntityAttributeCreationEvent event) {
        List<Entry> entries;
        synchronized (LOCK) {
            if (ran) {
                return;
            }
            ran = true;
            entries = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        Map<String, Integer> order = new HashMap<>();
        ModList.get().forEachModInOrder(
                container -> order.putIfAbsent(container.getModId(), order.size()));
        // stable: two inits of one mod keep the order the mod asked for
        entries.sort(Comparator.comparingInt(
                e -> order.getOrDefault(e.modId(), Integer.MAX_VALUE)));
        running = Thread.currentThread();
        try {
            for (Entry entry : entries) {
                try {
                    entry.init().run();
                } catch (Throwable t) {
                    String name = ModList.get().getModContainerById(entry.modId())
                            .map(c -> c.getModInfo().getDisplayName() + " ("
                                    + entry.modId() + ")")
                            .orElse("'" + entry.modId() + "'");
                    throw new IllegalStateException("The client setup of mod " + name
                            + ", started with Deds.initClient, failed: " + t, t);
                }
            }
        } finally {
            running = null;
        }
    }
}
