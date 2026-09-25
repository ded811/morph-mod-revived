package com.deds.api.registry;

import com.deds.api.id.BId;

import java.util.function.Supplier;

/**
 * A reference to a registered game object (block, item, ...).
 *
 * <p>Handles are returned at registration time and are safe to store in
 * {@code static final} fields. {@link #get()} must not be called before the
 * game's registry phase has completed for that object type; platform
 * implementations may throw if it is.</p>
 *
 * @param <T> the registered object type
 */
public interface RegistryHandle<T> extends Supplier<T> {

    /** The full registered id, e.g. {@code deds_secretrooms:ghost_block}. */
    BId id();
}
