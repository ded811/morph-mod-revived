package com.deds.api;

import com.deds.api.platform.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Static facade of Ded's API.
 *
 * <p>A mod's loader entrypoint calls {@link #init(String, DedsMod)} once;
 * everything else the mod needs arrives through its {@link ModContext}.</p>
 */
public final class Deds {

    public static final Logger LOGGER = LoggerFactory.getLogger("deds_api");

    private static volatile Platform platform;

    private Deds() {
    }

    /**
     * The active platform. Loaded lazily via ServiceLoader, from the class
     * loader that loaded Ded's API itself rather than the calling thread's
     * context class loader: whichever thread happens to ask first then
     * cannot change the answer, and on NeoForge that loader is the one that
     * sees the backend jar's {@code META-INF/services} entry (on Fabric it is
     * the same Knot loader either way).
     */
    public static Platform platform() {
        Platform p = platform;
        if (p == null) {
            synchronized (Deds.class) {
                p = platform;
                if (p == null) {
                    p = ServiceLoader.load(Platform.class,
                                    Deds.class.getClassLoader()).findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "No Deds Platform implementation found "
                                    + "on the classpath"));
                    platform = p;
                    LOGGER.info("Ded's API platform: {}", p.loaderName());
                }
            }
        }
        return p;
    }

    /**
     * Initializes a mod. Call exactly once per mod, from its loader
     * entrypoint.
     */
    public static ModContext init(String modId, DedsMod mod) {
        return platform().initMod(modId, mod);
    }
}
