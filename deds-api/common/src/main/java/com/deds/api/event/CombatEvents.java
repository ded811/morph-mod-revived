package com.deds.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Combat events (Ded's API v1), bridged from the loader by the platform.
 */
public final class CombatEvents {

    /** Payload: a player killed a living entity (server side). */
    public record Kill(ServerPlayer killer, LivingEntity victim) {
    }

    /**
     * Fired on the server thread after a player kills any living entity —
     * Morph acquisition's trigger.
     */
    public static final Event<Kill> PLAYER_KILLED_LIVING = new Event<>();

    private CombatEvents() {
    }
}
