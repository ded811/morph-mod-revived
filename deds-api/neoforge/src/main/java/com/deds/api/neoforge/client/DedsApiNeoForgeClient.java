package com.deds.api.neoforge.client;

import com.deds.api.Deds;
import com.deds.api.client.BlockFaceSampler;
import com.deds.api.client.BlockModelWrappers;
import com.deds.api.client.BlockTints;
import com.deds.api.client.ClientKeys;
import com.deds.api.internal.client.KeyDispatcher;
import com.deds.api.internal.client.RawKeyMapping;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.IntList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NeoForge CLIENT entrypoint of Ded's API, the counterpart of
 * {@code DedsApiFabricClient}: the {@link ClientKeys} backend, the client
 * payload handlers and the per-tick key dispatch.
 *
 * <p><b>Keys.</b> On Fabric a binding is registered the moment a mod asks.
 * On NeoForge the only moment a mapping is both saved AND loaded back is
 * {@code RegisterKeyMappingsEvent}, which {@code Options} posts right before
 * it reads options.txt (after every mod is constructed); one added later
 * would still be saved, but its saved binding would never be read again. So
 * {@link ClientKeys#register} queues here, and ONE listener at
 * {@link EventPriority#LOWEST} (after every other mod's normal-priority
 * listener, so a mod may register from its own listener too) creates each
 * mod's controls category with {@code registerCategory} (vanilla's
 * {@code KeyMapping.Category.register} is deprecated on NeoForge and appends
 * unsorted; NeoForge's sorts modded categories the way Fabric does), builds
 * the same {@link RawKeyMapping} with the plain vanilla constructor (so
 * NeoForge treats it as an ordinary vanilla mapping: universal conflict
 * context, no modifier, identical {@code isDown}/{@code consumeClick}), and
 * hands it to the shared {@link KeyDispatcher}. After that event a
 * registration throws, as Fabric's does once the options exist. The category
 * label key is {@code key.category.<modid>.main} on both loaders.</p>
 *
 * <p><b>Tick.</b> {@code ClientTickEvent.Post}, the end of every client tick,
 * where Fabric's {@code END_CLIENT_TICK} fires, except that NeoForge does not
 * post it before the first resource load has finished; held-key polling
 * during the loading overlay is the only thing that could notice.</p>
 *
 * <p><b>Not supported on NeoForge yet:</b> {@link BlockTints} and
 * {@link BlockFaceSampler} get backends whose every method throws
 * {@link UnsupportedOperationException} (NeoForge's tint registry takes static
 * source lists, so Ded's dynamic delegation needs a design of its own), and
 * {@link BlockModelWrappers} registrations are ignored, with one warning at
 * client setup if there are any. Mouse input needs nothing here: the shared
 * {@code MouseHandlerMixin} applies unchanged (deds_api.mixins.json).</p>
 *
 * <p>Constructed after {@link com.deds.api.neoforge.DedsApiNeoForge} (FML runs
 * a mod's all-dist entry class first) and before any consumer that declares
 * {@code ordering = "AFTER"} on deds_api, so such consumers find the key
 * backend installed. A consumer without that ordering may be constructed
 * before it, or at the same moment on another thread; the pending queues in
 * {@link ClientKeys} and {@link BlockTints} cover both, because the check,
 * the queueing and install's replay all happen under one lock per class, so
 * no registration can be lost or stranded.</p>
 */
@Mod(value = "deds_api", dist = Dist.CLIENT)
public final class DedsApiNeoForgeClient {

    /** Guards {@link #PENDING} and {@link #keysRegistered}. */
    private static final Object KEY_LOCK = new Object();

    /** Bindings asked for before {@code RegisterKeyMappingsEvent}, in order. */
    private static final List<PendingKey> PENDING = new ArrayList<>();

    private static boolean keysRegistered;

    /** One queued binding: a press binding, or a held one ({@code onChange}). */
    private record PendingKey(String modId, String name, ClientKeys.InputType type,
            int defaultCode, Runnable onPress, Consumer<Boolean> onChange) {
    }

    public DedsApiNeoForgeClient(IEventBus modBus) {
        Deds.LOGGER.info("Ded's API client initializing on NeoForge");

        installBlockModelGlue(modBus);

        ClientKeys.install(new ClientKeys.Backend() {
            @Override
            public void register(String modId, String name,
                    ClientKeys.InputType type, int defaultCode,
                    Runnable onPress) {
                enqueue(new PendingKey(modId, name, type, defaultCode, onPress, null));
            }

            @Override
            public void registerHeld(String modId, String name,
                    int defaultKey, Consumer<Boolean> onChange) {
                enqueue(new PendingKey(modId, name, ClientKeys.InputType.KEYBOARD,
                        defaultKey, null, onChange));
            }
        });
        modBus.addListener(EventPriority.LOWEST, RegisterKeyMappingsEvent.class,
                DedsApiNeoForgeClient::registerKeys);

        modBus.addListener(RegisterClientPayloadHandlersEvent.class,
                NeoForgeClientNet::registerHandlers);

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> KeyDispatcher.tick(Minecraft.getInstance()));
    }

    private static void enqueue(PendingKey key) {
        synchronized (KEY_LOCK) {
            if (keysRegistered) {
                throw new IllegalStateException("key binding key." + key.modId()
                        + "." + key.name() + " registered after NeoForge's "
                        + "RegisterKeyMappingsEvent; register key bindings during "
                        + "mod construction (a client entrypoint)");
            }
            PENDING.add(key);
        }
    }

    /**
     * Creates every queued binding, in the order they were asked for. The
     * mappings themselves (name, type, the UNBOUND routing) come from the
     * shared {@link KeyDispatcher#newMapping}, exactly as on Fabric.
     */
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        synchronized (KEY_LOCK) {
            keysRegistered = true;
            Map<String, KeyMapping.Category> categories = new HashMap<>();
            for (PendingKey key : PENDING) {
                KeyMapping.Category category = categories.computeIfAbsent(key.modId(),
                        modId -> {
                            KeyMapping.Category created = new KeyMapping.Category(
                                    Identifier.fromNamespaceAndPath(modId, "main"));
                            event.registerCategory(created);
                            return created;
                        });
                RawKeyMapping mapping = KeyDispatcher.newMapping(key.modId(),
                        key.name(), key.type(), key.defaultCode(), category);
                event.register(mapping);
                if (key.onChange() != null) {
                    KeyDispatcher.addHeld(mapping, key.onChange());
                } else {
                    KeyDispatcher.addPress(mapping, key.onPress());
                }
            }
            PENDING.clear();
        }
    }

    /**
     * The block-model/tint half of the client glue, which the Fabric backend
     * implements with {@code ModelLoadingPlugin} and {@code BlockColorRegistry}.
     * Neither has a NeoForge port yet (see the class javadoc), so the facades
     * get backends that say so the moment a mod uses them, instead of a
     * "used before the platform client bootstrap ran" error that would point
     * at the wrong cause.
     */
    private static void installBlockModelGlue(IEventBus modBus) {
        BlockFaceSampler.install(new BlockFaceSampler.Backend() {
            @Override
            public BlockStateModel modelOf(BlockState state) {
                throw unsupported("BlockFaceSampler");
            }

            @Override
            public BlockFaceSampler.Face[] facesOf(BlockState state, RandomSource random) {
                throw unsupported("BlockFaceSampler");
            }
        });
        BlockTints.install(new BlockTints.Backend() {
            @Override
            public void collect(BlockState state, BlockAndTintGetter level,
                    BlockPos pos, IntList output) {
                throw unsupported("BlockTints");
            }

            @Override
            public void registerDelegating(BlockTints.AppearanceSource source,
                    List<Block> blocks) {
                throw unsupported("BlockTints");
            }
        });
        // Wrappers are read at bake time, never pushed to a backend, so there
        // is nothing to refuse at registration: warn once, when every client
        // entrypoint has had its chance to register.
        modBus.addListener(FMLClientSetupEvent.class, event -> {
            int wrappers = BlockModelWrappers.registered().size();
            if (wrappers > 0) {
                Deds.LOGGER.warn("Ded's API {}: {} block model wrapper(s) registered, "
                        + "but block model wrapping is not supported on NeoForge yet; "
                        + "they are ignored", apiVersion(), wrappers);
            }
        });
    }

    private static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("Ded's API " + apiVersion()
                + " does not support " + what + " on NeoForge yet");
    }

    private static String apiVersion() {
        return com.deds.api.neoforge.NeoForgePlatform.apiVersion();
    }
}
