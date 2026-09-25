package com.deds.api.config;

import com.mojang.serialization.Codec;

import java.util.function.Supplier;

/**
 * Registers Codec-backed JSON config for one mod (Ded's API v1.1). Call
 * during {@link com.deds.api.DedsMod#onInitialize}. There is no vanilla
 * config system, so this is a minimal loader: one JSON file per registration
 * at {@code <configDir>/<modId>/<name>.json}, read (or written with defaults)
 * eagerly, forgiving of partial/legacy JSON and parse errors (falls back to
 * {@code defaults}). {@link Codec} is accepted vanilla surface.
 */
public interface ConfigRegistrar {

    /**
     * Loads (or, if absent, writes and then uses the defaults for) the config
     * file {@code <configDir>/<modId>/<name>.json}. On a parse error the
     * defaults are used and a warning is logged.
     *
     * @param name     the file base name (no extension)
     * @param codec    the codec (de)serializing the config value
     * @param defaults supplies a fresh default value; must never return null
     * @param <C>      the config type (an immutable record recommended)
     */
    <C> ConfigHandle<C> register(String name, Codec<C> codec, Supplier<C> defaults);
}
