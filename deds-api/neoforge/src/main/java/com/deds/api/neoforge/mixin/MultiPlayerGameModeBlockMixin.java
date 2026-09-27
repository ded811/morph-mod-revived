package com.deds.api.neoforge.mixin;

import com.deds.api.event.PlayerEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client half of {@link PlayerEvents#ATTACK_BLOCK} and
 * {@link PlayerEvents#USE_BLOCK}, mirroring Fabric API's client
 * {@code MultiPlayerGameModeMixin} at the identical points and with its
 * packet rules:
 * <ul>
 * <li>attack: in {@code startDestroyBlock} (and, in creative only,
 *     {@code continueDestroyBlock}) at the first
 *     {@code LocalPlayer.getAbilities()} call, after vanilla's
 *     restricted-block and world-border early returns. A non-PASS result
 *     makes the method return {@code result == SUCCESS} (particles and the
 *     swing only for SUCCESS) and, if the result consumes the action, still
 *     sends the server its START_DESTROY_BLOCK so the server-side event
 *     fires too;</li>
 * <li>use: in {@code useItemOn} at its {@code startPrediction} call, after
 *     the world-border check, skipping spectators. A non-PASS result is the
 *     method's result, with the server told only if it consumes the
 *     action.</li>
 * </ul>
 * One NeoForge-only caveat: {@code InputEvent.InteractionKeyMappingTriggered}
 * runs before these methods are even called, so a mod cancelling that input
 * event also prevents the client-side Ded's event. The server side still
 * fires whenever the server receives the action.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeBlockMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    protected abstract void startPrediction(ClientLevel level, PredictiveAction action);

    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/Direction;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/"
                    + "LocalPlayer;getAbilities()Lnet/minecraft/world/entity/player/"
                    + "Abilities;", ordinal = 0),
            cancellable = true, require = 1, allow = 1)
    private void deds_api$startDestroy(BlockPos pos, Direction face,
            CallbackInfoReturnable<Boolean> cir) {
        deds_api$attack(pos, face, cir);
    }

    @Inject(method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/Direction;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/"
                    + "LocalPlayer;getAbilities()Lnet/minecraft/world/entity/player/"
                    + "Abilities;", ordinal = 0),
            cancellable = true, require = 1, allow = 1)
    private void deds_api$continueDestroy(BlockPos pos, Direction face,
            CallbackInfoReturnable<Boolean> cir) {
        if (minecraft.player.getAbilities().instabuild) {
            deds_api$attack(pos, face, cir);
        }
    }

    @Unique
    private void deds_api$attack(BlockPos pos, Direction face,
            CallbackInfoReturnable<Boolean> cir) {
        InteractionResult result = PlayerEvents.ATTACK_BLOCK.invokeUntil(
                new PlayerEvents.AttackBlock(minecraft.player, minecraft.level,
                        InteractionHand.MAIN_HAND, pos, face),
                InteractionResult.PASS);
        if (result != InteractionResult.PASS) {
            cir.setReturnValue(result == InteractionResult.SUCCESS);
            if (result.consumesAction()) {
                startPrediction(minecraft.level, sequence ->
                        new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                pos, face, sequence));
            }
        }
    }

    @Inject(method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;"
            + "Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)"
            + "Lnet/minecraft/world/InteractionResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/"
                    + "MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/"
                    + "multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/"
                    + "prediction/PredictiveAction;)V"),
            cancellable = true, require = 1, allow = 1)
    private void deds_api$useBlock(LocalPlayer player, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (player.isSpectator()) {
            return;
        }
        InteractionResult result = PlayerEvents.USE_BLOCK.invokeUntil(
                new PlayerEvents.UseBlock(player, player.level(), hand, hit),
                InteractionResult.PASS);
        if (result != InteractionResult.PASS) {
            if (result.consumesAction()) {
                startPrediction((ClientLevel) player.level(),
                        sequence -> new ServerboundUseItemOnPacket(hand, hit, sequence));
            }
            cir.setReturnValue(result);
        }
    }
}
