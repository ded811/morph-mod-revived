package com.deds.morph.gametest.neoforge.client.mixin;

import com.deds.morph.gametest.neoforge.client.ClientTestInput;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the NeoForge client test driver runs, the game window never grabs
 * the mouse (Fabric's client gametest harness cancels the grab too).
 *
 * <p>A grabbed mouse turns the camera with the real pointer and aims Morph's
 * favourites radial with it, so moving the mouse over a focused test window
 * would change the screenshots and the radial's pick. Ungrabbed, pointer
 * motion is not accumulated at all; clicks still reach
 * {@code MouseHandler.onButton}, which does not require a grab to press a
 * key mapping, and the driver calls that method directly. {@code grabMouse()}
 * has the same shape in 26.2 and 26.3.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerTestMixin {

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void deds_morph_test$neverGrab(CallbackInfo ci) {
        if (ClientTestInput.ENABLED) {
            ci.cancel();
        }
    }
}
