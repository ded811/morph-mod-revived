package com.deds.api.neoforge;

import com.deds.api.DedsMod;
import com.deds.api.ModContext;
import com.deds.api.platform.Platform;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of the Deds platform boundary. Discovered via
 * {@code META-INF/services}, which in this jar names ONLY this class: FML
 * turns every services line into a module {@code provides}, and a line naming
 * a class that is not in the jar (the Fabric platform, say) is a hard startup
 * failure, not a skipped entry. Never referenced by name outside this package.
 *
 * <p>Thread note: FML constructs mods IN PARALLEL (only BEFORE/AFTER ordering
 * edges serialize two mods), so {@link #initMod} can run for two Ded's mods at
 * the same time on different {@code modloading-worker} threads. Everything it
 * touches is per mod or concurrent.</p>
 */
public final class NeoForgePlatform implements Platform {

    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    /**
     * {@link ModList} exists from just before mod construction on. An earlier
     * caller (a mixin plugin, a static initializer run during discovery) gets
     * the loading mod list instead, which already knows every mod file FML
     * will load; before even that exists, nothing is loaded yet.
     */
    @Override
    public boolean isModLoaded(String modId) {
        ModList mods = ModList.get();
        if (mods != null) {
            return mods.isLoaded(modId);
        }
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        if (loader == null) {
            return false;
        }
        try {
            return loader.getLoadingModList().getModFileById(modId) != null;
        } catch (IllegalStateException notBuiltYet) {
            return false;
        }
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /**
     * Builds the mod's context on the mod's OWN event bus (the registrars that
     * must defer to a NeoForge registration event register their listeners
     * there) and runs the mod's initialization, synchronously, exactly like
     * the Fabric platform. Called from the mod's {@code @Mod} constructor, so
     * {@link ModList} already holds the container.
     */
    @Override
    public ModContext initMod(String modId, DedsMod mod) {
        ModContainer container = ModList.get().getModContainerById(modId)
                .orElseThrow(() -> new IllegalStateException("Ded's API: no "
                        + "NeoForge mod container for '" + modId + "'; call "
                        + "Deds.init from that mod's own @Mod constructor"));
        IEventBus modBus = container.getEventBus();
        if (modBus == null) {
            throw new IllegalStateException("Ded's API: mod '" + modId
                    + "' has no mod event bus (not a javafml mod?)");
        }
        NeoForgeModContext ctx = new NeoForgeModContext(modId, modBus);
        mod.onInitialize(ctx);
        return ctx;
    }

    /**
     * This API's own version (the deds_api mod's), for error messages about
     * what it does not support yet. "unknown" only if asked before
     * {@link ModList} exists. Public for the client package; not API.
     */
    public static String apiVersion() {
        ModList mods = ModList.get();
        if (mods == null) {
            return "(unknown version)";
        }
        return mods.getModContainerById("deds_api")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("(unknown version)");
    }
}
