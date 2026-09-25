package com.deds.morph.gametest.neoforge;

import com.deds.api.attach.PlayerDataKey;
import com.deds.morph.gametest.SyncProbe;

import io.netty.buffer.Unpooled;

import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;

/**
 * {@link SyncProbe} on NeoForge: reads the attachment type behind a Ded's
 * player-data key from NeoForge's attachment registry, and its sync handler
 * from {@code AttachmentType.syncHandler} (package-private, no getter; read by
 * reflection, test code only).
 *
 * <p>NeoForge has TWO ways a value reaches another client, and only one of
 * them asks the handler who may see it: an UPDATE sync (a set, a removal)
 * goes to every player {@code sendToPlayer} admits, while the INITIAL sync a
 * player gets on starting to track the owner (and on login, respawn and
 * dimension change) never calls {@code sendToPlayer} and carries whatever
 * {@code write(..., initialSync = true)} writes. So {@link #syncsTo} answers
 * yes if EITHER path would deliver: the handler admits the receiver, or (for
 * anyone but the owner) an initial-sync write of the owner's current value
 * produces bytes. Ded's TARGET_ONLY handler admits only the owner and writes
 * nothing on an initial sync, so the acquired list reaches no one else; a
 * plain predicate handler, the obvious mistake, would pass the first check
 * and fail the second. Mock players never negotiate channels, so no packet
 * can be watched; this reads the rule NeoForge itself consults.</p>
 *
 * <p>For {@code receiver == owner} the answer is only "the owner can be sent
 * updates". The owner's own initial syncs carry nothing either (the same
 * handler), and what delivers the owner's list at login, respawn and
 * dimension change is {@code DedsApiNeoForge}'s three resync listeners, which
 * no server test exercises: the client test driver's scenario (i) does, in
 * {@code ./gradlew :neoforge:runClientGameTest}, outside {@code check}.</p>
 *
 * <p>Declared in this test mod's
 * {@code META-INF/services/com.deds.morph.gametest.SyncProbe}.</p>
 */
public final class NeoForgeSyncProbe implements SyncProbe {

    private static AttachmentType<?> type(PlayerDataKey<?> key) {
        return NeoForgeRegistries.ATTACHMENT_TYPES.getValue(
                Identifier.fromNamespaceAndPath(key.id().namespace(), key.id().path()));
    }

    private static AttachmentSyncHandler<?> handler(AttachmentType<?> type) {
        try {
            Field field = AttachmentType.class.getDeclaredField("syncHandler");
            field.setAccessible(true);
            return (AttachmentSyncHandler<?>) field.get(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read AttachmentType.syncHandler "
                    + "(did NeoForge rename it?)", e);
        }
    }

    @Override
    public boolean isRegistered(PlayerDataKey<?> key) {
        return type(key) != null;
    }

    @Override
    public boolean isSynced(PlayerDataKey<?> key) {
        return handler(type(key)) != null;
    }

    @Override
    public boolean syncsTo(PlayerDataKey<?> key, ServerPlayer owner,
            ServerPlayer receiver) {
        AttachmentSyncHandler<?> handler = handler(type(key));
        if (handler == null) {
            return false;
        }
        if (handler.sendToPlayer(owner, receiver)) {
            return true;
        }
        return receiver != owner && initialSyncWrites(handler, key, owner);
    }

    /** Whether an initial sync of the owner's current value writes anything. */
    @SuppressWarnings("unchecked")
    private static <T> boolean initialSyncWrites(AttachmentSyncHandler<?> handler,
            PlayerDataKey<T> key, ServerPlayer owner) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                owner.registryAccess());
        try {
            ((AttachmentSyncHandler<T>) handler).write(buf, key.get(owner), true);
            return buf.writerIndex() > 0;
        } finally {
            buf.release();
        }
    }
}
