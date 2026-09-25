package com.deds.api.net;

import com.deds.api.id.BId;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Handle to a registered network message (Ded's API v1). Created via
 * {@link NetRegistrar}; the direction is fixed at registration.
 *
 * @param <T> the message type (immutable records recommended)
 */
public interface MessageType<T> {

    /** The registered id, e.g. {@code deds_morph:select}. */
    BId id();

    /** Client → server send. Client side only ({@code C2S} messages). */
    void sendToServer(T message);

    /** Server → client send. Server side only ({@code S2C} messages). */
    void sendTo(ServerPlayer player, T message);

    /**
     * Registers the client-side handler for an {@code S2C} message. Call
     * from client initialization only (a client entrypoint); the handler
     * runs on the client main thread.
     */
    void listenOnClient(Consumer<T> handler);
}
