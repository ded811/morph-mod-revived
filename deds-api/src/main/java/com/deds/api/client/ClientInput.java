package com.deds.api.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client input the vanilla / Fabric surface does not expose outside a
 * {@code Screen} (Ded's API v1). A HUD overlay that stays open while the
 * player keeps moving — the Morph selector strip and its favourites radial —
 * needs two things a raw key/button poll cannot give:
 *
 * <ul>
 *   <li>the <b>mouse wheel</b>, to scroll the selector, and a way to
 *       <b>cancel</b> the vanilla hotbar slot change it would otherwise do
 *       ({@link #addScrollListener} — a listener returns {@code true} to
 *       consume the scroll); and</li>
 *   <li>the raw <b>grabbed-mouse motion</b> delta, to aim the radial, plus a
 *       toggle that <b>suppresses the camera turn</b> so aiming does not spin
 *       the player's view ({@link #addMotionListener} +
 *       {@link #setSuppressCameraTurn}).</li>
 * </ul>
 *
 * <p>Both are fed by the platform's {@code MouseHandler} mixin, which calls the
 * {@code dispatch*} hooks below. Those hooks are public only so the platform
 * layer can reach them across packages — <b>mod code must not call them</b>;
 * mods use the {@code add*Listener} / {@code setSuppressCameraTurn} surface.
 * Listeners fire on the client main thread.</p>
 */
public final class ClientInput {

    /** Mouse-wheel listener; return {@code true} to consume (cancel vanilla). */
    @FunctionalInterface
    public interface ScrollListener {
        boolean onScroll(double horizontal, double vertical);
    }

    /** Raw grabbed-mouse motion listener (per-frame accumulated delta). */
    @FunctionalInterface
    public interface MotionListener {
        void onMotion(double dx, double dy);
    }

    private static final List<ScrollListener> SCROLL =
            new CopyOnWriteArrayList<>();
    private static final List<MotionListener> MOTION =
            new CopyOnWriteArrayList<>();
    private static volatile boolean suppressCameraTurn;

    private ClientInput() {
    }

    /** Registers a mouse-wheel listener. Client side only. */
    public static void addScrollListener(ScrollListener listener) {
        SCROLL.add(listener);
    }

    /** Registers a grabbed-mouse motion listener. Client side only. */
    public static void addMotionListener(MotionListener listener) {
        MOTION.add(listener);
    }

    /**
     * While {@code true}, the platform mixin swallows the grabbed-mouse motion
     * (feeding it to the motion listeners instead of the camera) so the view
     * stays put — used to lock the camera during radial aim. Remember to clear
     * it when the overlay closes.
     */
    public static void setSuppressCameraTurn(boolean suppress) {
        suppressCameraTurn = suppress;
    }

    /** Whether the camera turn is currently suppressed. */
    public static boolean isSuppressCameraTurn() {
        return suppressCameraTurn;
    }

    /**
     * Internal (platform mixin only): dispatch a scroll event; returns whether
     * any listener consumed it, so the caller can cancel the vanilla handling.
     */
    public static boolean dispatchScroll(double horizontal, double vertical) {
        boolean consumed = false;
        for (ScrollListener listener : SCROLL) {
            consumed |= listener.onScroll(horizontal, vertical);
        }
        return consumed;
    }

    /** Internal (platform mixin only): dispatch a grabbed-mouse motion delta. */
    public static void dispatchMotion(double dx, double dy) {
        for (MotionListener listener : MOTION) {
            listener.onMotion(dx, dy);
        }
    }
}
