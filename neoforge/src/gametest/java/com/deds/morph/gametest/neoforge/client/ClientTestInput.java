package com.deds.morph.gametest.neoforge.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keyboard as the NeoForge client test driver sees it, read by the test
 * mod's two input mixins.
 *
 * <p>While the driver runs, {@code InputConstants.isKeyDown} answers from
 * {@link #isKeyDown} instead of the real keyboard, exactly as Fabric's client
 * gametest harness does ({@code InputConstantsMixin} there). Two reasons:
 * Morph's favourites radial opens on a RAW poll of the grave key (there is
 * no event to fake, only a key that has to read as held), and nobody typing
 * on the machine during a run may change what it sees (a held Shift turns
 * the selector's scroll into a variant scroll). {@code MouseHandlerTestMixin}
 * keeps the window from grabbing the mouse for the same second reason.</p>
 *
 * <p>Inert unless the game was started with {@code -Dmorph.clientTest=true}
 * (the {@code clientGameTest} run sets it): in any other run with the test
 * mod loaded, both mixins pass the real input through.</p>
 */
public final class ClientTestInput {

    /** {@code -Dmorph.clientTest=true}: the driver and its input hooks are on. */
    public static final boolean ENABLED = Boolean.getBoolean("morph.clientTest");

    /** Keys the driver holds down, by the running version's key code. */
    private static final Set<Integer> HELD = ConcurrentHashMap.newKeySet();

    private ClientTestInput() {
    }

    /** Whether the driver holds {@code key} (an {@code InputConstants.KEY_*} code). */
    public static boolean isKeyDown(int key) {
        return HELD.contains(key);
    }

    static void hold(int key) {
        HELD.add(key);
    }

    static void release(int key) {
        HELD.remove(key);
    }

    static void releaseAll() {
        HELD.clear();
    }
}
