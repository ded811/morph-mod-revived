package com.deds.api.client;

import java.util.function.Consumer;

/**
 * Client key-binding registration (Ded's API v1; held-state + mouse defaults
 * v1.2). Call from a mod's CLIENT entrypoint only. Keys appear in the controls
 * screen under a per-mod category ({@code key.category.<modid>.main}); the
 * binding's own label is {@code key.<modid>.<name>} — provide both in the lang
 * file.
 *
 * <p>Bindings may ship with a default key ({@link #register(String, String,
 * int, Runnable)}, a GLFW key code — use {@link #UNBOUND} for none) so a mod
 * can reproduce a legacy default like {@code [} / {@code ]}, or register
 * unbound ({@link #register(String, String, Runnable)}) and let the player
 * assign the key. Either way the binding is fully rebindable in the vanilla
 * controls screen.</p>
 *
 * <p>v1.2 additions (TrailMix, the second consumer):</p>
 * <ul>
 * <li>{@link #register(String, String, InputType, int, Runnable)} — a default
 *     on another input device (TrailMix's fireball defaults to MIDDLE MOUSE,
 *     the original's binding). {@code InputType.MOUSE} + a GLFW mouse-button
 *     code (left 0, right 1, middle 2).</li>
 * <li>{@link #registerHeld(String, String, int, Consumer)} — HELD-state
 *     bindings: the callback fires with {@code true} on press and {@code false}
 *     on release (the backend polls the RAW device state of the current
 *     binding once per client tick), for hold-to-steer controls like
 *     TrailMix's seven flight keys. Completes the API-ROADMAP v1 "per-tick
 *     pressed queries" promise.</li>
 * </ul>
 *
 * <p><b>Held-through-GUI contract</b> (v1.2): while ANY screen is open (chat,
 * inventory, pause…) a held binding's state FREEZES — no edges fire, a key
 * held before the screen opened stays logically held throughout. Vanilla's
 * {@code KeyMapping.releaseAll()} on screen open therefore never fabricates a
 * release edge (the backend polls raw GLFW state, not the mapping's
 * down-flag). On the first tick after the screen closes the physical state is
 * re-read and AT MOST ONE edge fires per binding if it changed while the
 * screen was open — on every platform, including macOS, where vanilla never
 * restores key state after a mouse grab. This mirrors the original TrailMix
 * key state machine (frozen while {@code currentScreen != null}, raw
 * {@code Keyboard}/{@code Mouse} polling).</p>
 */
public final class ClientKeys {

    /**
     * GLFW "no key" code (== {@code InputConstants.UNKNOWN.getValue()}) — pass
     * as the default to register a binding unbound. Kept as a plain int so this
     * portable facade needs no blaze3d import. Registering
     * {@link InputType#MOUSE} with {@code UNBOUND} yields a genuinely UNBOUND
     * binding (the backend routes the sentinel to the keyboard-side UNKNOWN
     * key — a raw mouse "-1" would mint a phantom bound-looking button).
     */
    public static final int UNBOUND = -1;

    /**
     * The input device a binding's DEFAULT code refers to (the binding itself
     * stays rebindable to any device in the controls screen). Portable mirror
     * of the vanilla {@code InputConstants.Type} the backend maps to.
     */
    public enum InputType {
        /** GLFW keyboard key code (the default everywhere else). */
        KEYBOARD,
        /** GLFW mouse button code: left 0, right 1, middle 2. */
        MOUSE
    }

    /** Loader-side backend, installed by the platform's client entrypoint. */
    public interface Backend {
        void register(String modId, String name, InputType type,
                int defaultCode, Runnable onPress);

        void registerHeld(String modId, String name, int defaultKey,
                Consumer<Boolean> onChange);
    }

    private static Backend backend;

    /**
     * Bindings registered before the platform client bootstrap ran, replayed
     * on install. Fabric does NOT guarantee that {@code deds_api}'s client
     * entrypoint runs before its consumers' (observed 2026-07-26 with Secret
     * Rooms), so "register early" must be legal rather than a crash.
     */
    private static final java.util.List<Runnable> PENDING =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private ClientKeys() {
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
     * Registers an UNBOUND key binding; {@code onPress} runs on the client
     * main thread once per press. The player assigns the key.
     */
    public static void register(String modId, String name, Runnable onPress) {
        register(modId, name, UNBOUND, onPress);
    }

    /**
     * Registers a key binding with a default GLFW key code ({@link #UNBOUND}
     * for none); {@code onPress} runs on the client main thread once per press.
     * The binding stays rebindable in the vanilla controls screen.
     */
    public static void register(String modId, String name, int defaultKey,
            Runnable onPress) {
        register(modId, name, InputType.KEYBOARD, defaultKey, onPress);
    }

    /**
     * Registers a key binding whose DEFAULT lives on another input device
     * (v1.2): {@code InputType.MOUSE} + a GLFW mouse-button code reproduces a
     * legacy mouse default (e.g. TrailMix's middle-mouse fireball). Otherwise
     * identical to {@link #register(String, String, int, Runnable)}.
     *
     * <p>While the CURRENT binding is a mouse button, presses dispatch from
     * RAW rising-edge polling (screen closed), not the vanilla click queue —
     * so a button shared with a vanilla bind (middle mouse vs pick-block)
     * fires BOTH consumers, like legacy raw-polling mods did. Rebinding to a
     * keyboard key restores ordinary click consumption.</p>
     */
    public static void register(String modId, String name, InputType type,
            int defaultCode, Runnable onPress) {
        if (backend == null) {
            PENDING.add(() ->
                    backend.register(modId, name, type, defaultCode, onPress));
            return;
        }
        backend.register(modId, name, type, defaultCode, onPress);
    }

    /**
     * Registers a HELD-state key binding (v1.2): {@code onChange} runs on the
     * client main thread with {@code true} on the press edge and {@code false}
     * on the release edge (RAW device state of the current binding polled once
     * per client tick). Default is a GLFW keyboard code ({@link #UNBOUND} for
     * none); rebindable as usual — including to a mouse button.
     *
     * <p>Held state honors the class-level <b>held-through-GUI contract</b>:
     * frozen while a screen is open, re-synced from the physical device with
     * at most one edge when it closes.</p>
     */
    public static void registerHeld(String modId, String name, int defaultKey,
            Consumer<Boolean> onChange) {
        if (backend == null) {
            PENDING.add(() ->
                    backend.registerHeld(modId, name, defaultKey, onChange));
            return;
        }
        backend.registerHeld(modId, name, defaultKey, onChange);
    }
}
