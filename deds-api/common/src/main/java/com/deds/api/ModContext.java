package com.deds.api;

import com.deds.api.attach.PlayerDataRegistrar;
import com.deds.api.command.CommandRegistrar;
import com.deds.api.config.ConfigRegistrar;
import com.deds.api.fluid.FluidRegistrar;
import com.deds.api.net.NetRegistrar;
import com.deds.api.registry.BlockEntityRegistrar;
import com.deds.api.registry.BlockRegistrar;
import com.deds.api.registry.EffectRegistrar;
import com.deds.api.registry.ItemRegistrar;
import com.deds.api.registry.TabRegistrar;

import org.slf4j.Logger;

/**
 * Everything a {@link DedsMod} receives at initialization. One instance per mod,
 * created by the platform when the mod's (one-line) loader entrypoint calls
 * {@link Deds#init}.
 */
public interface ModContext {

    /** The mod id, e.g. {@code deds_secretrooms}. Also the registry namespace. */
    String modId();

    Logger logger();

    BlockRegistrar blocks();

    ItemRegistrar items();

    /** Mob-effect registration (Ded's API v1.2) — mirror of {@link #items()}. */
    EffectRegistrar effects();

    TabRegistrar tabs();

    BlockEntityRegistrar blockEntities();

    /** Per-player persistent data slots (Ded's API v1). */
    PlayerDataRegistrar playerData();

    /** Network messages (Ded's API v1). */
    NetRegistrar net();

    /** Brigadier command registration (Ded's API v1.1). */
    CommandRegistrar commands();

    /** Codec-backed JSON config (Ded's API v1.1). */
    ConfigRegistrar config();

    /**
     * Fluid registration and the tank/transport seam (Ded's API v2.1).
     * Deliberately four methods — see {@link FluidRegistrar}.
     */
    FluidRegistrar fluids();
}
