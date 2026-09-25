package com.deds.api.attach;

/**
 * Registers per-player persistent data slots for one mod (Ded's API v1).
 * Register during {@code DedsMod.onInitialize} only.
 */
public interface PlayerDataRegistrar {

    /** Registers a player data slot under the mod's namespace. */
    <T> PlayerDataKey<T> register(String name, PlayerDataSpec<T> spec);
}
