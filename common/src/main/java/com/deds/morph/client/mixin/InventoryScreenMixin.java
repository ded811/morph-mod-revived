package com.deds.morph.client.mixin;

import com.deds.morph.client.MorphDummies;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes any "render the player in a GUI" show the local player's MORPH instead
 * of the vanilla player. The survival inventory (and every mod GUI that uses
 * it) draws the player through the static
 * {@link InventoryScreen#extractEntityInInventoryFollowsMouse} — which calls the
 * renderer directly, NOT through {@code EntityRenderDispatcher.extractEntity},
 * so the world-render {@code EntityRenderDispatcherMixin} never fires for it.
 * We redirect the {@link LivingEntity} argument to the morph dummy for a morphed
 * local player (with its adopted armour), so the inventory shows the morph.
 *
 * <p>{@link MorphDummies#guiPreviewSwap} leaves non-player entities untouched
 * and is suppressed while our own selector/radial render their own-form preview
 * (which must stay the player). javap-verified target: {@code InventoryScreen
 * .extractEntityInInventoryFollowsMouse(GuiGraphicsExtractor,int,int,int,int,int,
 * float,float,float,LivingEntity)}.</p>
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {

    @ModifyVariable(
            method = "extractEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            argsOnly = true)
    private static LivingEntity deds_morph$swapMorphIntoGui(LivingEntity entity) {
        return MorphDummies.guiPreviewSwap(entity);
    }
}
