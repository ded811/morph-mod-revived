package com.deds.api.internal.client;

import com.deds.api.client.ClientKeys;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The loader-neutral half of the {@link ClientKeys} backend: the mappings'
 * construction, the registered press and held bindings, and the per-tick
 * dispatch loop. A loader's client bootstrap does only what needs that
 * loader: it creates the per-mod controls category, builds each mapping with
 * {@link #newMapping}, registers it with its own key-mapping registry, hands
 * it over with {@link #addPress} / {@link #addHeld}, and calls {@link #tick}
 * at the end of every client tick.
 *
 * <p>Everything here used to live in the Fabric client entrypoint
 * ({@code DedsApiFabricClient}) and is unchanged. The two things that differ
 * between Minecraft versions (reading a binding's raw device state, and
 * mapping a portable default onto the running version's
 * {@code InputConstants}) are in {@link RawKeys}, the only key-machinery
 * class with a per-version overlay.</p>
 *
 * <p>Not API: mods use {@link ClientKeys}.</p>
 */
public final class KeyDispatcher {

    private static final List<PressKey> KEYS = new CopyOnWriteArrayList<>();
    private static final List<HeldKey> HELD_KEYS = new CopyOnWriteArrayList<>();

    private KeyDispatcher() {
    }

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
     * Builds one mapping for {@code key.<modId>.<name>} under {@code category},
     * not yet registered anywhere: the caller registers it with its loader.
     *
     * <p>{@link ClientKeys#UNBOUND} (-1) is the API's "no key". A MOUSE
     * {@code getOrCreate(-1)} would mint a phantom bound-looking mouse key
     * ({@code isUnbound()} false, garbage label, dead Reset), so UNBOUND always
     * routes to the keyboard side regardless of the requested type, with the
     * running version's unbound code ({@link RawKeys#typeFor},
     * {@link RawKeys#codeFor}). The typed ctor ({@code KeyMapping(String,
     * InputConstants.Type, int, Category)}, javap-verified) carries mouse
     * -button defaults (v1.2) while staying fully rebindable.</p>
     */
    public static RawKeyMapping newMapping(String modId, String name,
            ClientKeys.InputType type, int defaultCode,
            KeyMapping.Category category) {
        InputConstants.Type vanillaType = RawKeys.typeFor(type, defaultCode);
        int vanillaCode = RawKeys.codeFor(defaultCode);
        return new RawKeyMapping("key." + modId + "." + name, vanillaType,
                vanillaCode, category);
    }

    /** Adds a press binding; {@code onPress} runs once per press. */
    public static void addPress(RawKeyMapping key, Runnable onPress) {
        KEYS.add(new PressKey(key, onPress));
    }

    /** Adds a held binding; {@code onChange} gets both edges. */
    public static void addHeld(RawKeyMapping key, Consumer<Boolean> onChange) {
        HELD_KEYS.add(new HeldKey(key, onChange));
    }

    /**
     * The dispatch loop, run by the loader at the END of every client tick
     * (Fabric: {@code ClientTickEvents.END_CLIENT_TICK}; NeoForge:
     * {@code ClientTickEvent.Post}).
     */
    public static void tick(Minecraft minecraft) {
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
                boolean down = !screenOpen && RawKeys.isDown(minecraft, press.key);
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
            boolean down = RawKeys.isDown(minecraft, held.key);
            if (down != held.wasDown) {
                held.wasDown = down;
                held.onChange.accept(down);
            }
        }
    }
}
