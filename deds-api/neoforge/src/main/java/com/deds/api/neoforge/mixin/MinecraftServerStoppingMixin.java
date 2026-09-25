package com.deds.api.neoforge.mixin;

import com.deds.api.event.ServerEvents;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link ServerEvents#STOPPING} on NeoForge, at the point Fabric API fires its
 * {@code SERVER_STOPPING}: the HEAD of {@code MinecraftServer.stopServer}.
 *
 * <p>Why not NeoForge's {@code ServerStoppingEvent}: it is posted from
 * {@code runServer} after the tick loop exits normally, so a crashed server
 * (and one whose start failed) never posts it, while {@code stopServer} runs
 * in the {@code finally} of every one of those paths. Morph clears its
 * per-server state from here; missing it on a crash would carry a stale
 * transition lock into the next singleplayer world (Morph resets on STARTED
 * too, but parity is the rule). {@code IntegratedServer} and
 * {@code DedicatedServer} override {@code stopServer} and call up to this
 * method, exactly as on Fabric.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStoppingMixin {

    @Inject(method = "stopServer()V", at = @At("HEAD"), require = 1, allow = 1)
    private void deds_api$serverStopping(CallbackInfo ci) {
        ServerEvents.STOPPING.invoke((MinecraftServer) (Object) this);
    }
}
