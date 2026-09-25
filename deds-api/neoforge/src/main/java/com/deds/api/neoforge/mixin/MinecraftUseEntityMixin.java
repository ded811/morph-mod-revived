package com.deds.api.neoforge.mixin;

import com.deds.api.event.InteractionEvents;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link InteractionEvents#USE_ENTITY}, client side, the mirror of Fabric
 * API's client {@code MinecraftMixin.injectUseEntityCallback}
 * (fabric-events-interaction-v0 5.2.6, the Minecraft 26.2 build): in
 * {@code Minecraft.startUseItem}, at the call of
 * {@code MultiPlayerGameMode.interact(player, entity, entityHit, hand)}, i.e.
 * only once the target passed the interaction-range check.
 *
 * <p>The body is Fabric's: on a result other than PASS, send the interact
 * packet ourselves if the result consumes the action (with the hit relative
 * to the entity again, and WITHOUT {@code ensureHasSentCarriedItem}, as
 * Fabric), swing if it is a {@code Success} whose swing source is CLIENT, and
 * return from {@code startUseItem} (no other hand, no item use). A FAIL sends
 * nothing. NeoForge's {@code EntityInteract} cannot stand in: on the client it
 * fires after vanilla already sent the packet, so a FAIL could not suppress
 * it.</p>
 *
 * <p><b>Locals by TYPE.</b> Fabric names them ({@code hand},
 * {@code entityHit}, {@code entity}); javap {@code -c -l} of both NeoForge
 * builds shows exactly one live local of each of those three types at this
 * call (26.2.0.75: slots 4, 7 and 8 at offset 238), so the implicit by-type
 * {@code @Local} needs no local variable table.</p>
 *
 * <p>This is the canonical (26.2) version. Minecraft 26.3 changed the swing
 * API, so {@code versions/mc26.3/deds-api/neoforge/...} carries a twin that
 * mirrors fabric-events-interaction-v0 5.3.6 instead.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseEntityMixin {

    @Shadow
    public LocalPlayer player;

    @Shadow
    public abstract ClientPacketListener getConnection();

    @Inject(method = "startUseItem()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interact("
                            + "Lnet/minecraft/world/entity/player/Player;"
                            + "Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/world/phys/EntityHitResult;"
                            + "Lnet/minecraft/world/InteractionHand;)"
                            + "Lnet/minecraft/world/InteractionResult;"),
            cancellable = true, require = 1, allow = 1)
    private void deds_api$useEntity(CallbackInfo ci, @Local InteractionHand hand,
            @Local EntityHitResult hitResult, @Local Entity entity) {
        InteractionResult result = InteractionEvents.USE_ENTITY.invokeUntil(
                new InteractionEvents.UseEntity(player, player.level(), hand,
                        entity, hitResult),
                InteractionResult.PASS);

        if (result != InteractionResult.PASS) {
            if (result.consumesAction()) {
                Vec3 hitVec = hitResult.getLocation().subtract(entity.getX(),
                        entity.getY(), entity.getZ());
                getConnection().send(new ServerboundInteractPacket(entity.getId(),
                        hand, hitVec, player.isShiftKeyDown()));
            }

            if (result instanceof InteractionResult.Success success
                    && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                player.swing(hand);
            }

            ci.cancel();
        }
    }
}
