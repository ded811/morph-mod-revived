package com.deds.morph.gametest.neoforge.client.mixin;

import com.deds.morph.gametest.neoforge.client.ClientTestInput;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import com.mojang.blaze3d.platform.InputConstants;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the NeoForge client test driver runs, a key reads as down exactly
 * when the driver holds it ({@link ClientTestInput}), the way Fabric's client
 * gametest harness answers {@code isKeyDown}.
 *
 * <p>One source for both Minecraft versions: 26.2's method is
 * {@code isKeyDown(Window, int)} (GLFW) and 26.3's is {@code isKeyDown(int)}
 * (SDL), and in each the key code is the only {@code int} argument, which is
 * what {@code @Local(argsOnly = true) int} captures. {@code defaultRequire}
 * is 1, so a version with no such method, or with a second {@code int}
 * argument, fails the run at startup instead of leaving the driver holding
 * keys nobody reads.</p>
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsTestMixin {

    @ModifyReturnValue(method = "isKeyDown", at = @At("RETURN"))
    private static boolean deds_morph_test$driverKeys(boolean original,
            @Local(argsOnly = true) int key) {
        return ClientTestInput.ENABLED ? ClientTestInput.isKeyDown(key) : original;
    }
}
