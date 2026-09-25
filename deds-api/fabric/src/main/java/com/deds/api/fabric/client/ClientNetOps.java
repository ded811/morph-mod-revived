package com.deds.api.fabric.client;

import com.deds.api.fabric.FabricMessageType;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * Client-side network operations, isolated in their own class so a
 * dedicated server never classloads {@link ClientPlayNetworking}. Called
 * only behind environment checks in {@link FabricMessageType}.
 */
@Environment(EnvType.CLIENT)
public final class ClientNetOps {

    private ClientNetOps() {
    }

    /** Sends a payload to the server (must be connected). */
    public static void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /** Registers the client receiver for an S2C payload type. */
    public static <T> void listen(
            CustomPacketPayload.Type<FabricMessageType.Payload<T>> type,
            Consumer<T> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type,
                (payload, context) -> handler.accept(payload.value()));
    }
}
