package com.deds.morph;

import com.deds.api.event.InteractionEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * The interaction router (spec §2): dispatches a right-click on a morphed player
 * to either the MOUNT branch (empty hand, not sneaking, rideable morph) or the
 * PRODUCTION branch (whitelisted container/tool → the morph's {@code mobInteract}).
 * Registered on the shared {@link InteractionEvents#USE_ENTITY}; shared code
 * (no {@code net.fabricmc}), server-authoritative with a client prediction so the
 * vanilla client swings and sends the interact packet.
 */
public final class MorphInteractions {

    private MorphInteractions() {
    }

    /** Registers the router. Called from {@code Morph.onInitialize}. */
    public static void register() {
        InteractionEvents.USE_ENTITY.registerReturning(MorphInteractions::onUseEntity);
    }

    /** The USE_ENTITY handler (both sides). */
    public static InteractionResult onUseEntity(InteractionEvents.UseEntity ctx) {
        Player player = ctx.player();
        Level level = ctx.level();
        InteractionHand hand = ctx.hand();

        if (!(ctx.target() instanceof Player victim)) {
            return InteractionResult.PASS;
        }
        if (player == victim || player.isSpectator()) {
            return InteractionResult.PASS; // no self-interact; guard spectator here
        }
        if (!Morph.config().mobInteractions() && !Morph.config().rideableMorphs()) {
            return InteractionResult.PASS;
        }
        Optional<MorphVariant> variant = MorphAbilities.committedVariant(victim);
        if (variant.isEmpty()) {
            return InteractionResult.PASS; // target is not morphed
        }
        ItemStack held = player.getItemInHand(hand);

        // CLIENT: predict so the client swings + sends the interact packet (a
        // vanilla player target otherwise produces no interaction).
        if (level.isClientSide()) {
            return wouldHandle(variant.get(), held, player, level)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        // SERVER: authoritative.
        if (!(victim instanceof ServerPlayer serverVictim)) {
            return InteractionResult.PASS;
        }
        // 1) MOUNT: empty hand, not sneaking, rideable morph.
        if (Morph.config().rideableMorphs() && held.isEmpty()
                && !player.isSecondaryUseActive()
                && MorphRideable.isMountable(variant.get(), level)) {
            return MorphRideable.mount(player, serverVictim);
        }
        // 2) PRODUCTION: whitelisted container/tool → forward mobInteract.
        if (Morph.config().mobInteractions()
                && MorphInteractionEffects.isProductionItem(held)) {
            return MorphInteractionEffects.run(serverVictim, variant.get(),
                    player, hand);
        }
        return InteractionResult.PASS;
    }

    /** Client-side predicate mirroring the two server branches. */
    private static boolean wouldHandle(MorphVariant variant, ItemStack held,
            Player player, Level level) {
        if (Morph.config().rideableMorphs() && held.isEmpty()
                && !player.isSecondaryUseActive()
                && MorphRideable.isMountable(variant, level)) {
            return true;
        }
        return Morph.config().mobInteractions()
                && MorphInteractionEffects.isProductionItem(held);
    }
}
