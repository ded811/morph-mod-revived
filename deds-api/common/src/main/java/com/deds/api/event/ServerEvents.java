package com.deds.api.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server lifecycle events, bridged from the underlying loader by the
 * platform implementation.
 */
public final class ServerEvents {

    /** Fired when the server has finished starting. */
    public static final Event<MinecraftServer> STARTED = new Event<>();

    /** Fired when the server begins stopping. */
    public static final Event<MinecraftServer> STOPPING = new Event<>();

    /** Fired at the end of every server tick. */
    public static final Event<MinecraftServer> TICK_END = new Event<>();

    /**
     * Fired once per connected {@link ServerPlayer} at the end of every server
     * tick, after {@link #TICK_END}. This is the per-player server post-tick the
     * whole-server {@link #TICK_END} could not provide — Morph ticks each
     * morphed player's active passive abilities from here (the modern stand-in
     * for the original's {@code TickHandlerServer.serverTick} per-player ability
     * loop). Bridged from the loader's end-of-server-tick hook (Fabric's
     * {@code ServerTickEvents.END_SERVER_TICK}, NeoForge's
     * {@code ServerTickEvent.Post}) by iterating the live player list.
     */
    public static final Event<ServerPlayer> PLAYER_TICK_END = new Event<>();

    private ServerEvents() {
    }
}
