package com.deds.morph.client.mixin;

import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the HUD air-bubble row entirely for a water-breathing (SWIM) morph
 * (wave 6 item C). Vanilla's private {@code Hud.extractAirBubbles} gates on
 * {@code isEyeInFluid(WATER) || air < maxAir} — the eye-in-water half is true no
 * matter what the air value is, so a fish/squid morph with pinned-full air still
 * showed a full bubble row.
 *
 * <p>A HEAD-cancel, deliberately NOT a redirect of {@code isEyeInFluid}: the OR
 * with {@code air < maxAir} would still show a partial row after morphing
 * mid-dive. Cancelling also skips {@code playAirBubblePoppedSound}, which lives
 * only in this method. The {@code player} arg is the CAMERA player vanilla
 * passes — correct for F5/spectating. Air VALUE management (the swim ability's
 * pinned-full air, the land drain) is untouched; on demorph the row reappears
 * at whatever the real air value is.</p>
 *
 * <p>Cancels ONLY while the eye is in water (wave 6 round 2, review R1): a
 * STRICTLY-AQUATIC morph suffocating ON LAND drains air 1/tick and takes
 * drowning damage — the emptying row IS that feature's designed feedback (the
 * original's AbilitySwim likewise hid vanilla air only in water and drew a
 * draining row on land). SWIM-only cancellation was hiding it.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original design to iChun.</p>
 */
@Mixin(Hud.class)
public abstract class HudAirBubblesMixin {

    @Inject(method = "extractAirBubbles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;III)V",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$hideBubblesForSwimMorph(
            GuiGraphicsExtractor graphics, Player player, int width, int height,
            int y, CallbackInfo ci) {
        if (player.isEyeInFluid(FluidTags.WATER)
                && MorphAbilities.activeAbilities(player)
                        .contains(MorphAbility.SWIM)) {
            ci.cancel();
        }
    }
}
