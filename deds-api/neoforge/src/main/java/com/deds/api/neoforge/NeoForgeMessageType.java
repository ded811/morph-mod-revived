package com.deds.api.neoforge;

import com.deds.api.id.BId;
import com.deds.api.net.MessageType;
import com.deds.api.net.NetRegistrar;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * NeoForge implementation of {@link MessageType}: the same wire shape as the
 * Fabric backend (each message is its own {@link CustomPacketPayload} type,
 * id {@code <modid>:<name>}, wrapping the user value), the same direction and
 * client-side guards with the same messages.
 *
 * <p><b>Registration is deferred.</b> NeoForge accepts payload types only
 * inside {@code RegisterPayloadHandlersEvent}, which fires after every mod is
 * constructed. So {@link #c2s}/{@link #s2c} queue the type, and
 * {@link DedsApiNeoForge} registers the whole queue from that event; a
 * registration after it throws {@link IllegalStateException}, as Fabric's
 * registry would refuse a late payload type. Registering the same name in
 * both directions is refused by NeoForge's own registrar (Fabric keeps the two
 * directions apart and would allow it).</p>
 *
 * <p><b>Optional, as on Fabric.</b> The payloads are registered optional
 * ({@link DedsApiNeoForge}), so NeoForge's channel negotiation never refuses a
 * connection because of them. A NeoForge client with the mod can join a server
 * without it, vanilla or NeoForge: its client-to-server messages (Morph's
 * selector messages) are simply not sent there ({@code NeoForgeClientNet.send}
 * drops them), and {@link #sendTo} never sends to a client that lacks the
 * channel. A vanilla client can join a NeoForge server with the mod. That is
 * how Fabric behaves too. Left required, NeoForge would disconnect the first
 * two at configuration.</p>
 *
 * <p><b>What still differs from Fabric:</b> a NeoForge client WITHOUT the mod
 * cannot join a NeoForge server WITH it when the mod registers SYNCED player
 * data (Morph does). That is NeoForge's registry sync, not the payloads: every
 * attachment type with a sync handler is added to the synced registry
 * {@code neoforge:synced_attachment_types}, and a client missing entries the
 * server's snapshot names is disconnected ("server-with-unknown-keys"). A
 * client with EXTRA entries is accepted, which is why the other directions
 * work.</p>
 *
 * <p>Thread note: the queue is concurrent, because FML constructs mods in
 * parallel and two mods may register messages at the same time.</p>
 */
public final class NeoForgeMessageType<T> implements MessageType<T> {

    /** The wire wrapper carrying the user's message value. */
    public record Payload<T>(NeoForgeMessageType<T> messageType, T value)
            implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return messageType.payloadType;
        }
    }

    /** Every message type of every Ded's mod, in registration order. */
    private static final List<NeoForgeMessageType<?>> ALL = new CopyOnWriteArrayList<>();

    /** Set once {@code RegisterPayloadHandlersEvent} has taken the queue. */
    private static volatile boolean registered;

    private final BId id;
    private final boolean clientToServer;
    final CustomPacketPayload.Type<Payload<T>> payloadType;
    private final StreamCodec<RegistryFriendlyByteBuf, Payload<T>> wireCodec;
    private final NetRegistrar.C2SHandler<T> serverHandler;

    /**
     * The client handler of an S2C message. NeoForge refuses to start unless
     * every clientbound payload has a client handler, registered in one fixed
     * event; {@link #listenOnClient} may come later (or never), so the handler
     * registered there is a dispatcher reading this slot. First wins, like
     * Fabric's {@code registerGlobalReceiver}: a second listener is silently
     * ignored.
     */
    final AtomicReference<Consumer<T>> clientHandler = new AtomicReference<>();

    private NeoForgeMessageType(BId id, boolean clientToServer,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            NetRegistrar.C2SHandler<T> serverHandler) {
        this.id = id;
        this.clientToServer = clientToServer;
        this.payloadType = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(id.namespace(), id.path()));
        this.wireCodec = StreamCodec.of(
                (buf, payload) -> codec.encode(buf, payload.value()),
                buf -> new Payload<>(this, codec.decode(buf)));
        this.serverHandler = serverHandler;
    }

    static <T> NeoForgeMessageType<T> c2s(String modId, String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            NetRegistrar.C2SHandler<T> handler) {
        return enqueue(new NeoForgeMessageType<>(BId.of(modId, name), true,
                codec, handler));
    }

    static <T> NeoForgeMessageType<T> s2c(String modId, String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return enqueue(new NeoForgeMessageType<>(BId.of(modId, name), false,
                codec, null));
    }

    private static synchronized <T> NeoForgeMessageType<T> enqueue(
            NeoForgeMessageType<T> type) {
        if (registered) {
            throw new IllegalStateException("message " + type.id + " registered "
                    + "after NeoForge's RegisterPayloadHandlersEvent; register "
                    + "messages during DedsMod.onInitialize (mod construction)");
        }
        ALL.add(type);
        return type;
    }

    /**
     * Registers every queued type with NeoForge, from
     * {@code RegisterPayloadHandlersEvent}. C2S handlers run on NeoForge's
     * default handler thread, the server thread, which is where Fabric runs
     * a {@code C2SHandler} too.
     */
    static synchronized void registerAll(PayloadRegistrar registrar) {
        registered = true;
        for (NeoForgeMessageType<?> type : ALL) {
            type.registerWith(registrar);
        }
    }

    private void registerWith(PayloadRegistrar registrar) {
        if (clientToServer) {
            registrar.playToServer(payloadType, wireCodec,
                    (payload, context) -> serverHandler.handle(payload.value(),
                            (ServerPlayer) context.player()));
        } else {
            registrar.playToClient(payloadType, wireCodec);
        }
    }

    /**
     * Every S2C type, for the client bootstrap's handler registration.
     * Public for the client package only; not API.
     */
    public static List<NeoForgeMessageType<?>> serverToClient() {
        return ALL.stream().filter(t -> !t.clientToServer).toList();
    }

    /** This message's payload type. Public for the client package; not API. */
    public CustomPacketPayload.Type<Payload<T>> payloadType() {
        return payloadType;
    }

    /**
     * Delivers a received S2C value to the listener, if one was set. Public
     * for the client package only; not API.
     */
    public void deliverOnClient(T value) {
        Consumer<T> handler = clientHandler.get();
        if (handler != null) {
            handler.accept(value);
        }
    }

    @Override
    public BId id() {
        return id;
    }

    @Override
    public void sendToServer(T message) {
        requireDirection(true);
        requireClient("sendToServer");
        com.deds.api.neoforge.client.NeoForgeClientNet.send(
                new Payload<>(this, message));
    }

    /**
     * Fabric sends unconditionally and a client without the channel just
     * drops the payload. NeoForge would instead THROW for a connection that
     * never negotiated the channel, and that includes every gametest mock
     * player, so without this check every kill in a test (Morph broadcasts
     * its acquisition effect to the whole level) would throw. Not sending is
     * what Fabric's send amounts to for such a client.
     */
    @Override
    public void sendTo(ServerPlayer player, T message) {
        requireDirection(false);
        if (player.connection != null && player.connection.hasChannel(payloadType)) {
            PacketDistributor.sendToPlayer(player, new Payload<>(this, message));
        }
    }

    @Override
    public void listenOnClient(Consumer<T> handler) {
        requireDirection(false);
        requireClient("listenOnClient");
        clientHandler.compareAndSet(null, handler);
    }

    private void requireDirection(boolean wantC2S) {
        if (clientToServer != wantC2S) {
            throw new IllegalStateException("message " + id + " is "
                    + (clientToServer ? "C2S" : "S2C")
                    + " — wrong direction for this operation");
        }
    }

    private void requireClient(String operation) {
        if (!FMLEnvironment.getDist().isClient()) {
            throw new IllegalStateException(operation
                    + " is client-side only (message " + id + ")");
        }
    }
}
