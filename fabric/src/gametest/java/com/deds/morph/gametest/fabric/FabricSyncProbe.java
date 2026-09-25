package com.deds.morph.gametest.fabric;

import com.deds.api.attach.PlayerDataKey;
import com.deds.morph.gametest.SyncProbe;

import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * {@link SyncProbe} on Fabric: reads the registered Fabric attachment behind a
 * Ded's player-data key and its {@link AttachmentSyncPredicate}. This is the
 * reflection {@code MorphWave9GameTests} did itself before the NeoForge port
 * (moved here, unchanged, because shared test code may not name Fabric
 * internals). Fabric applies that one predicate on every sync path, initial
 * syncs included, so the predicate alone answers {@link #syncsTo}.
 *
 * <p>Declared in this test mod's
 * {@code META-INF/services/com.deds.morph.gametest.SyncProbe}.</p>
 */
public final class FabricSyncProbe implements SyncProbe {

    /** The registered Fabric attachment behind one of our player-data keys. */
    private static AttachmentType<?> attachment(PlayerDataKey<?> key) {
        try {
            Class<?> registry = Class.forName(
                    "net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl");
            Method get = registry.getMethod("get", Identifier.class);
            return (AttachmentType<?>) get.invoke(null,
                    Identifier.fromNamespaceAndPath(key.id().namespace(),
                            key.id().path()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "could not read attachment " + key.id(), e);
        }
    }

    /** The attachment's sync predicate — {@code all()} vs {@code targetOnly()}
     *  is EXACTLY the privacy property under test. */
    private static AttachmentSyncPredicate syncPredicateOf(AttachmentType<?> type) {
        try {
            Method accessor = type.getClass().getMethod("syncPredicate");
            accessor.setAccessible(true);
            return (AttachmentSyncPredicate) accessor.invoke(type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read syncPredicate", e);
        }
    }

    @Override
    public boolean isRegistered(PlayerDataKey<?> key) {
        return attachment(key) != null;
    }

    @Override
    public boolean isSynced(PlayerDataKey<?> key) {
        return attachment(key).isSynced();
    }

    @Override
    public boolean syncsTo(PlayerDataKey<?> key, ServerPlayer owner,
            ServerPlayer receiver) {
        return syncPredicateOf(attachment(key))
                .test((AttachmentTarget) owner, receiver);
    }
}
