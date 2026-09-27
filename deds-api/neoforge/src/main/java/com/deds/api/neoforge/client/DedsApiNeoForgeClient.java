package com.deds.api.neoforge.client;

import com.deds.api.Deds;
import com.deds.api.client.BlockEntityRenderers;
import com.deds.api.client.BlockFaceSampler;
import com.deds.api.client.BlockModelWrappers;
import com.deds.api.client.ClientKeys;
import com.deds.api.client.FluidRenderers;
import com.deds.api.internal.client.CachingBlockFaceSampler;
import com.deds.api.internal.client.KeyDispatcher;
import com.deds.api.internal.client.ModelWrapping;
import com.deds.api.internal.client.RawKeyMapping;
import com.deds.api.internal.client.VanillaBlockEntityRenderers;
import com.deds.api.neoforge.ClientInitQueue;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NeoForge CLIENT entrypoint of Ded's API, the counterpart of
 * {@code DedsApiFabricClient}. Where each client seam plugs in:
 * <ul>
 * <li><b>Client init</b> ({@code Deds.initClient}): every mod's queued client
 *     setup runs at the end of NeoForge's registry window (see
 *     {@link ClientInitQueue}), right after the block-entity-renderer and
 *     fluid-rendering backends are installed, so their queued registrations
 *     resolve and later ones apply at once.</li>
 * <li><b>Keys</b>: queued until {@code RegisterKeyMappingsEvent} (the only
 *     moment a mapping is both saved and loaded back), then created at
 *     {@link EventPriority#LOWEST} under a real controls category per mod and
 *     handed to the shared {@link KeyDispatcher}; a later registration
 *     throws. Dispatch runs at {@code ClientTickEvent.Post}.</li>
 * <li><b>Block model wrappers</b>: {@code ModelEvent.ModifyBakingResult},
 *     after every bake: each baked block-state model is run through the
 *     registered wrappers in order.</li>
 * <li><b>Face sampler</b>: the shared sampler, its cache cleared at the start
 *     of every model reload ({@code ModelEvent.RegisterStandalone}).</li>
 * <li><b>Tints</b>: {@link NeoForgeBlockTints}, installed at the end of
 *     {@code RegisterClientExtensionsEvent}.</li>
 * <li><b>Fluid rendering</b>: {@link NeoForgeFluidRenderers}, fed to every
 *     reload's {@code RegisterFluidModelsEvent}.</li>
 * <li><b>Entity renderers and menu screens</b>: their facades call vanilla
 *     directly (see those classes); nothing to install.</li>
 * </ul>
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

        // Face sampler: installed now (it resolves nothing until render time),
        // cache dropped at the start of every model reload.
        CachingBlockFaceSampler sampler = new CachingBlockFaceSampler();
        BlockFaceSampler.install(sampler);
        modBus.addListener(ModelEvent.RegisterStandalone.class,
                event -> sampler.invalidate());
        modBus.addListener(ModelEvent.ModifyBakingResult.class,
                DedsApiNeoForgeClient::wrapModels);

        NeoForgeBlockTints tints = new NeoForgeBlockTints();
        modBus.addListener(EventPriority.LOWEST, RegisterClientExtensionsEvent.class,
                tints::install);

        NeoForgeFluidRenderers fluids = new NeoForgeFluidRenderers();
        modBus.addListener(RegisterFluidModelsEvent.class, fluids::onRegisterFluidModels);

        // The end of the registry window: install the backends that resolve
        // handles, then run every mod's client init.
        modBus.addListener(EventPriority.LOWEST, EntityAttributeCreationEvent.class, event -> {
            BlockEntityRenderers.install(new VanillaBlockEntityRenderers());
            FluidRenderers.install(fluids);
            ClientInitQueue.runAll(event);
        });

        ClientKeys.install(new ClientKeys.Backend() {
            @Override
            public void register(String modId, String name,
                    ClientKeys.InputType type, int defaultCode, Runnable onPress) {
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

        // NeoForge's controls screen can bind "modifier + key"; the raw
        // polls (held bindings, mouse-bound presses) honour it as NeoForge's
        // click path does.
        KeyDispatcher.useModifierCheck(mapping ->
                mapping.getKeyModifier().isActive(mapping.getKeyConflictContext()));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                event -> KeyDispatcher.tick(Minecraft.getInstance()));
    }

    /**
     * Runs every baked block-state model through the registered wrappers with
     * the shared {@link ModelWrapping} loop, the one the Fabric backend runs
     * from {@code modifyBlockModelAfterBake}: registration order, each wrapper
     * seeing the previous result, a wrapper that throws skipped for that
     * state while the others still apply.
     *
     * <p>One NeoForge difference: the handful of vanilla special blocks whose
     * item/entity models vanilla bakes before this event (chests, skulls,
     * banners, shulker boxes and the like) keep their unwrapped model there.
     * Wrappers only ever wrap their own mod's blocks.</p>
     */
    private static void wrapModels(ModelEvent.ModifyBakingResult event) {
        ModelWrapping.beginReload();
        if (BlockModelWrappers.registered().isEmpty()) {
            return;
        }
        Map<BlockState, BlockStateModel> models =
                event.getBakingResult().blockStateModels();
        for (Map.Entry<BlockState, BlockStateModel> entry : models.entrySet()) {
            entry.setValue(ModelWrapping.apply(entry.getKey(), entry.getValue()));
        }
    }

    private static void enqueue(PendingKey key) {
        synchronized (KEY_LOCK) {
            if (keysRegistered) {
                throw new IllegalStateException("key binding key." + key.modId()
                        + "." + key.name() + " registered after NeoForge's "
                        + "RegisterKeyMappingsEvent; register key bindings from "
                        + "Deds.initClient");
            }
            // Fabric refuses a second mapping with the same name at the call;
            // NeoForge would accept it and silently leave the first one dead
            // (the second constructor replaces it in vanilla's name table).
            for (PendingKey pending : PENDING) {
                if (pending.modId().equals(key.modId()) && pending.name().equals(key.name())) {
                    throw new IllegalArgumentException("Attempted to register two key "
                            + "mappings with equal ID: key." + key.modId() + "." + key.name());
                }
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
}
