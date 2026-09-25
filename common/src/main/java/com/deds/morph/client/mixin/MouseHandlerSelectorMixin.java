package com.deds.morph.client.mixin;

import com.deds.morph.client.MorphSelector;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the morph selector is open, a left click picks the highlighted form and
 * a right click closes the selector - and the game does not ALSO see the click.
 * The selector used to poll the mouse flags, so the same click still swung at
 * or broke whatever was under the crosshair, or used the held item.
 * {@link MorphSelector#handleClick} decides; it only swallows a release whose
 * press it swallowed, so a button held from before the selector opened is
 * never left stuck down.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerSelectorMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void deds_morph$selectorClicks(long handle, MouseButtonInfo button,
            int action, CallbackInfo ci) {
        if (MorphSelector.handleClick(button.button(), action == 1)) {
            ci.cancel();
        }
    }
}
