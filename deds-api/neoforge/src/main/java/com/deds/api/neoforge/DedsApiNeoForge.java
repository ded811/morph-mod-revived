package com.deds.api.neoforge;

import com.deds.api.Deds;
import com.deds.api.event.ServerEvents;
import com.deds.api.neoforge.transfer.CapabilityRefresh;
import com.deds.api.neoforge.transfer.NeoEnergy;
import com.deds.api.neoforge.transfer.NeoFluids;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * NeoForge entrypoint for the API itself (both sides): bridges NeoForge into
 * the loader-agnostic events, the counterpart of {@code DedsApiFabric}, and
 * hands every Ded's mod's queued registrations to the NeoForge events that
 * accept them (one listener here serves every consumer).
 *
 * <p>Events: where NeoForge has an event at the point Fabric API fires with
 * the same values, it is used; where it does not, the Fabric API mixin is
 * mirrored at the identical injection point instead
 * (deds_api.neoforge.mixins.json):</p>
 * <ul>
 * <li>{@link ServerEvents#STARTED}: {@code ServerStartedEvent}, posted right
 *     after {@code initServer}, before anything a listener could observe.</li>
 * <li>{@link ServerEvents#TICK_END} / {@link ServerEvents#PLAYER_TICK_END}:
 *     {@code ServerTickEvent.Post}, the last statement of
 *     {@code tickServer}, where Fabric's {@code END_SERVER_TICK} fires.</li>
 * <li>{@link ServerEvents#STOPPING}: a mixin at the head of
 *     {@code MinecraftServer.stopServer}, as Fabric (NeoForge's
 *     {@code ServerStoppingEvent} misses a crash and a failed start).</li>
 * <li>{@code CombatEvents.PLAYER_KILLED_LIVING}: two mixins, as Fabric, not
 *     {@code LivingDeathEvent} (which fires before the death is final).</li>
 * <li>{@code InteractionEvents.USE_ENTITY}, {@code PlayerEvents.ATTACK_BLOCK}
 *     and {@code PlayerEvents.USE_BLOCK}: mixins at Fabric API's points on
 *     both sides, not NeoForge's interact events (which fire later and whose
 *     cancellation neither resyncs nor stops the client).</li>
 * </ul>
 *
 * <p>Constructed before the {@code dist = CLIENT} entry class of the same mod
 * (FML constructs all-dist entry classes first).</p>
 */
@Mod("deds_api")
public final class DedsApiNeoForge {

    public DedsApiNeoForge(IEventBus modBus) {
        Deds.LOGGER.info("Ded's API initializing on NeoForge");

        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                event -> ServerEvents.STARTED.invoke(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            MinecraftServer server = event.getServer();
            ServerEvents.TICK_END.invoke(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerEvents.PLAYER_TICK_END.invoke(player);
            }
            CapabilityRefresh.endOfTick();
        });

        // TARGET_ONLY player data: NeoForge's initial sync leaves it out (see
        // NeoForgeModContext), so the owner's copy is re-sent right after
        // each of the three initial self-syncs.
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class,
                event -> NeoForgeModContext.resyncOwnerOnlyData(event.getEntity()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class,
                event -> NeoForgeModContext.resyncOwnerOnlyData(event.getEntity()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class,
                event -> NeoForgeModContext.resyncOwnerOnlyData(event.getEntity()));

        // Every Ded's mod's network messages. "1": bump only if a wire format
        // changes. Optional, so a side without the mod is not refused over
        // them, as on Fabric.
        modBus.addListener(RegisterPayloadHandlersEvent.class,
                event -> NeoForgeMessageType.registerAll(event.registrar("1").optional()));

        // The registry window. HIGHEST of the first registry event runs before
        // every mod's onInitialize (LOWEST of that event, see
        // NeoForgePlatform): open the window and give ItemEnergy its
        // component, whatever order the mods load in.
        modBus.addListener(EventPriority.HIGHEST, RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(Registries.ATTRIBUTE)) {
                NeoForgeModContext.registryWindowOpen = true;
                NeoEnergy.registerComponent();
            } else {
                // The attribute event, and every mod's onInitialize in it, is
                // over: a Deds.init made from now on would never run.
                NeoForgePlatform.initEventPassed = true;
            }
        });
        // LOWEST, so NeoForge's own entries of these registries come first.
        modBus.addListener(EventPriority.LOWEST, RegisterEvent.class, event -> {
            NeoFluids.registerFluidTypes(event);
            if (event.getRegistryKey().equals(
                    NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS)) {
                event.register(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,
                        DedsOverworldOres.ID, () -> DedsOverworldOres.CODEC);
            }
        });
        modBus.addListener(EntityAttributeCreationEvent.class,
                NeoForgeModContext::putDefaultAttributes);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class,
                VanillaTabOps::onBuildContents);
        modBus.addListener(RegisterCapabilitiesEvent.class, event -> {
            NeoFluids.registerCapabilities(event);
            NeoEnergy.registerCapabilities(event);
        });
        modBus.addListener(EventPriority.LOW, RegisterCapabilitiesEvent.class,
                NeoFluids::registerBucketCapabilities);
        modBus.addListener(EventPriority.LOWEST, RegisterCapabilitiesEvent.class,
                NeoEnergy::registerItemCapabilities);
        // NeoForge freezes every registry right after the registry events and
        // the few events of their success branch, the last of which is
        // BlockEntityTypeAddBlocksEvent: from its LOWEST on, a registration
        // gets the "only from onInitialize" message instead of vanilla's
        // "Registry is already frozen". Common setup closes it too, in case
        // that event never ran.
        modBus.addListener(EventPriority.LOWEST, BlockEntityTypeAddBlocksEvent.class,
                event -> NeoForgeModContext.registryWindowOpen = false);
        modBus.addListener(FMLCommonSetupEvent.class,
                event -> NeoForgeModContext.registryWindowOpen = false);
    }
}
