package com.deds.api.neoforge.mixin;

import com.deds.api.event.InteractionEvents;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link InteractionEvents#USE_ENTITY}, server side, the mirror of Fabric
 * API's {@code ServerGamePacketListenerImplMixin.handleInteract}
 * (fabric-events-interaction-v0, identical in 5.2.6 for 26.2 and 5.3.6 for
 * 26.3): in {@code handleInteract}, at the call of
 * {@code player.getItemInHand(hand)}.
 *
 * <p>By then vanilla has checked that the client finished loading, that the
 * target exists, is inside the world border and within interaction range;
 * it has NOT yet checked that the held item is enabled or that the player is
 * not a spectator (that is in {@code interactOn}), which is why the Ded's
 * event contract makes listeners guard spectators themselves. A result other
 * than PASS returns from {@code handleInteract} at once: no
 * {@code interactOn}, no interaction advancement, no server swing, exactly
 * as a Fabric cancel.</p>
 *
 * <p>Why not NeoForge's {@code PlayerInteractEvent.EntityInteract}: it fires
 * inside {@code interactOn}, after the spectator and item checks, and a
 * cancelled one still hands its result back to {@code handleInteract}, which
 * then awards the advancement and, for Morph's mount result, swings the
 * rider's arm and (26.3) resets its attack strength. None of that happens on
 * Fabric.</p>
 *
 * <p><b>The target by TYPE, not by name.</b> Fabric reads it with
 * {@code @Local(name = "target")}, which needs the local variable table, and
 * a production NeoForge jar is not guaranteed to have one. javap of both
 * NeoForge builds ({@code -c -l}) shows exactly one {@link Entity}-typed
 * local live at this call (slot 3, {@code target}, offset 111 on both), so
 * the implicit by-type {@code @Local} binds the same variable without it.
 * The hit keeps Fabric's shape: ABSOLUTE, the packet's entity-relative
 * location plus the target's position.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerUseEntityMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getItemInHand("
                            + "Lnet/minecraft/world/InteractionHand;)"
                            + "Lnet/minecraft/world/item/ItemStack;"),
            cancellable = true, require = 1, allow = 1)
    private void deds_api$useEntity(ServerboundInteractPacket packet,
            CallbackInfo ci, @Local Entity target) {
        EntityHitResult hit = new EntityHitResult(target, packet.location()
                .add(target.getX(), target.getY(), target.getZ()));
        InteractionResult result = InteractionEvents.USE_ENTITY.invokeUntil(
                new InteractionEvents.UseEntity(player, player.level(),
                        packet.hand(), target, hit),
                InteractionResult.PASS);
        if (result != InteractionResult.PASS) {
            ci.cancel();
        }
    }
}
