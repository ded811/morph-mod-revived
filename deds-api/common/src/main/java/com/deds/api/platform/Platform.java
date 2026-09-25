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

    /** Creates the per-mod context and runs the mod's initialization. */
    ModContext initMod(String modId, DedsMod mod);
}
