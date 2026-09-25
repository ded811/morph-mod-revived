package com.deds.api.fabric;

import com.deds.api.id.BId;
import com.deds.api.net.MessageType;
import com.deds.api.net.NetRegistrar;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Fabric implementation of {@link MessageType}: each registered message
 * becomes its own {@link CustomPacketPayload} type wrapping the user value.
 * Client-only operations route through {@code fabric.client.ClientNetOps},
 * which is never classloaded on a dedicated server.
 */
public final class FabricMessageType<T> implements MessageType<T> {

    /** The wire wrapper carrying the user's message value. */
    public record Payload<T>(FabricMessageType<T> messageType, T value)
            implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return messageType.payloadType;
        }
    }

    private final BId id;
    private final boolean clientToServer;
    final CustomPacketPayload.Type<Payload<T>> payloadType;

    private FabricMessageType(BId id, boolean clientToServer) {
        this.id = id;
        this.clientToServer = clientToServer;
        this.payloadType = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(id.namespace(), id.path()));
    }

    private StreamCodec<RegistryFriendlyByteBuf, Payload<T>> wrap(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return StreamCodec.of(
                (buf, payload) -> codec.encode(buf, payload.value()),
                buf -> new Payload<>(this, codec.decode(buf)));
    }

    static <T> FabricMessageType<T> c2s(String modId, String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            NetRegistrar.C2SHandler<T> handler) {
        FabricMessageType<T> type =
                new FabricMessageType<>(BId.of(modId, name), true);
        PayloadTypeRegistry.serverboundPlay().register(type.payloadType,
                type.wrap(codec));
        ServerPlayNetworking.registerGlobalReceiver(type.payloadType,
                (payload, context) ->
                        handler.handle(payload.value(), context.player()));
        return type;
    }

    static <T> FabricMessageType<T> s2c(String modId, String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        FabricMessageType<T> type =
                new FabricMessageType<>(BId.of(modId, name), false);
        PayloadTypeRegistry.clientboundPlay().register(type.payloadType,
                type.wrap(codec));
        return type;
    }

    @Override
    public BId id() {
        return id;
    }

    @Override
    public void sendToServer(T message) {
        requireDirection(true);
        requireClient("sendToServer");
        com.deds.api.fabric.client.ClientNetOps.send(
                new Payload<>(this, message));
    }

    @Override
    public void sendTo(ServerPlayer player, T message) {
        requireDirection(false);
        ServerPlayNetworking.send(player, new Payload<>(this, message));
    }

    @Override
    public void listenOnClient(Consumer<T> handler) {
        requireDirection(false);
        requireClient("listenOnClient");
        com.deds.api.fabric.client.ClientNetOps.listen(payloadType, handler);
    }

    private void requireDirection(boolean wantC2S) {
        if (clientToServer != wantC2S) {
            throw new IllegalStateException("message " + id + " is "
                    + (clientToServer ? "C2S" : "S2C")
                    + " — wrong direction for this operation");
        }
    }

    private void requireClient(String operation) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            throw new IllegalStateException(operation
                    + " is client-side only (message " + id + ")");
        }
    }
}
