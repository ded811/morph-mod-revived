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

    /** The active platform. Loaded lazily via ServiceLoader. */
    public static Platform platform() {
        Platform p = platform;
        if (p == null) {
            synchronized (Deds.class) {
                p = platform;
                if (p == null) {
                    p = ServiceLoader.load(Platform.class).findFirst()
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
