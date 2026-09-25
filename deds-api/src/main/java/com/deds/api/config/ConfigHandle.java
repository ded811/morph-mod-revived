package com.deds.api.config;

/**
 * Handle to one registered config file (Ded's API v1.1). Created via
 * {@link ConfigRegistrar#register}. The value is server-authoritative; there
 * is no in-game editor.
 *
 * @param <C> the config type
 */
public interface ConfigHandle<C> {

    /** The current value, never null. */
    C get();

    /** Replaces the in-memory value AND rewrites the JSON file. */
    void set(C value);

    /** Re-reads the value from disk (writing defaults if the file is gone). */
    void reload();
}
