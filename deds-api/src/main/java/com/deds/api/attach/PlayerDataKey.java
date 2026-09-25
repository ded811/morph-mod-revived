package com.deds.api.attach;

import com.deds.api.id.BId;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Handle to a registered per-player data slot (Ded's API v1). Reads work on
 * both sides (for {@link PlayerDataSpec.Sync synced} data the client sees
 * the live value); writes are server-authoritative.
 *
 * @param <T> the stored type
 */
public interface PlayerDataKey<T> {

    /** The registered id, e.g. {@code deds_morph:morph_state}. */
    BId id();

    /**
     * The player's current value, never null (the spec's default fills in).
     * Client-side reads require the spec to be {@linkplain
     * PlayerDataSpec#syncedWith synced}; unsynced data reads the default on
     * the client.
     */
    T get(Player player);

    /** Sets the value (server side; persists and syncs per the spec). */
    void set(ServerPlayer player, T value);
}
