package com.deds.morph.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player leaving the server who is riding something takes their mount with
 * them: vanilla {@code PlayerList.remove} marks the root vehicle and all its
 * passengers removed ("Removing player mount"). A rideable morph makes that
 * mount another PLAYER, who then vanished from the world - no chunks, no
 * ticking - until they relogged. So a player riding a player gets off first.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRemoveMixin {

    @Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"))
    private void deds_morph$dismountPlayerVehicle(ServerPlayer player, CallbackInfo ci) {
        if (player.getVehicle() instanceof Player) {
            player.stopRiding();
        }
    }
}
