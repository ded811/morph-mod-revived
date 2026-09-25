package com.deds.api.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Minimal event with listener registration, owned by Ded's API so mod
 * code never subscribes to loader-specific event buses.
 *
 * <p>Two listener shapes are supported: fire-and-forget {@link Consumer} via
 * {@link #register}/{@link #invoke}, and value-returning {@link Function} via
 * {@link #registerReturning}/{@link #invokeUntil} (for interception events that
 * a listener can handle, cancelling further processing).</p>
 *
 * @param <T> listener callback payload
 */
public final class Event<T> {

    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final List<Function<T, ?>> returningListeners =
            new CopyOnWriteArrayList<>();

    public void register(Consumer<T> listener) {
        listeners.add(listener);
    }

    /** Platform implementations fire this from the loader's native event. */
    public void invoke(T payload) {
        for (Consumer<T> listener : listeners) {
            listener.accept(payload);
        }
    }

    /**
     * Registers a value-returning listener consulted by {@link #invokeUntil}.
     * Distinct method name (not an overload of {@link #register}) so a
     * value-returning method reference is never ambiguous between the two shapes.
     *
     * @param <R> the result type (uniform per event; e.g. {@code InteractionResult})
     */
    public <R> void registerReturning(Function<T, R> listener) {
        returningListeners.add(listener);
    }

    /**
     * Invokes returning-listeners in registration order and returns the first
     * result that is not equal to {@code stopValue} (the "handled, stop"
     * short-circuit); returns {@code stopValue} if every listener passes. All
     * returning-listeners of one event must share the result type {@code R}.
     */
    @SuppressWarnings("unchecked")
    public <R> R invokeUntil(T payload, R stopValue) {
        for (Function<T, ?> listener : returningListeners) {
            R result = (R) listener.apply(payload);
            if (result != null && !result.equals(stopValue)) {
                return result;
            }
        }
        return stopValue;
    }
}
