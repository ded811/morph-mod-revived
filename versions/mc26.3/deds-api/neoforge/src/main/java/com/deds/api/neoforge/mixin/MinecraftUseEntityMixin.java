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
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link InteractionEvents#USE_ENTITY}, client side: the Minecraft 26.3 twin
 * of the canonical {@code MinecraftUseEntityMixin}, mirroring Fabric API's
 * client {@code MinecraftMixin.injectUseEntityCallback} from
 * fabric-events-interaction-v0 5.3.6 (the 26.3 build). Same point, same body,
 * except for 26.3's swing API, the one thing Fabric's 26.3 version changes:
 * {@code startUseItem} has a {@code SwingAnimation} local, a client-side
 * swing is now {@code SwingSource.PREDICTED} (26.3 dropped the serverbound
 * swing packet; other players see swings the server sends), and the swing
 * call takes the animation.
 *
 * <p>Locals by TYPE, as in the canonical version: javap {@code -c -l} of the
 * NeoForge 26.3.0.7-beta build shows exactly one live local each of
 * {@code InteractionHand} (slot 4), {@code SwingAnimation} (slot 6),
 * {@code EntityHitResult} (slot 8) and {@code Entity} (slot 9) at this call
 * (offset 250).</p>
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
            @Local SwingAnimation swingAnimation, @Local EntityHitResult hitResult,
            @Local Entity entity) {
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
                    && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                player.swing(hand, swingAnimation, false);
            }

            ci.cancel();
        }
    }
}
