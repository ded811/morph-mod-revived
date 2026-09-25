package com.deds.api.fabric;

import com.deds.api.DedsMod;
import com.deds.api.ModContext;
import com.deds.api.platform.Platform;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric implementation of the Deds platform boundary. Discovered via
 * {@code META-INF/services}; never referenced by name outside this package.
 */
public final class FabricPlatform implements Platform {

    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public ModContext initMod(String modId, DedsMod mod) {
        FabricModContext ctx = new FabricModContext(modId);
        mod.onInitialize(ctx);
        return ctx;
    }
}
