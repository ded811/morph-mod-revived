package com.deds.api.neoforge.client;

import com.deds.api.neoforge.NeoForgeMessageType;

import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-side network operations, isolated in their own class so a dedicated
 * server never loads {@link Minecraft} or NeoForge's client networking (the
 * NeoForge counterpart of the Fabric backend's {@code ClientNetOps}). Called
 * only behind the client checks in {@link NeoForgeMessageType} and from the
 * client bootstrap.
 */
public final class NeoForgeClientNet {

    private NeoForgeClientNet() {
    }

    /**
     * Sends a payload to the server. Not connected is Fabric's
     * {@code IllegalStateException} with Fabric's message; NeoForge's
     * distributor would fail with a bare NullPointerException instead.
     *
     * <p>A server that never negotiated the channel (vanilla, or NeoForge
     * without the mod; the payloads are optional, so such a server lets this
     * client join) gets nothing: the payload is dropped silently. That is what
     * Fabric's send amounts to there, since that server ignores it. NeoForge
     * would instead THROW {@code UnsupportedOperationException} from its
     * outbound packet check, on a player's key press.</p>
     */
    public static void send(CustomPacketPayload payload) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            throw new IllegalStateException("Cannot send packets when not in game!");
        }
        if (!connection.hasChannel(payload.type())) {
            return;
        }
        ClientPacketDistributor.sendToServer(payload);
    }

    /**
     * Registers the client handler of every S2C message type, from
     * {@code RegisterClientPayloadHandlersEvent}. NeoForge refuses to finish
     * starting if any clientbound payload lacks one; Fabric does not care. So
     * every S2C type gets a dispatcher here, and the mod's actual listener
     * (from {@code MessageType.listenOnClient}, whenever that comes) is read
     * from the type's slot on each delivery. Runs on NeoForge's default
     * handler thread, the client main thread, where Fabric runs its client
     * receivers too.
     */
    static void registerHandlers(RegisterClientPayloadHandlersEvent event) {
        for (NeoForgeMessageType<?> type : NeoForgeMessageType.serverToClient()) {
            register(event, type);
        }
    }

    private static <T> void register(RegisterClientPayloadHandlersEvent event,
            NeoForgeMessageType<T> type) {
        event.register(type.payloadType(),
                (payload, context) -> type.deliverOnClient(payload.value()));
    }
}
