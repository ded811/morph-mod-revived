package com.deds.api.neoforge;

import com.deds.api.Deds;
import com.deds.api.event.ServerEvents;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * NeoForge entrypoint for the API itself (both sides): bridges NeoForge into
 * the loader-agnostic events, the counterpart of {@code DedsApiFabric}.
 *
 * <p>Where NeoForge has an event at the point Fabric API fires with the same
 * values, it is used; where it does not, the Fabric API mixin is mirrored at
 * the identical injection point instead (deds_api.neoforge.mixins.json):
 * <ul>
 * <li>{@link ServerEvents#STARTED}: {@code ServerStartedEvent}, posted right
 *     after {@code initServer}; Fabric's hook sits a few statements later in
 *     the same method, before anything a listener could observe.</li>
 * <li>{@link ServerEvents#TICK_END} / {@link ServerEvents#PLAYER_TICK_END}:
 *     {@code ServerTickEvent.Post}, the last statement of
 *     {@code tickServer}, exactly where Fabric's {@code END_SERVER_TICK}
 *     fires. NOT {@code PlayerTickEvent}: that fires inside each player's own
 *     entity tick (and on both sides), which is not "after the whole server
 *     tick".</li>
 * <li>{@link ServerEvents#STOPPING}: a mixin at the head of
 *     {@code MinecraftServer.stopServer}, as Fabric. NeoForge's
 *     {@code ServerStoppingEvent} fires only on a normal exit of the server
 *     loop; Fabric's hook also runs on a crash and on a failed start. Never
 *     both, or it fires twice.</li>
 * <li>{@code CombatEvents.PLAYER_KILLED_LIVING}: two mixins, as Fabric
 *     ({@code LivingEntity.die} around {@code killedEntity};
 *     {@code ServerPlayer.die} at {@code getKillCredit}). NOT
 *     {@code LivingDeathEvent}: it fires at the START of {@code die}, before
 *     {@code dead} is set and the kill is scored, and any later listener may
 *     still cancel the death, so a mod acting on it (Morph discards the
 *     victim) would act on a death that may never happen.</li>
 * <li>{@code InteractionEvents.USE_ENTITY}: two mixins, as Fabric
 *     ({@code handleInteract} on the server, {@code startUseItem} on the
 *     client). NOT {@code PlayerInteractEvent.EntityInteract}: it fires after
 *     the spectator and item-enabled checks, after the client already sent
 *     its interact packet, and its cancellation still lets the server swing
 *     and award the interaction advancement.</li>
 * <li>{@code PlayerEvents.ATTACK_BLOCK} / {@code USE_BLOCK}: NOT bridged on
 *     NeoForge yet (see their javadoc).</li>
 * </ul>
 *
 * <p>Constructed before the {@code dist = CLIENT} entry class of the same
 * mod (FML sorts all-dist entry classes first), and before any Ded's mod that
 * declares {@code ordering = "AFTER"} on deds_api, which every consumer should:
 * FML constructs unordered mods in parallel.</p>
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
            // Per-player post-tick: the same loop as DedsApiFabric, over the
            // live player list once the whole-server tick has ended (Morph's
            // passive-ability tick fires from here).
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerEvents.PLAYER_TICK_END.invoke(player);
            }
        });

        // TARGET_ONLY player data: NeoForge's initial sync leaves it out (see
        // NeoForgeModContext#register), so the owner's copy is re-sent right
        // after each of the three initial self-syncs.
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class,
                event -> NeoForgeModContext.resyncOwnerOnlyData(event.getEntity()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class,
                event -> NeoForgeModContext.resyncOwnerOnlyData(event.getEntity()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class,
                event -> NeoForgeModContext.resyncOwnerOnlyData(event.getEntity()));

        // Every Ded's mod's network messages, queued during construction.
        // "1": bump only if a wire format changes (NeoForge-to-NeoForge
        // connections refuse a version mismatch). Optional, so a server or
        // client without the mod is not refused over them, as on Fabric (see
        // NeoForgeMessageType's class javadoc for the one case that still is).
        modBus.addListener(RegisterPayloadHandlersEvent.class,
                event -> NeoForgeMessageType.registerAll(event.registrar("1").optional()));
    }
}
