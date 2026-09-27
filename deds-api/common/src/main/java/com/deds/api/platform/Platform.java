package com.deds.api.platform;

import com.deds.api.DedsMod;
import com.deds.api.ModContext;

import java.nio.file.Path;

/**
 * The loader/version abstraction boundary. Exactly one implementation is
 * discovered via {@link java.util.ServiceLoader} at runtime:
 * {@code FabricPlatform} or {@code NeoForgePlatform}, whichever backend jar is
 * present, through its META-INF/services entry. Nothing outside
 * {@code com.deds.api} may implement or call this directly; mods go through
 * {@link com.deds.api.Deds}.
 */
public interface Platform {

    /** e.g. {@code "fabric"} or {@code "neoforge"}. */
    String loaderName();

    boolean isDevelopmentEnvironment();

    boolean isModLoaded(String modId);

    Path configDir();

    /**
     * Creates the per-mod context and arranges for the mod's initialization
     * to run: immediately (Fabric) or once the loader allows registration
     * (NeoForge); see {@link com.deds.api.Deds#init}.
     */
    ModContext initMod(String modId, DedsMod mod);

    /**
     * Runs a mod's client setup at the loader's "every mod initialized"
     * moment; see {@link com.deds.api.Deds#initClient} (Ded's API v2.9). The
     * default runs it immediately, which is right for a loader that already
     * calls client entrypoints after every main one.
     */
    default void initClient(String modId, Runnable init) {
        init.run();
    }
}
