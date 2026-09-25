package com.deds.api.attach;

import com.mojang.serialization.Codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.Supplier;

/**
 * Declaration of a per-player persistent data slot (Ded's API v1).
 *
 * <p>Accepted vanilla surface in these signatures: {@link Codec} (DFU) for
 * disk persistence and {@link StreamCodec} for network sync — both are the
 * ecosystem-standard serialization currencies and wrapping them would cost
 * more churn than it saves (see docs/ARCHITECTURE.md).</p>
 *
 * @param <T> the stored type (immutable value objects strongly recommended)
 */
public final class PlayerDataSpec<T> {

    /** Who receives live sync of this data. */
    public enum Sync {
        /** Server-only; clients never see it. */
        NONE,
        /** Synced to the owning player AND everyone tracking them. */
        ALL,
        /** Synced only to the owning player. */
        TARGET_ONLY
    }

    private final Codec<T> codec;
    private final Supplier<T> defaultValue;
    private boolean copyOnRespawn = true;
    private Sync sync = Sync.NONE;
    private StreamCodec<? super RegistryFriendlyByteBuf, T> syncCodec;

    private PlayerDataSpec(Codec<T> codec, Supplier<T> defaultValue) {
        this.codec = codec;
        this.defaultValue = defaultValue;
    }

    /** Persistent player data with a disk codec and a default value. */
    public static <T> PlayerDataSpec<T> of(Codec<T> codec,
            Supplier<T> defaultValue) {
        return new PlayerDataSpec<>(codec, defaultValue);
    }

    /** Data is reset on death instead of carried to the respawned player. */
    public PlayerDataSpec<T> resetOnRespawn() {
        this.copyOnRespawn = false;
        return this;
    }

    /** Enables live client sync with the given scope and wire codec. */
    public PlayerDataSpec<T> syncedWith(Sync scope,
            StreamCodec<? super RegistryFriendlyByteBuf, T> wireCodec) {
        this.sync = scope;
        this.syncCodec = wireCodec;
        return this;
    }

    // --- read side (platform implementations) ---

    public Codec<T> codec() {
        return codec;
    }

    public Supplier<T> defaultValue() {
        return defaultValue;
    }

    public boolean copiesOnRespawn() {
        return copyOnRespawn;
    }

    public Sync syncScope() {
        return sync;
    }

    public StreamCodec<? super RegistryFriendlyByteBuf, T> syncCodec() {
        return syncCodec;
    }
}
