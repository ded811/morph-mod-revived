package com.deds.api.fabric;

import com.deds.api.Deds;
import com.deds.api.event.CombatEvents;
import com.deds.api.event.InteractionEvents;
import com.deds.api.event.PlayerEvents;
import com.deds.api.event.ServerEvents;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * Fabric entrypoint for the API itself: bridges Fabric events into the
 * loader-agnostic {@link ServerEvents}.
 */
public final class DedsApiFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Deds.LOGGER.info("Ded's API initializing on Fabric");

        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> ServerEvents.STARTED.invoke(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> ServerEvents.STOPPING.invoke(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerEvents.TICK_END.invoke(server);
            // Per-player post-tick: Fabric has no native "per ServerPlayer end
            // tick", so iterate the live player list once the whole-server tick
            // has ended (Morph's passive-ability tick fires from here).
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerEvents.PLAYER_TICK_END.invoke(player);
            }
        });

        // Ded's API v1: player-kill bridge (Morph acquisition's trigger)
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(
                (level, killer, victim, damageSource) -> {
                    if (killer instanceof ServerPlayer player) {
                        CombatEvents.PLAYER_KILLED_LIVING.invoke(
                                new CombatEvents.Kill(player, victim));
                    }
                });

        // Ded's API v1: use-entity bridge (Morph interactions/mounting trigger).
        // Fires both sides; returning-listeners yield an InteractionResult and
        // the first non-PASS short-circuits vanilla processing.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                InteractionEvents.USE_ENTITY.invokeUntil(
                        new InteractionEvents.UseEntity(player, level, hand,
                                entity, hit),
                        InteractionResult.PASS));

        // Ded's API v1.5: attack-block bridge (Carpenter's Blocks' hammer and
        // chisel left-click half). Fabric's AttackBlockCallback is the one
        // hook that carries BOTH the clicked face and a cancel — vanilla's
        // Block.attack has neither. Fires on both sides; a non-PASS result
        // cancels the attack on that side.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, face) ->
                PlayerEvents.ATTACK_BLOCK.invokeUntil(
                        new PlayerEvents.AttackBlock(player, level, hand, pos,
                                face),
                        InteractionResult.PASS));

        // Ded's API v1.5: use-block bridge. Fires at the HEAD of the server's
        // useItemOn (javap-verified: the Fabric mixin's first instruction is
        // the event invoke), i.e. BEFORE vanilla's "sneaking with an item
        // skips the block entirely" gate — which is the only way a block can
        // see a sneak + item right-click at all.
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                PlayerEvents.USE_BLOCK.invokeUntil(
                        new PlayerEvents.UseBlock(player, level, hand, hit),
                        InteractionResult.PASS));
    }
}
