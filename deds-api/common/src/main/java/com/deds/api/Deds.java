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
     *
     * <p>When {@link DedsMod#onInitialize} runs depends on the loader. On
     * Fabric it runs inside this call. On NeoForge it runs later, during
     * NeoForge's registry events (the only time NeoForge lets anything be
     * registered), after every mod has been constructed: this call returns
     * first. Do not rely on the mod being initialized when it returns, and
     * start the client half with {@link #initClient}, not in a client
     * constructor.</p>
     */
    public static ModContext init(String modId, DedsMod mod) {
        return platform().initMod(modId, mod);
    }

    /**
     * Runs a mod's client setup: key bindings, renderers, menu screens,
     * tints, model wrappers, client message listeners (Ded's API v2.9). Call
     * it from the mod's CLIENT entrypoint (Fabric's {@code client}
     * entrypoint, NeoForge's {@code @Mod(dist = Dist.CLIENT)} constructor).
     *
     * <p>It keeps the rule Fabric gives for free, "every mod's
     * {@link DedsMod#onInitialize} has run before any client setup", on every
     * loader:</p>
     * <ul>
     * <li>Fabric: {@code init} runs right away (the client entrypoint is
     *     already that moment).</li>
     * <li>NeoForge: {@code init} is queued and runs once every mod's
     *     {@code onInitialize} has run, before the game window, the key
     *     options and the first resource load exist. It runs on NeoForge's
     *     mod-loading thread, and {@code Minecraft.getInstance()} is still
     *     {@code null} then, so touch the client only from inside the
     *     callbacks you register. A call made from inside another mod's
     *     client setup runs at once, as on Fabric; a call after every setup
     *     has run throws.</li>
     * <li>A dedicated server (either loader): {@code init} never runs.</li>
     * </ul>
     *
     * @param modId the mod's id, as passed to {@link #init}
     * @param init  the client setup
     */
    public static void initClient(String modId, Runnable init) {
        platform().initClient(modId, init);
    }
}
