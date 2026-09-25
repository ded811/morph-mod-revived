package com.deds.api.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers network messages for one mod (Ded's API v1). Register during
 * {@code DedsMod.onInitialize} only (both sides — registration must be
 * symmetric). {@link StreamCodec}/{@link RegistryFriendlyByteBuf} are
 * accepted vanilla surface (see docs/ARCHITECTURE.md).
 */
public interface NetRegistrar {

    /** Handles a client→server message on the server thread. */
    @FunctionalInterface
    interface C2SHandler<T> {
        void handle(T message, ServerPlayer sender);
    }

    /** Registers a client→server message and its server handler. */
    <T> MessageType<T> registerC2S(String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            C2SHandler<T> handler);

    /**
     * Registers a server→client message. The client handler attaches via
     * {@link MessageType#listenOnClient} from a client entrypoint.
     */
    <T> MessageType<T> registerS2C(String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec);
}
