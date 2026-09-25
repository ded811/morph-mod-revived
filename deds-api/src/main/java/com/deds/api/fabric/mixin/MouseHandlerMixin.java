package com.deds.api.fabric.mixin;

import com.deds.api.client.ClientInput;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The platform backing of {@link ClientInput}: the two pieces of mouse input
 * the vanilla surface keeps private (verified via javap — both
 * {@code onScroll(long,double,double)} and the {@code accumulatedDX/DY} fields
 * are {@code private}).
 *
 * <ul>
 *   <li><b>Scroll:</b> at the head of {@code onScroll} we offer the wheel to
 *       {@link ClientInput}'s listeners; if one consumes it we cancel, which
 *       stops the vanilla hotbar slot change (the original's
 *       {@code event.setCanceled(true)}).</li>
 *   <li><b>Motion + camera lock:</b> {@code handleAccumulatedMovement} runs
 *       once per frame and turns the accumulated raw delta into a camera turn.
 *       While {@link ClientInput#isSuppressCameraTurn()} is set we snapshot the
 *       delta, hand it to the motion listeners (radial aim), and zero the
 *       accumulators at the head — so the rest of the method (smoothing +
 *       {@code turnPlayer}) sees no motion and the view stays locked. This is
 *       robust to the internal {@code turnPlayer} details because it consumes
 *       the input before any of them read it.</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void deds_api$onScroll(long window, double horizontal,
            double vertical, CallbackInfo ci) {
        if (ClientInput.dispatchScroll(horizontal, vertical)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void deds_api$captureMotion(CallbackInfo ci) {
        if (!ClientInput.isSuppressCameraTurn()) {
            return;
        }
        if (accumulatedDX != 0.0 || accumulatedDY != 0.0) {
            ClientInput.dispatchMotion(accumulatedDX, accumulatedDY);
            accumulatedDX = 0.0;
            accumulatedDY = 0.0;
        }
    }
}
