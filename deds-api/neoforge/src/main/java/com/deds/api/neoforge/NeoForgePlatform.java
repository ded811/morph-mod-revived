package com.deds.api.neoforge;

import com.deds.api.DedsMod;
import com.deds.api.ModContext;
import com.deds.api.platform.Platform;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.RegisterEvent;

import net.minecraft.core.registries.Registries;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge implementation of the Deds platform boundary. Discovered via
 * {@code META-INF/services}, which in this jar names ONLY this class: FML
 * turns every services line into a module {@code provides}, and a line naming
 * a class that is not in the jar (the Fabric platform, say) is a hard startup
 * failure, not a skipped entry. Never referenced by name outside this package.
 *
 * <h2>When a mod's {@code onInitialize} runs</h2>
 *
 * <p>Not inside {@link #initMod}. FML constructs mods in PARALLEL, with every
 * built-in registry frozen: constructing a Block, an Item or a Fluid at that
 * point throws, and Ded's registrars register immediately, exactly as they do
 * on Fabric. So {@link #initMod} builds the context and adds one listener to
 * the mod's OWN event bus, at {@link EventPriority#LOWEST} of the first
 * registry event ({@code RegisterEvent} for {@code minecraft:attribute}), and
 * {@code onInitialize} runs there. At that point:</p>
 * <ul>
 * <li>every registry is writable (NeoForge unfreezes them all for the whole
 *     registry window), so every registrar can do what the Fabric one does,
 *     and a mod's own direct vanilla registrations (Thermal Expansion writes
 *     its data components itself) work unchanged;</li>
 * <li>it runs on FML's single "modloading-sync-worker" thread, one mod after
 *     another in mod order, never in parallel;</li>
 * <li>NeoForge's own attributes are already registered (they register at
 *     NORMAL priority of the same event), so a living entity's attribute
 *     supplier can be evaluated during {@code registerLiving}, as on
 *     Fabric.</li>
 * </ul>
 * <p>Not available yet: anything NeoForge or another mod registers through a
 * {@code DeferredRegister} for any registry other than attributes is still
 * unbound, NeoForge's own fluid types included, so
 * {@code Fluids.WATER.getFluidType()} there throws "Trying to access unbound
 * value".</p>
 *
 * <p>The consequence a mod can see: on NeoForge {@code Deds.init} returns
 * BEFORE {@code onInitialize} has run. Client code that needs the mod's
 * content goes through {@link #initClient} ({@code Deds.initClient}), never a
 * client constructor, and {@code Deds.init} itself must be called from the
 * mod's constructor: once the registry events have begun it throws. An
 * exception thrown by {@code onInitialize} is reported as a mod-loading error
 * naming the mod; the rest of that event's lowest phase is skipped (later
 * mods' {@code onInitialize} never run), NeoForge still posts the remaining
 * registry events, then reports every error together and reverts the
 * registries to vanilla.</p>
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
     * Builds the mod's context on the mod's OWN event bus and defers its
     * {@code onInitialize} into the registry window (see the class javadoc).
     * Called from the mod's {@code @Mod} constructor, so {@link ModList}
     * already holds the container.
     */
    /**
     * Set once the first registry event is over (by {@link DedsApiNeoForge}):
     * a context made after that would wait for an event that never comes.
     */
    static volatile boolean initEventPassed;

    @Override
    public ModContext initMod(String modId, DedsMod mod) {
        if (initEventPassed) {
            throw new IllegalStateException("Deds.init for mod '" + modId + "' called "
                    + "after NeoForge's registry events began, so its onInitialize "
                    + "could never run; call Deds.init from the mod's @Mod constructor");
        }
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
        AtomicBoolean ran = new AtomicBoolean();
        modBus.addListener(EventPriority.LOWEST, RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(Registries.ATTRIBUTE)
                    && ran.compareAndSet(false, true)) {
                mod.onInitialize(ctx);
            }
        });
        return ctx;
    }

    /**
     * Queues {@code init} to run once every mod's {@code onInitialize} has run
     * and before any client registration event (see {@link ClientInitQueue}).
     * On a dedicated server there is no client to initialize, and the call
     * does nothing.
     */
    @Override
    public void initClient(String modId, Runnable init) {
        ClientInitQueue.enqueue(modId, init);
    }

    /**
     * This API's own version (the deds_api mod's), for error messages. Public
     * for the client package; not API.
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
