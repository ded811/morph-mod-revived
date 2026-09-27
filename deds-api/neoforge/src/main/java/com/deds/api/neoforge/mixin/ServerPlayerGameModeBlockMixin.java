package com.deds.api.neoforge.mixin;

import com.deds.api.event.PlayerEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The server half of {@link PlayerEvents#ATTACK_BLOCK} and
 * {@link PlayerEvents#USE_BLOCK}, mirroring Fabric API's
 * {@code ServerPlayerGameModeMixin} at the identical points, so the events
 * fire with the same arguments at the same moment on both loaders:
 * <ul>
 * <li>attack: HEAD of {@code handleBlockBreakAction}, START_DESTROY_BLOCK
 *     only, before vanilla's reach, height, spawn-protection and game-mode
 *     checks. A non-PASS result resends the block (and its block entity) so
 *     a client that already broke it locally puts it back, then cancels;</li>
 * <li>use: HEAD of {@code useItemOn}, before the spectator check and the
 *     "sneaking with an item skips the block" gate. A non-PASS result is the
 *     method's result.</li>
 * </ul>
 *
 * <p>NeoForge's own {@code PlayerInteractEvent.LeftClickBlock} and
 * {@code RightClickBlock} are NOT used: they fire for every break action,
 * later, and cancelling them neither resyncs the block nor stops what the
 * client already did. Because these hooks run first, a Ded's cancel also
 * pre-empts NeoForge's events for that click, as Fabric's does.</p>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeBlockMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    @Inject(method = "handleBlockBreakAction(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"
            + "Lnet/minecraft/core/Direction;II)V",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void deds_api$attackBlock(BlockPos pos,
            ServerboundPlayerActionPacket.Action action, Direction face,
            int maxBuildHeight, int sequence, CallbackInfo ci) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            return;
        }
        InteractionResult result = PlayerEvents.ATTACK_BLOCK.invokeUntil(
                new PlayerEvents.AttackBlock(player, level,
                        InteractionHand.MAIN_HAND, pos, face),
                InteractionResult.PASS);
        if (result != InteractionResult.PASS) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
            if (level.getBlockState(pos).hasBlockEntity()) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    Packet<ClientGamePacketListener> update =
                            blockEntity.getUpdatePacket();
                    if (update != null) {
                        player.connection.send(update);
                    }
                }
            }
            ci.cancel();
        }
    }

    @Inject(method = "useItemOn(Lnet/minecraft/server/level/ServerPlayer;"
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)"
            + "Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void deds_api$useBlock(ServerPlayer usingPlayer, Level usingLevel,
            ItemStack stack, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = PlayerEvents.USE_BLOCK.invokeUntil(
                new PlayerEvents.UseBlock(usingPlayer, usingLevel, hand, hit),
                InteractionResult.PASS);
        if (result != InteractionResult.PASS) {
            cir.setReturnValue(result);
        }
    }
}
