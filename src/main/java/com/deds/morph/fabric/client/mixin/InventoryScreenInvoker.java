package com.deds.morph.fabric.client.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code InventoryScreen}'s private static
 * {@code extractRenderState(LivingEntity)} so the selector/radial can build a
 * GUI entity render state and set {@code bodyRot}/{@code yRot}/{@code xRot}
 * directly — reproducing the ORIGINAL's fixed three-quarter FRONT facing
 * (glRotatef(25) yaw + glRotatef(15) forward tilt) WITHOUT the
 * {@code extractEntityInInventoryFollowsMouse} wrapper's camera-tilt coupling
 * (which drove both the model xRot and the camera orientation off the same mouse
 * term, distorting the pose).
 *
 * <p>javap-verified target: {@code private static
 * net.minecraft.client.renderer.entity.state.EntityRenderState
 * InventoryScreen.extractRenderState(net.minecraft.world.entity.LivingEntity)}.</p>
 */
@Mixin(InventoryScreen.class)
public interface InventoryScreenInvoker {

    @Invoker("extractRenderState")
    static EntityRenderState deds_morph$extractRenderState(LivingEntity entity) {
        throw new AssertionError();
    }
}
