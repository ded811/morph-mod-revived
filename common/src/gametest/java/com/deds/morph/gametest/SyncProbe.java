package com.deds.morph.gametest;

import com.deds.api.attach.PlayerDataKey;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Reads back, from the RUNNING loader, who a registered player-data slot is
 * synced to: the property {@code MorphWave9GameTests.theAcquiredListIsSyncedToItsOwnerOnly}
 * asserts. Each loader stores that decision in its own internals (Fabric's
 * attachment sync predicate, NeoForge's attachment sync handler), which
 * shared test code may not name, so each loader's test mod implements this
 * in its own tree and declares it in
 * {@code META-INF/services/com.deds.morph.gametest.SyncProbe}:
 * {@code fabric/src/gametest/.../FabricSyncProbe} and
 * {@code neoforge/src/gametest/.../NeoForgeSyncProbe}.
 *
 * <p>Gametest mock players never negotiate network channels on either loader,
 * so no packet can be observed; a probe answers from the registered sync rule
 * itself, the same thing the loader consults before sending.</p>
 */
public interface SyncProbe {

    /** Whether the loader has an attachment registered for {@code key}. */
    boolean isRegistered(PlayerDataKey<?> key);

    /** Whether that attachment is synced to clients at all. */
    boolean isSynced(PlayerDataKey<?> key);

    /**
     * Whether {@code owner}'s value of {@code key} can reach
     * {@code receiver}'s client, by ANY sync path the loader has (an update,
     * or the initial sync a player gets when it starts seeing the owner).
     *
     * <p>{@code receiver == owner} asks whether the owner's own client can be
     * sent UPDATES. On Fabric that also covers the initial syncs, because
     * Fabric applies its target predicate on every path. On NeoForge it
     * covers the update path only: Ded's owner-only handler writes nothing on
     * an initial sync, and the owner's copy at login, respawn and dimension
     * change comes from the three resync listeners in {@code DedsApiNeoForge}
     * instead. No server test can see those deliveries (mock players never
     * negotiate channels); the NeoForge client test driver's scenario (i)
     * checks them, in {@code ./gradlew :neoforge:runClientGameTest}, which is
     * not part of {@code check}.</p>
     */
    boolean syncsTo(PlayerDataKey<?> key, ServerPlayer owner, ServerPlayer receiver);

    /**
     * The running loader's probe: exactly one must be declared, from the
     * class loader that loaded this interface (the test mod's own), so a
     * second declaration or none is a setup error, not a silent pick.
     */
    static SyncProbe get() {
        List<SyncProbe> found = ServiceLoader.load(SyncProbe.class,
                SyncProbe.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get).toList();
        if (found.size() != 1) {
            throw new IllegalStateException("expected exactly one SyncProbe "
                    + "(META-INF/services/" + SyncProbe.class.getName()
                    + " in the loader's test mod) but found " + found);
        }
        return found.get(0);
    }
}
