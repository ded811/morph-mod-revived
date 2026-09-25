package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphAbilities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The {@code fireImmunity} ability (spec §A.1 #5). {@code Entity.fireImmune()}
 * is the public 26.2 override point (no reflection into a private field needed):
 * while a morphed player's committed set contains {@code FIRE_IMMUNITY} this
 * returns {@code true}, so the player takes no fire/lava damage. Guarded to
 * players; every other entity keeps its own immunity. Frozen contract: morph as
 * a fire-immune type ⇒ {@code player.fireImmune() == true}; after demorph ⇒
 * {@code false}.
 */
@Mixin(Entity.class)
public abstract class EntityFireImmuneMixin {

    @Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphFireImmune(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Player player
                && MorphAbilities.isFireImmune(player)) {
            cir.setReturnValue(true);
        }
    }
}
