package com.deds.api.event;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Player↔entity interaction events (Ded's API v1), bridged from the loader by
 * the platform. Mirrors {@link CombatEvents}/{@link ServerEvents} so mod code
 * never imports {@code net.fabricmc.*}.
 */
public final class InteractionEvents {

    /**
     * Payload: a player right-clicked an entity. Fires on BOTH sides (server in
     * the interact-packet handler for the target entity, client when the local
     * player interacts) and BEFORE the spectator check, so listeners must guard
     * {@code player.isSpectator()} themselves. {@code hit} may be null.
     */
    public record UseEntity(Player player, Level level, InteractionHand hand,
            Entity target, EntityHitResult hit) {
    }

    /**
     * Fired when a player right-clicks an entity. A returning listener yields an
     * {@link InteractionResult}; the first non-{@code PASS} result cancels
     * vanilla processing (server) / triggers the swing + interact packet
     * (client). Register with {@link Event#registerReturning}; the platform
     * bridge fires it via {@link Event#invokeUntil} with a {@code PASS} sentinel.
     */
    public static final Event<UseEntity> USE_ENTITY = new Event<>();

    private InteractionEvents() {
    }
}
